package ru.radiationx.anilibria.screen.player

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PriorityTaskManager
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.PriorityDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.radiationx.data.player.PlayerBufferConfig
import ru.radiationx.data.player.PlayerCacheDataSourceProvider
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

data class HlsPrefetchSession(
    val current: HlsPlaylistCacheDescriptor?,
    val next: HlsPlaylistCacheDescriptor?,
    val positionMs: Long,
    val diskCacheEnabled: Boolean,
    val preloadNextEpisode: Boolean,
)

data class HlsPrefetchPlaybackState(
    val positionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
    val playbackState: Int,
    val isPlaying: Boolean,
    val rebufferCount: Int,
    val diskCacheEnabled: Boolean,
    val preloadNextEpisode: Boolean,
)

data class HlsRollingPrefetchState(
    val mode: HlsRollingPrefetchMode = HlsRollingPrefetchMode.Stopped,
    val label: String = "Выкл",
)

enum class HlsRollingPrefetchMode {
    Idle,
    Current,
    Next,
    PausedPlayback,
    PausedMemory,
    Backoff,
    Disabled,
    Stopped,
}

data class HlsPrefetchTarget(
    val segment: HlsPlaylistSegment,
    val mode: HlsRollingPrefetchMode,
)

/**
 * Порядок предзагрузки:
 * 1. текущая серия: позиция + 10 минут;
 * 2. следующая серия: первые 120 секунд (начинается, когда текущая закэширована на 10 минут вперёд
 *    или до конца, т.е. когда шаг 1 выполнен);
 * 3. текущая серия до конца;
 * 4. следующая серия до 10 минут.
 */
object HlsPrefetchPlanner {

    const val CURRENT_FIRST_AHEAD_MS = 10L * 60L * 1_000L
    const val NEXT_FIRST_AHEAD_MS = 120L * 1_000L
    const val NEXT_AHEAD_MS = 10L * 60L * 1_000L

    fun nextTarget(
        current: HlsPlaylistCacheDescriptor?,
        next: HlsPlaylistCacheDescriptor?,
        positionMs: Long,
        includeNext: Boolean,
        isCached: (HlsPlaylistSegment) -> Boolean,
    ): HlsPrefetchTarget? {
        val startMs = positionMs.coerceAtLeast(0L)
        current.firstUncached(startMs, startMs + CURRENT_FIRST_AHEAD_MS, isCached)
            ?.let { return HlsPrefetchTarget(it, HlsRollingPrefetchMode.Current) }
        if (includeNext) {
            next.firstUncached(0L, NEXT_FIRST_AHEAD_MS, isCached)
                ?.let { return HlsPrefetchTarget(it, HlsRollingPrefetchMode.Next) }
        }
        current.firstUncached(startMs, Long.MAX_VALUE, isCached)
            ?.let { return HlsPrefetchTarget(it, HlsRollingPrefetchMode.Current) }
        if (includeNext) {
            next.firstUncached(0L, NEXT_AHEAD_MS, isCached)
                ?.let { return HlsPrefetchTarget(it, HlsRollingPrefetchMode.Next) }
        }
        return null
    }

    private fun HlsPlaylistCacheDescriptor?.firstUncached(
        windowStartMs: Long,
        windowEndMs: Long,
        isCached: (HlsPlaylistSegment) -> Boolean,
    ): HlsPlaylistSegment? {
        if (this == null || windowEndMs <= windowStartMs) {
            return null
        }
        return segments
            .asSequence()
            .filter { segment -> segment.endMs > windowStartMs && segment.startMs < windowEndMs }
            .sortedBy { it.startMs }
            .firstOrNull { !isCached(it) }
    }
}

