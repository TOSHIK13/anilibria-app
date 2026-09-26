package ru.radiationx.anilibria.screen.player

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import ru.radiationx.data.system.LoadTiming
import java.io.IOException

/**
 * Дешёвая диагностика плеера.
 *
 * - `PlayerNet`: каждая загрузка (сегмент/плейлист). Включено в debug, в release — только
 *   `adb shell setprop log.tag.PlayerNet DEBUG` (или LoadTiming), как в [LoadTiming].
 *   Ошибки загрузки логируются всегда.
 * - `PlayerFlow`: TTFF, ребуферы, переходы MediaItem — всегда (мало строк).
 * - `PlayerMem`: раз в [MEM_LOG_INTERVAL_MS] во время воспроизведения — всегда.
 */
@UnstableApi
class PlayerDiagnostics : AnalyticsListener {

    private val netLogEnabled: Boolean = LoadTiming.enabled ||
        runCatching { Log.isLoggable(PLAYER_NET_TAG, Log.DEBUG) }.getOrDefault(false)

    private var ttffStartAt = 0L
    private var ttffCause = ""
    private var bufferingStartAt = 0L
    private var bufferingCause = ""
    private var lastSeekAt = 0L
    private var lastMemLogAt = 0L
    private var netBytes = 0L
    private var cacheBytes = 0L
    private var rebuffers = 0

    /** Старт отсчёта TTFF: setMediaItems/prepare или переход очереди. */
    fun markLoadStart(cause: String) {
        ttffStartAt = SystemClock.elapsedRealtime()
        ttffCause = cause
    }

    fun maybeLogMemory(isPlaying: Boolean, extra: () -> String) {
        if (!isPlaying) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastMemLogAt < MEM_LOG_INTERVAL_MS) {
            return
        }
        lastMemLogAt = now
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()).toMb()
        val maxMb = runtime.maxMemory().toMb()
        val nativeMb = Debug.getNativeHeapAllocatedSize().toMb()
        Log.d(
            MEM_TAG,
            "java=${usedMb}/${maxMb}MB native=${nativeMb}MB net=${netBytes.toMb()}MB cacheRead=${cacheBytes.toMb()}MB rebuffers=$rebuffers ${extra()}"
        )
    }

    override fun onMediaItemTransition(
        eventTime: AnalyticsListener.EventTime,
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        val label = reason.transitionReasonLabel()
        Log.d(
            FLOW_TAG,
            "media-item-transition reason=$label window=${eventTime.currentWindowIndex} uri=${mediaItem?.localConfiguration?.uri?.toString().tail()}"
        )
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            markLoadStart("transition:$label")
        }
    }

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long,
    ) {
        val startedAt = ttffStartAt
        if (startedAt == 0L) {
            return
        }
        ttffStartAt = 0L
        Log.d(
            FLOW_TAG,
            "ttff cause=$ttffCause ms=${SystemClock.elapsedRealtime() - startedAt} window=${eventTime.currentWindowIndex} pos=${eventTime.eventPlaybackPositionMs}"
        )
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            lastSeekAt = SystemClock.elapsedRealtime()
        }
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_BUFFERING -> {
                if (bufferingStartAt != 0L) {
                    return
                }
                bufferingStartAt = now
                bufferingCause = when {
                    ttffStartAt != 0L && now - ttffStartAt < TTFF_STALE_MS -> "load"
                    now - lastSeekAt < SEEK_BUFFER_WINDOW_MS -> "seek"
                    else -> "rebuffer"
                }
            }

            Player.STATE_READY -> {
                val startedAt = bufferingStartAt
                if (startedAt == 0L) {
                    return
                }
                bufferingStartAt = 0L
                val durationMs = now - startedAt
                if (bufferingCause == "rebuffer") {
                    rebuffers += 1
                    Log.d(FLOW_TAG, "rebuffer ms=$durationMs pos=${eventTime.eventPlaybackPositionMs} window=${eventTime.currentWindowIndex} total=$rebuffers")
                } else {
                    Log.d(FLOW_TAG, "buffering cause=$bufferingCause ms=$durationMs pos=${eventTime.eventPlaybackPositionMs}")
                }
            }

            else -> bufferingStartAt = 0L
        }
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        // CacheDataSource отдаёт заголовки только при чтении из сети: пустые заголовки = чтение из кэша.
        val fromCache = loadEventInfo.responseHeaders.isEmpty()
        val bytes = loadEventInfo.bytesLoaded
        if (fromCache) {
            cacheBytes += bytes
        } else {
            netBytes += bytes
        }
        if (!netLogEnabled) {
            return
        }
        val ms = loadEventInfo.loadDurationMs
        val kbps = if (ms > 0L) bytes * 8L / ms else 0L
        Log.d(
            PLAYER_NET_TAG,
            "load src=${if (fromCache) "cache" else "net"} type=${mediaLoadData.dataType.dataTypeLabel()} track=${mediaLoadData.trackType.trackTypeLabel()} bytes=$bytes ms=$ms kbps=$kbps window=${eventTime.windowIndex} uri=${loadEventInfo.uri.toString().tail()}"
        )
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        Log.w(
            PLAYER_NET_TAG,
            "load-error type=${mediaLoadData.dataType.dataTypeLabel()} track=${mediaLoadData.trackType.trackTypeLabel()} bytes=${loadEventInfo.bytesLoaded} ms=${loadEventInfo.loadDurationMs} canceled=$wasCanceled error=${error.javaClass.simpleName}: ${error.message} uri=${loadEventInfo.uri.toString().tail()}"
        )
    }

    private companion object {
        const val FLOW_TAG = "PlayerFlow"
        const val MEM_TAG = "PlayerMem"
        const val MEM_LOG_INTERVAL_MS = 5_000L
        const val TTFF_STALE_MS = 30_000L
        const val SEEK_BUFFER_WINDOW_MS = 2_000L
        const val URI_TAIL_CHARS = 40

        fun Long.toMb(): Long = this / (1024L * 1024L)

        fun String?.tail(): String {
            if (this == null) {
                return "null"
            }
            return if (length <= URI_TAIL_CHARS) this else "..." + takeLast(URI_TAIL_CHARS)
        }

        fun Int.dataTypeLabel(): String = when (this) {
            C.DATA_TYPE_MEDIA -> "media"
            C.DATA_TYPE_MEDIA_INITIALIZATION -> "init"
            C.DATA_TYPE_MANIFEST -> "manifest"
            C.DATA_TYPE_DRM -> "drm"
            else -> toString()
        }

        fun Int.trackTypeLabel(): String = when (this) {
            C.TRACK_TYPE_VIDEO -> "video"
            C.TRACK_TYPE_AUDIO -> "audio"
            C.TRACK_TYPE_TEXT -> "text"
            C.TRACK_TYPE_DEFAULT -> "default"
            C.TRACK_TYPE_UNKNOWN -> "unknown"
            else -> toString()
        }

        fun Int.transitionReasonLabel(): String = when (this) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist_changed"
            else -> toString()
        }
    }
}
