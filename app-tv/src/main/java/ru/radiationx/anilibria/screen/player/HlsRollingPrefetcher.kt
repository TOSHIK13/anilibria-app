package ru.radiationx.anilibria.screen.player

import android.annotation.SuppressLint
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
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
import ru.radiationx.data.player.PlayerCacheDataSourceProvider
import timber.log.Timber
import java.io.IOException
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
    val playbackState: Int,
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

object HlsPrefetchPlanner {

    const val CURRENT_AHEAD_MS = 30L * 60L * 1_000L
    const val NEXT_AHEAD_MS = 5L * 60L * 1_000L

    fun planCurrent(
        descriptor: HlsPlaylistCacheDescriptor?,
        positionMs: Long,
        aheadMs: Long = CURRENT_AHEAD_MS,
        isCached: (HlsPlaylistSegment) -> Boolean,
    ): List<HlsPlaylistSegment> {
        val windowStartMs = positionMs.coerceAtLeast(0L)
        val windowEndMs = windowStartMs + aheadMs.coerceAtLeast(0L)
        return descriptor
            .planWindow(windowStartMs, windowEndMs, isCached)
    }

    fun planNext(
        descriptor: HlsPlaylistCacheDescriptor?,
        aheadMs: Long = NEXT_AHEAD_MS,
        isCached: (HlsPlaylistSegment) -> Boolean,
    ): List<HlsPlaylistSegment> {
        return descriptor.planWindow(0L, aheadMs.coerceAtLeast(0L), isCached)
    }

    private fun HlsPlaylistCacheDescriptor?.planWindow(
        windowStartMs: Long,
        windowEndMs: Long,
        isCached: (HlsPlaylistSegment) -> Boolean,
    ): List<HlsPlaylistSegment> {
        if (this == null || windowEndMs <= windowStartMs) {
            return emptyList()
        }
        return segments
            .asSequence()
            .filter { segment -> segment.endMs > windowStartMs && segment.startMs < windowEndMs }
            .sortedBy { it.startMs }
            .filterNot(isCached)
            .toList()
    }
}

@SuppressLint("UnsafeOptInUsageError")
class HlsRollingPrefetcher(
    private val cacheProvider: PlayerCacheDataSourceProvider,
    private val upstreamFactory: DataSource.Factory,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeWriter = AtomicReference<CacheWriter?>(null)
    private val _state = MutableStateFlow(HlsRollingPrefetchState())

    val state: StateFlow<HlsRollingPrefetchState> = _state

    @Volatile
    private var session: HlsPrefetchSession? = null

    @Volatile
    private var playbackState = HlsPrefetchPlaybackState(
        positionMs = 0L,
        durationMs = 0L,
        playbackState = Player.STATE_IDLE,
        rebufferCount = 0,
        diskCacheEnabled = false,
        preloadNextEpisode = false,
    )

    @Volatile
    private var workerJob: Job? = null

    @Volatile
    private var lastWindowPositionMs = 0L

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
            runWorker()
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
                playback.playbackState == Player.STATE_BUFFERING -> {
                    activeWriter.get()?.cancel()
                    setState(HlsRollingPrefetchMode.PausedPlayback)
                    delay(PAUSED_DELAY_MS)
                }
                isHeapGuardActive() -> {
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

    private suspend fun downloadNextSegment(
        session: HlsPrefetchSession,
        playback: HlsPrefetchPlaybackState,
    ) {
        val currentPlan = HlsPrefetchPlanner.planCurrent(
            descriptor = session.current,
            positionMs = playback.positionMs,
            isCached = ::isSegmentCached,
        )
        val segment = if (currentPlan.isNotEmpty()) {
            setState(HlsRollingPrefetchMode.Current)
            currentPlan.first()
        } else {
            val nextPlan = if (playback.preloadNextEpisode && session.preloadNextEpisode) {
                HlsPrefetchPlanner.planNext(
                    descriptor = session.next,
                    isCached = ::isSegmentCached,
                )
            } else {
                emptyList()
            }
            if (nextPlan.isNotEmpty()) {
                setState(HlsRollingPrefetchMode.Next)
                nextPlan.first()
            } else {
                setState(HlsRollingPrefetchMode.Idle)
                delay(IDLE_DELAY_MS)
                return
            }
        }

        if (isSegmentCached(segment)) {
            delay(SHORT_DELAY_MS)
            return
        }
        cacheSegment(segment)
    }

    private fun isSegmentCached(segment: HlsPlaylistSegment): Boolean {
        return cacheProvider.isSegmentFullyCached(
            key = segment.key,
            position = segment.byteRangeOffset,
            expectedLengthBytes = segment.expectedLengthBytes,
        )
    }

    private suspend fun cacheSegment(segment: HlsPlaylistSegment) {
        val dataSource = cacheProvider.createPrefetchCacheDataSource(upstreamFactory)
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
        try {
            writer.cache()
            consecutiveErrors = 0
            backoffUntilMs = 0L
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: IOException) {
            handleDownloadError(ex)
        } catch (ex: Throwable) {
            handleDownloadError(ex)
        } finally {
            activeWriter.compareAndSet(writer, null)
        }
    }

    private fun handleDownloadError(error: Throwable) {
        consecutiveErrors = (consecutiveErrors + 1).coerceAtMost(BACKOFF_DELAYS_MS.size)
        val delayMs = BACKOFF_DELAYS_MS[consecutiveErrors - 1]
        backoffUntilMs = System.currentTimeMillis() + delayMs
        Timber.w(error, "hls rolling prefetch failed, backoff=${delayMs}ms")
        setState(HlsRollingPrefetchMode.Backoff)
    }

    private fun isHeapGuardActive(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemoryBytes = runtime.maxMemory().coerceAtLeast(1L)
        val usedMemoryBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        return usedMemoryBytes.toFloat() / maxMemoryBytes.toFloat() >= HEAP_DISABLE_RATIO
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
        private const val HEAP_DISABLE_RATIO = 0.75f
        private const val SEEK_REPLAN_THRESHOLD_MS = 30_000L
        private const val IDLE_DELAY_MS = 5_000L
        private const val PAUSED_DELAY_MS = 1_000L
        private const val SHORT_DELAY_MS = 250L
        private val BACKOFF_DELAYS_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L)
    }
}