@SuppressLint("UnsafeOptInUsageError")
class HlsRollingPrefetcher(
    private val cacheProvider: PlayerCacheDataSourceProvider,
    upstreamFactory: DataSource.Factory,
    private val priorityTaskManager: PriorityTaskManager,
    private val bufferMemoryLimitMb: () -> Int,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeWriter = AtomicReference<CacheWriter?>(null)
    private val _state = MutableStateFlow(HlsRollingPrefetchState())

    // Сетевые чтения prefetch-а уступают плееру: пока плеер грузит, бросается PriorityTooLowException.
    private val priorityUpstreamFactory = PriorityDataSource.Factory(
        upstreamFactory,
        priorityTaskManager,
        C.PRIORITY_DOWNLOAD,
    )

    val state: StateFlow<HlsRollingPrefetchState> = _state

    @Volatile
    private var session: HlsPrefetchSession? = null

    @Volatile
    private var playbackState = HlsPrefetchPlaybackState(
        positionMs = 0L,
        durationMs = 0L,
        bufferedPositionMs = 0L,
        playbackState = Player.STATE_IDLE,
        isPlaying = false,
        rebufferCount = 0,
        diskCacheEnabled = false,
        preloadNextEpisode = false,
    )

    @Volatile
    private var workerJob: Job? = null

    @Volatile
    private var lastWindowPositionMs = 0L

    @Volatile
    private var memoryPauseUntilMs = 0L

    private var consecutiveErrors = 0
    private var backoffUntilMs = 0L

    fun start(session: HlsPrefetchSession) {
        stopWorker()
        this.session = session
        playbackState = playbackState.copy(
            positionMs = session.positionMs,
            diskCacheEnabled = session.diskCacheEnabled,
            preloadNextEpisode = session.preloadNextEpisode,
        )
        lastWindowPositionMs = session.positionMs
        consecutiveErrors = 0
        backoffUntilMs = 0L
        startWorker()
    }

    fun update(playback: HlsPrefetchPlaybackState) {
        val previousPosition = playbackState.positionMs
        playbackState = playback
        if (abs(playback.positionMs - previousPosition) >= SEEK_REPLAN_THRESHOLD_MS) {
            activeWriter.get()?.cancel()
            lastWindowPositionMs = playback.positionMs
        }
        if (workerJob?.isActive != true && session != null) {
            startWorker()
        }
    }

    /**
     * Реакция на onTrimMemory: пауза на [durationMs], при [cancelActive] прерывается и текущая загрузка.
     */
    fun pauseForMemoryPressure(durationMs: Long, cancelActive: Boolean) {
        memoryPauseUntilMs = maxOf(memoryPauseUntilMs, System.currentTimeMillis() + durationMs)
        if (cancelActive) {
            activeWriter.get()?.cancel()
        }
    }

    fun stop() {
        stopWorker()
        session = null
        _state.value = HlsRollingPrefetchState()
    }

    private fun startWorker() {
        if (workerJob?.isActive == true) {
            return
        }
        workerJob = scope.launch {
            // PriorityTaskManager пропускает только зарегистрированный приоритет:
            // без add() proceedNonBlocking(PRIORITY_DOWNLOAD) всегда false.
            priorityTaskManager.add(C.PRIORITY_DOWNLOAD)
            try {
                runWorker()
            } finally {
                priorityTaskManager.remove(C.PRIORITY_DOWNLOAD)
            }
        }
    }

    private fun stopWorker() {
        activeWriter.getAndSet(null)?.cancel()
        workerJob?.cancel()
        workerJob = null
    }

    private suspend fun runWorker() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val currentSession = session
            if (currentSession == null) {
                setState(HlsRollingPrefetchMode.Stopped)
                delay(IDLE_DELAY_MS)
                continue
            }
            val playback = playbackState
            when {
                !currentSession.diskCacheEnabled || !playback.diskCacheEnabled || !cacheProvider.isEnabled() -> {
                    setState(HlsRollingPrefetchMode.Disabled)
                    delay(PAUSED_DELAY_MS)
                }
                CdnRateLimitGate.remainingMs() > 0L -> {
                    // CDN ограничил загрузку (429/5xx у плеера или prefetch-а): ждём с запасом, плеер первым.
                    setState(HlsRollingPrefetchMode.Backoff)
                    delay(CdnRateLimitGate.remainingMs().coerceIn(PAUSED_DELAY_MS, RATE_LIMIT_POLL_MAX_MS))
                    backoffUntilMs = maxOf(backoffUntilMs, System.currentTimeMillis() + RATE_LIMIT_GRACE_MS)
                }
                // BUFFERING (в т.ч. после перемотки) и IDLE (ошибка, плеер восстанавливается): не мешаем плееру.
                playback.playbackState == Player.STATE_BUFFERING ||
                    playback.playbackState == Player.STATE_IDLE -> {
                    activeWriter.get()?.cancel()
                    setState(HlsRollingPrefetchMode.PausedPlayback)
                    delay(PAUSED_DELAY_MS)
                }
                isPlayerBufferLow(playback) || !priorityTaskManager.proceedNonBlocking(C.PRIORITY_DOWNLOAD) -> {
                    setState(HlsRollingPrefetchMode.PausedPlayback)
                    delay(PRIORITY_RETRY_DELAY_MS)
                }
                System.currentTimeMillis() < memoryPauseUntilMs ||
                    PlayerBufferConfig.isHeapHeadroomLow(bufferMemoryLimitMb()) -> {
                    activeWriter.get()?.cancel()
                    setState(HlsRollingPrefetchMode.PausedMemory)
                    delay(PAUSED_DELAY_MS)
                }
                System.currentTimeMillis() < backoffUntilMs -> {
                    setState(HlsRollingPrefetchMode.Backoff)
                    delay(PAUSED_DELAY_MS)
                }
                else -> {
                    downloadNextSegment(currentSession, playback)
                }
            }
        }
    }

    /** Плеер играет, но его RAM-буфер почти пуст: не отнимаем у него канал. */
    private fun isPlayerBufferLow(playback: HlsPrefetchPlaybackState): Boolean {
        if (playback.playbackState != Player.STATE_READY || !playback.isPlaying) {
            return false
        }
        if (playback.durationMs > 0L && playback.bufferedPositionMs >= playback.durationMs) {
            return false
        }
        return playback.bufferedPositionMs - playback.positionMs < MIN_PLAYER_BUFFER_AHEAD_MS
    }

    private suspend fun downloadNextSegment(
        session: HlsPrefetchSession,
        playback: HlsPrefetchPlaybackState,
    ) {
        val target = HlsPrefetchPlanner.nextTarget(
            current = session.current,
            next = session.next,
            positionMs = playback.positionMs,
            includeNext = playback.preloadNextEpisode && session.preloadNextEpisode,
            isCached = ::isSegmentCached,
        )
        if (target == null) {
            setState(HlsRollingPrefetchMode.Idle)
            delay(IDLE_DELAY_MS)
            return
        }
        setState(target.mode)
        cacheSegment(target.segment)
    }

    private fun isSegmentCached(segment: HlsPlaylistSegment): Boolean {
        return cacheProvider.isSegmentFullyCached(
            key = segment.key,
            position = segment.byteRangeOffset,
            expectedLengthBytes = segment.expectedLengthBytes,
        )
    }

    private suspend fun cacheSegment(segment: HlsPlaylistSegment) {
        val dataSource = cacheProvider.createPrefetchCacheDataSource(priorityUpstreamFactory)
        if (dataSource == null) {
            setState(HlsRollingPrefetchMode.Disabled)
            delay(PAUSED_DELAY_MS)
            return
        }
        val length = segment.expectedLengthBytes ?: C.LENGTH_UNSET.toLong()
        val dataSpec = DataSpec(
            Uri.parse(segment.key),
            segment.byteRangeOffset.coerceAtLeast(0L),
            length,
            segment.key,
        )
        val writer = CacheWriter(
            dataSource,
            dataSpec,
            ByteArray(CacheWriter.DEFAULT_BUFFER_SIZE_BYTES),
            null,
        )
        activeWriter.set(writer)
        var priorityTooLow = false
        try {
            writer.cache()
            consecutiveErrors = 0
            backoffUntilMs = 0L
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Throwable) {
            if (ex.isPriorityTooLow()) {
                priorityTooLow = true
            } else if (!ex.isWriterCancelled()) {
                handleDownloadError(ex)
            }
        } finally {
            activeWriter.compareAndSet(writer, null)
        }
        if (priorityTooLow) {
            // Плеер начал грузить: уступаем и повторяем позже, это не сетевая ошибка.
            setState(HlsRollingPrefetchMode.PausedPlayback)
            delay(PRIORITY_RETRY_DELAY_MS)
        }
    }

    private fun Throwable.isPriorityTooLow(): Boolean {
        return generateSequence(this) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .any { it is PriorityTaskManager.PriorityTooLowException }
    }

    // CacheWriter.cancel() прерывает загрузку InterruptedIOException: это не ошибка сети.
    private fun Throwable.isWriterCancelled(): Boolean {
        return this is InterruptedIOException && this !is SocketTimeoutException
    }

    private fun handleDownloadError(error: Throwable) {
        consecutiveErrors = (consecutiveErrors + 1).coerceAtMost(BACKOFF_DELAYS_MS.size)
        val delayMs = BACKOFF_DELAYS_MS[consecutiveErrors - 1]
        backoffUntilMs = System.currentTimeMillis() + delayMs
        error.findInvalidResponseCode()
            ?.takeIf { RateLimitBackoff.isRateLimitStatus(it.responseCode) }
            ?.let {
                // Свой backoff prefetch-а (15+ с) не навязываем плееру: в общий gate — как первый повтор плеера.
                val gateMs = RateLimitBackoff.retryDelayMs(1, RateLimitBackoff.retryAfterHeader(it.headerFields))
                CdnRateLimitGate.report(gateMs, source = "prefetch", responseCode = it.responseCode)
            }
        Log.w(PLAYER_NET_TAG, "hls rolling prefetch failed, backoff=${delayMs}ms", error)
        setState(HlsRollingPrefetchMode.Backoff)
    }

    private fun setState(mode: HlsRollingPrefetchMode) {
        val label = when (mode) {
            HlsRollingPrefetchMode.Idle -> "Готово"
            HlsRollingPrefetchMode.Current -> "Текущая"
            HlsRollingPrefetchMode.Next -> "Следующая"
            HlsRollingPrefetchMode.PausedPlayback -> "Пауза: плеер"
            HlsRollingPrefetchMode.PausedMemory -> "Пауза: память"
            HlsRollingPrefetchMode.Backoff -> "Ошибка сети"
            HlsRollingPrefetchMode.Disabled -> "Выкл"
            HlsRollingPrefetchMode.Stopped -> "Выкл"
        }
        val newState = HlsRollingPrefetchState(mode, label)
        if (_state.value != newState) {
            _state.value = newState
        }
    }

    private companion object {
        private const val SEEK_REPLAN_THRESHOLD_MS = 30_000L
        private const val MIN_PLAYER_BUFFER_AHEAD_MS = 10_000L
        private const val IDLE_DELAY_MS = 5_000L
        private const val PAUSED_DELAY_MS = 1_000L
        private const val PRIORITY_RETRY_DELAY_MS = 750L
        private const val MAX_CAUSE_DEPTH = 5
        private const val RATE_LIMIT_POLL_MAX_MS = 5_000L
        /** После снятия ограничения CDN даём плееру фору перед возобновлением prefetch-а. */
        private const val RATE_LIMIT_GRACE_MS = 5_000L
        private val BACKOFF_DELAYS_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L)
    }
}
