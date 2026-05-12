package ru.radiationx.player.tv

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import ru.radiationx.data.player.PlayerBufferConfig
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@UnstableApi
open class ReusableTvPlayerView(context: Context) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val hudHandler = Handler(Looper.getMainLooper())
    private val statsRefresh = object : Runnable {
        override fun run() {
            refreshCompose()
            handler.postDelayed(this, STATS_REFRESH_MS)
        }
    }

    private lateinit var composeView: ComposeView
    private lateinit var videoPlayerView: PlayerView
    private var player: ExoPlayer? = null
    private var lastIntent: Intent? = null
    private var lastPlayerError: String? = null
    private var lastPlaybackState: Int = Player.STATE_IDLE
    private var composeVersion by mutableIntStateOf(0)
    private var currentSpeed = 1f
    private var overlayVisible = true
    private var activeSubmenu: TvPlayerSubmenuType? = null
    private var statsOverlayVisible = false
    private var playPauseHud: Boolean? = null
    private var seekHud: Long? = null

    init {
        buildLayout()
    }

    fun handleStart() {
        handler.post(statsRefresh)
    }

    fun handleStop() {
        handler.removeCallbacks(statsRefresh)
        player?.pause()
    }

    fun release() {
        releasePlayer()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (activeSubmenu != null) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    activeSubmenu = null
                    refreshCompose()
                    true
                }
                else -> false
            }
        }
        if (!overlayVisible) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_SPACE -> {
                    togglePlay()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekBy(-SEEK_STEP_MS)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekBy(SEEK_STEP_MS)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    overlayVisible = true
                    refreshCompose()
                    true
                }
                else -> false
            }
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                togglePlay()
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-SEEK_STEP_MS)
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(SEEK_STEP_MS)
                true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                overlayVisible = false
                activeSubmenu = null
                refreshCompose()
                true
            }
            else -> false
        }
    }

    private fun buildLayout() {
        removeAllViews()
        videoPlayerView = PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            setBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = false
            isClickable = false
        }
        addView(
            videoPlayerView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            isFocusable = true
            isFocusableInTouchMode = true
            setContent {
                composeVersion
                TvPlayerScreen(
                    player = player,
                    controlsState = buildControlsState(),
                    snapshot = buildSnapshot(),
                    menuState = buildMenuState(),
                    stats = buildStatsState(),
                    overlayVisible = overlayVisible,
                    activeSubmenu = activeSubmenu,
                    statsOverlayVisible = statsOverlayVisible,
                    playPauseHud = playPauseHud,
                    seekHud = seekHud,
                    onShowOverlay = {
                        overlayVisible = true
                        refreshCompose()
                    },
                    onHideOverlay = {
                        overlayVisible = false
                        activeSubmenu = null
                        refreshCompose()
                    },
                    onCloseSubmenu = {
                        activeSubmenu = null
                        refreshCompose()
                    },
                    onSetActiveSubmenu = {
                        activeSubmenu = it
                        overlayVisible = true
                        refreshCompose()
                    },
                    onToggleStatsOverlay = {
                        statsOverlayVisible = !statsOverlayVisible
                        activeSubmenu = null
                        refreshCompose()
                    },
                    onTogglePlayPause = ::togglePlay,
                    onSeekBy = ::seekBy,
                    onSeekTo = ::seekTo,
                    onPrevious = {},
                    onNext = {},
                    onQualitySelected = ::applyQuality,
                    onSpeedSelected = ::applySpeed,
                )
            }
        }
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        composeView.post {
            composeView.requestFocus()
        }
    }

    fun play(intent: Intent?) {
        lastIntent = intent
        lastPlayerError = null
        lastPlaybackState = Player.STATE_IDLE
        currentSpeed = 1f
        overlayVisible = true
        activeSubmenu = null
        refreshCompose()

        val uri = resolveMediaUri(intent)
        if (uri == null) {
            releasePlayer()
            lastPlayerError = "No video URI"
            refreshCompose()
            return
        }

        releasePlayer()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        val headers = resolveHttpHeaders(intent)
        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val newPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(
                PlayerBufferConfig.createLoadControl(
                    FORWARD_BUFFER_SECONDS,
                    BACK_BUFFER_SECONDS,
                    BUFFER_MEMORY_LIMIT_MB,
                )
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()

        newPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                lastPlaybackState = playbackState
                refreshCompose()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refreshCompose()
            }

            override fun onTracksChanged(tracks: Tracks) {
                refreshCompose()
            }

            override fun onPlayerError(error: PlaybackException) {
                lastPlayerError = "${error::class.java.simpleName}: ${error.message}"
                refreshCompose()
            }
        })

        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        val type = resolveMediaMimeType(intent)
        if (type != null && type != "video/*") {
            mediaItemBuilder.setMimeType(type)
        }
        newPlayer.setMediaItem(mediaItemBuilder.build())
        newPlayer.setPlaybackParameters(PlaybackParameters(currentSpeed))
        newPlayer.playWhenReady = true
        newPlayer.prepare()
        player = newPlayer
        videoPlayerView.player = newPlayer
        refreshCompose()
    }

    private fun releasePlayer() {
        if (::videoPlayerView.isInitialized) {
            videoPlayerView.player = null
        }
        player?.release()
        player = null
    }

    private fun togglePlay() {
        val current = player ?: return
        if (current.isPlaying) current.pause() else current.play()
        showPlayPauseHud()
        refreshCompose()
    }

    private fun seekTo(positionMs: Long) {
        player?.seekTo(max(0L, positionMs))
        refreshCompose()
    }

    private fun seekBy(deltaMs: Long) {
        val current = player ?: return
        val duration = current.duration
        var target = current.currentPosition + deltaMs
        if (duration > 0 && duration != C.TIME_UNSET) {
            target = min(target, duration)
        }
        current.seekTo(max(0L, target))
        showSeekHud(deltaMs)
        refreshCompose()
    }

    private fun applySpeed(speed: Float) {
        currentSpeed = speed
        player?.playbackParameters = PlaybackParameters(speed)
        refreshCompose()
    }

    private fun applyQuality(id: String) {
        val current = player ?: return
        if (id == AUTO_QUALITY_ID) {
            current.trackSelectionParameters = current.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
            refreshCompose()
            return
        }
        getQualityOptions().firstOrNull { it.id == id }?.let { option ->
            current.trackSelectionParameters = current.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .addOverride(option.override)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build()
        }
        refreshCompose()
    }

    private fun showPlayPauseHud() {
        playPauseHud = player?.isPlaying == true
        refreshCompose()
        clearHudLater()
    }

    private fun showSeekHud(deltaMs: Long) {
        seekHud = deltaMs
        refreshCompose()
        clearHudLater()
    }

    private fun clearHudLater() {
        hudHandler.removeCallbacksAndMessages(null)
        hudHandler.postDelayed({
            playPauseHud = null
            seekHud = null
            refreshCompose()
        }, 800L)
    }

    private fun refreshCompose() {
        if (::composeView.isInitialized) {
            composeVersion += 1
        }
    }

    private fun buildControlsState(): TvPlayerControlsState {
        val intent = lastIntent
        val uri = resolveMediaUri(intent)
        val title = resolveTitle(intent) ?: uri?.host ?: "AniLiberty External Player"
        val subtitle = uri?.let { "${it.scheme}://${it.host}" }.orEmpty()
        return TvPlayerControlsState(title = title, subtitle = subtitle)
    }

    private fun buildSnapshot(): TvPlaybackSnapshot {
        val current = player ?: return TvPlaybackSnapshot()
        return TvPlaybackSnapshot(
            positionMs = current.currentPosition,
            durationMs = current.duration,
            bufferedPositionMs = current.bufferedPosition,
            cachedPositionMs = 0L,
            isPlaying = current.isPlaying,
            isBuffering = current.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun buildMenuState(): TvPlayerMenuState {
        val selectedQuality = getSelectedQualityLabel()
        val options = buildList {
            add(TvQualityOption(AUTO_QUALITY_ID, "Авто", selectedQuality == "Авто"))
            getQualityOptions().forEach {
                add(TvQualityOption(it.id, it.label, it.label == selectedQuality))
            }
        }
        return TvPlayerMenuState(
            qualityOptions = options,
            selectedQualityTitle = selectedQuality,
            selectedSpeed = currentSpeed,
            settings = TvPlayerSettingsState(
                backBufferSeconds = BACK_BUFFER_SECONDS,
                forwardBufferSeconds = FORWARD_BUFFER_SECONDS,
                bufferMemoryLimitMb = BUFFER_MEMORY_LIMIT_MB,
            ),
        )
    }

    private fun buildStatsState(): TvPlayerStatsState {
        val current = player
        val videoFormat = current?.videoFormat
        val audioFormat = current?.audioFormat
        val bufferLabel = current?.let {
            "${formatTime(it.bufferedPosition)} (${it.bufferedPercentage}%)"
        } ?: "Нет данных"
        return TvPlayerStatsState(
            playbackStateLabel = playbackStateName(lastPlaybackState),
            bitrateLabel = formatBitrate(videoFormat?.bitrate ?: -1),
            videoSizeLabel = formatVideoSize(videoFormat),
            fpsLabel = formatFps(videoFormat),
            codecLabel = videoFormat?.let { firstNonEmpty(it.sampleMimeType, it.codecs, "unknown") }
                ?: audioFormat?.let { firstNonEmpty(it.sampleMimeType, it.codecs, "unknown") }
                ?: "Нет данных",
            bufferLabel = bufferLabel,
            sourceDiagnostics = buildSourceDiagnostics(lastIntent),
        )
    }

    protected open fun resolveMediaUri(intent: Intent?): Uri? = intent?.data

    protected open fun resolveMediaMimeType(intent: Intent?): String? = intent?.type

    protected open fun resolveHttpHeaders(intent: Intent?): Map<String, String> = extractHttpHeaders(intent)

    protected open fun resolveTitle(intent: Intent?): String? {
        if (intent == null) return null
        return intent.getStringExtra(Intent.EXTRA_TITLE) ?: intent.getStringExtra("title")
    }

    protected open fun buildSourceDiagnostics(intent: Intent?): String = buildIntentDiagnostics(intent)

    private fun getQualityOptions(): List<QualityOption> {
        val current = player ?: return emptyList()
        val result = mutableListOf<QualityOption>()
        current.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                result += QualityOption(
                    id = "${group.mediaTrackGroup.hashCode()}-$index-${format.height}-${format.bitrate}",
                    label = formatQuality(format),
                    override = TrackSelectionOverride(group.mediaTrackGroup, index),
                )
            }
        }
        return result
    }

    private fun getSelectedQualityLabel(): String {
        val current = player ?: return ""
        current.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) {
                    return formatQuality(group.getTrackFormat(index))
                }
            }
        }
        return "Авто"
    }

    private fun extractHttpHeaders(intent: Intent?): Map<String, String> {
        val extras = intent?.extras ?: return emptyMap()
        val headers = linkedMapOf<String, String>()
        collectHeaderBundle(headers, extras.getBundle("headers"))
        collectHeaderBundle(headers, extras.getBundle("http_headers"))
        collectHeaderBundle(headers, extras.getBundle("android.media.intent.extra.HTTP_HEADERS"))
        collectHeaderMap(headers, safeGet(extras, "headers"))
        collectHeaderMap(headers, safeGet(extras, "http_headers"))
        collectHeaderMap(headers, safeGet(extras, "android.media.intent.extra.HTTP_HEADERS"))
        putFirstStringExtra(headers, "User-Agent", extras, USER_AGENT_KEYS)
        putFirstStringExtra(headers, "Referer", extras, REFERER_KEYS)
        putFirstStringExtra(headers, "Cookie", extras, arrayOf("Cookie", "cookie", "cookies"))
        return headers
    }

    private fun collectHeaderBundle(headers: MutableMap<String, String>, bundle: Bundle?) {
        bundle ?: return
        bundle.keySet().forEach { key ->
            safeGet(bundle, key)?.let { headers[key] = it.toString() }
        }
    }

    private fun collectHeaderMap(headers: MutableMap<String, String>, value: Any?) {
        if (value !is Map<*, *>) return
        value.forEach { (key, entryValue) ->
            if (key != null && entryValue != null) {
                headers[key.toString()] = entryValue.toString()
            }
        }
    }

    private fun putFirstStringExtra(
        headers: MutableMap<String, String>,
        headerName: String,
        extras: Bundle,
        keys: Array<String>,
    ) {
        keys.forEach { key ->
            val value = safeGet(extras, key) as? String
            if (!value.isNullOrBlank()) {
                headers[headerName] = value
                return
            }
        }
    }

    private fun buildIntentDiagnostics(intent: Intent?): String {
        val out = StringBuilder()
        out.append("Intent\n")
        if (intent == null) {
            out.append("  null\n")
            return out.toString()
        }
        appendLine(out, "action", intent.action)
        appendLine(out, "type", intent.type)
        appendLine(out, "data", intent.dataString)
        appendLine(out, "scheme", intent.scheme)
        appendLine(out, "package", intent.`package`)
        appendLine(out, "component", intent.component)
        appendLine(out, "flags", "0x" + Integer.toHexString(intent.flags))
        appendLine(out, "categories", intent.categories)
        appendLine(out, "sourceBounds", intent.sourceBounds)

        intent.data?.let { data ->
            out.append("\nUri\n")
            appendLine(out, "scheme", data.scheme)
            appendLine(out, "host", data.host)
            appendLine(out, "port", data.port)
            appendLine(out, "path", data.path)
            appendLine(out, "lastPathSegment", data.lastPathSegment)
            appendLine(out, "query", data.query)
            appendLine(out, "fragment", data.fragment)
        }

        out.append("\nResolved HTTP headers\n")
        val headers = extractHttpHeaders(intent)
        if (headers.isEmpty()) {
            out.append("  none\n")
        } else {
            headers.forEach { (key, value) -> appendLine(out, key, value) }
        }

        out.append("\nExtras\n")
        val extras = intent.extras
        if (extras == null || extras.isEmpty) {
            out.append("  none\n")
        } else {
            extras.keySet().forEach { key -> appendLine(out, key, formatValue(safeGet(extras, key))) }
        }

        out.append("\nClipData\n")
        val clipData = intent.clipData
        if (clipData == null) {
            out.append("  none\n")
        } else {
            appendLine(out, "description", clipData.description)
            for (index in 0 until clipData.itemCount) {
                val item = clipData.getItemAt(index)
                appendLine(out, "item[$index].uri", item.uri)
                appendLine(out, "item[$index].text", item.text)
                appendLine(out, "item[$index].htmlText", item.htmlText)
                appendLine(out, "item[$index].intent", item.intent)
            }
        }
        return out.toString()
    }

    private fun appendLine(out: StringBuilder, key: String, value: Any?) {
        out.append("  ").append(key).append(": ").append(value ?: "null").append('\n')
    }

    private fun formatValue(value: Any?): String {
        if (value == null) return "null"
        if (value is Bundle) {
            return value.keySet().joinToString(prefix = "Bundle{", postfix = "}") { key ->
                "$key=${safeGet(value, key)}"
            }
        }
        return "${value::class.java.name} = $value"
    }

    private fun safeGet(bundle: Bundle, key: String): Any? {
        return try {
            bundle.get(key)
        } catch (error: Throwable) {
            "<unreadable ${error::class.java.simpleName}: ${error.message}>"
        }
    }

    private fun playbackStateName(state: Int): String {
        return when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> state.toString()
        }
    }

    private fun formatQuality(format: Format): String {
        val base = when {
            format.height > 0 -> "${format.height}p"
            format.width > 0 -> "${format.width}w"
            else -> "video"
        }
        val bitrate = format.bitrate.takeIf { it > 0 }?.let { "  ${it / 1000} kbps" }.orEmpty()
        val fps = format.frameRate.takeIf { it > 0 }?.let { "  ${"%.0f".format(Locale.US, it)} fps" }.orEmpty()
        return base + bitrate + fps
    }

    private fun formatBitrate(bitrate: Int): String {
        return if (bitrate <= 0) "Нет данных" else "${bitrate / 1000} kbps"
    }

    private fun formatVideoSize(format: Format?): String {
        return if (format == null || format.width <= 0 || format.height <= 0) {
            "Нет данных"
        } else {
            "${format.width}x${format.height}"
        }
    }

    private fun formatFps(format: Format?): String {
        return if (format == null || format.frameRate <= 0) {
            "Нет данных"
        } else {
            "%.0f".format(Locale.US, format.frameRate)
        }
    }

    private fun firstNonEmpty(first: String?, second: String?, fallback: String): String {
        return when {
            !first.isNullOrBlank() -> first
            !second.isNullOrBlank() -> second
            else -> fallback
        }
    }

    private fun formatTime(timeMs: Long): String {
        if (timeMs == C.TIME_UNSET || timeMs < 0) return "--:--"
        val totalSeconds = timeMs / 1000L
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        } else {
            "%02d:%02d".format(Locale.US, minutes, seconds)
        }
    }

    private data class QualityOption(
        val id: String,
        val label: String,
        val override: TrackSelectionOverride,
    )

    private companion object {
        private const val FORWARD_BUFFER_SECONDS = 50
        private const val BACK_BUFFER_SECONDS = 0
        private const val BUFFER_MEMORY_LIMIT_MB = 128
        private const val SEEK_STEP_MS = 10_000L
        private const val STATS_REFRESH_MS = 1_000L
        private const val AUTO_QUALITY_ID = "auto"

        private val USER_AGENT_KEYS = arrayOf(
            "User-Agent",
            "user-agent",
            "user_agent",
            "http.user_agent",
            "android.media.intent.extra.USER_AGENT",
        )

        private val REFERER_KEYS = arrayOf(
            "Referer",
            "referer",
            "referrer",
            "http.referer",
            "android.intent.extra.REFERRER_NAME",
        )
    }
}
