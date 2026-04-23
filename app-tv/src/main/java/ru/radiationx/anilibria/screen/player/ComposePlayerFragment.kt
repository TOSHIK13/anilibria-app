package ru.radiationx.anilibria.screen.player

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.widget.ProgressBar
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.R
import ru.radiationx.data.datasource.holders.PreferencesHolder
import ru.radiationx.data.entity.common.PlayerQuality
import ru.radiationx.data.entity.domain.release.PlayerSkips
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.player.EpisodePlaybackRules
import ru.radiationx.data.player.PlayerDataSourceProvider
import ru.radiationx.quill.get
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.getExtra
import ru.radiationx.shared.ktx.android.getExtraNotNull
import ru.radiationx.shared.ktx.android.putExtra
import ru.radiationx.shared.ktx.android.subscribeTo
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ComposePlayerFragment : Fragment(), PlayerMotionHandler {

    companion object {
        private const val TAG = "PlayerFlow"

        private const val ARG_RELEASE_ID = "release id"
        private const val ARG_EPISODE_ID = "episode id"
        private const val SEEK_STEP_MS = 10_000L
        private const val PLAY_PAUSE_HUD_DURATION_MS = 1_000L
        private const val SEEK_HUD_DURATION_MS = 2_000L
        private const val PROGRESS_TICK_MS = 250L
        private const val SKIP_TIMER_SEC = 5
        private const val NEXT_EPISODE_TIMER_SEC = 3
        private const val SKIP_EDGE_TOLERANCE_MS = 500L

        fun newInstance(
            releaseId: ReleaseId,
            episodeId: EpisodeId?,
        ): ComposePlayerFragment = ComposePlayerFragment().putExtra {
            putParcelable(ARG_RELEASE_ID, releaseId)
            putParcelable(ARG_EPISODE_ID, episodeId)
        }
    }

    private val viewModel by viewModel<PlayerViewModel> {
        PlayerExtra(
            releaseId = getExtraNotNull(ARG_RELEASE_ID),
            episodeId = getExtra(ARG_EPISODE_ID)
        )
    }

    private val playerHolder by lazy {
        ComposePlayerHolder(
            dataSourceProvider = get<PlayerDataSourceProvider>(),
            preferencesHolder = preferencesHolder,
        )
    }

    private val preferencesHolder by lazy {
        get<PreferencesHolder>()
    }

    private val player by lazy {
        playerHolder.attach(requireContext())
    }

    private var currentVideo by mutableStateOf<Video?>(null)
    private var controlsVisible by mutableStateOf(false)
    private var playbackSnapshot by mutableStateOf(ComposePlaybackSnapshot())
    private var playPauseHud by mutableStateOf<PlayPauseHudState?>(null)
    private var seekHud by mutableStateOf<SeekHudState?>(null)
    private var skipHud by mutableStateOf<SkipHudState?>(null)
    private var skipActionSelection by mutableStateOf(SkipOverlayAction.Skip)
    private var activeSubmenu by mutableStateOf<PlayerSubmenuType?>(null)
    private var statsOverlayVisible by mutableStateOf(false)
    private var completionOverlay by mutableStateOf<PlayerCompletionOverlay?>(null)
    private var completionActionSelection by mutableStateOf(CompletionOverlayAction.Next)
    private var completionProgress by mutableStateOf(0f)
    private var completionRemainingSec by mutableStateOf<Int?>(null)
    private var submenuScrollCommand by mutableStateOf(SubmenuScrollCommand())
    private var playerStats by mutableStateOf(PlayerStatsState())
    private var currentVideoFormat: Format? = null
    private var currentAudioFormat: Format? = null
    private var estimatedBitrate by mutableStateOf<Long?>(null)
    private var droppedFramesCount by mutableStateOf(0)
    private var rebufferCount by mutableStateOf(0)
    private var lastPlaybackState by mutableStateOf(Player.STATE_IDLE)

    private var progressJob: Job? = null
    private var playPauseHudJob: Job? = null
    private var seekHudJob: Job? = null
    private var skipTimerJob: Job? = null
    private var completionTimerJob: Job? = null
    private var touchGestureDetector: GestureDetector? = null
    private var touchSeekAccumulatorPx = 0f
    private var genericMotionAccumulator = 0f
    private var suppressedSkip by mutableStateOf<SuppressedSkipState?>(null)
    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING && lastPlaybackState != Player.STATE_IDLE) {
                rebufferCount += 1
            }
            val previousPlaybackState = lastPlaybackState
            lastPlaybackState = playbackState
            updatePlaybackSnapshot()
            when (playbackState) {
                Player.STATE_ENDED -> {
                    notifyEpisodeCompleted(playbackSnapshot, "state_ended")
                }

                Player.STATE_READY -> {
                    val snapshot = playbackSnapshot
                    viewModel.onPrepare(snapshot.durationMs)
                }

                Player.STATE_IDLE -> {
                    val snapshot = playbackSnapshot
                    if (previousPlaybackState != Player.STATE_IDLE && isPlaybackFinished(snapshot)) {
                        notifyEpisodeCompleted(snapshot, "state_idle")
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlaybackSnapshot()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            updatePlaybackSnapshot()
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                val snapshot = playbackSnapshot
                viewModel.onSeek(snapshot.positionMs, snapshot.durationMs)
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            droppedFramesCount += droppedFrames
            updatePlayerStats()
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            estimatedBitrate = bitrateEstimate.takeIf { it > 0 }
            updatePlayerStats()
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            currentVideoFormat = format
            updatePlayerStats()
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            currentAudioFormat = format
            updatePlayerStats()
        }
    }

    @UnstableApi
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    ComposePlayerScreen()
                }
            }
        }
    }

    @UnstableApi
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
        initializeTouchpadControls(view)
        startProgressUpdates()

        subscribeTo(viewModel.videoData.filterNotNull()) {
            currentVideo = it
            skipHud = null
            skipTimerJob?.cancel()
            suppressedSkip = null
            preparePlayer(it.url, it.seek)
        }

        subscribeTo(viewModel.playAction.filterNotNull()) {
            if (it) {
                player.play()
            } else {
                player.pause()
            }
            updatePlaybackSnapshot()
        }

        subscribeTo(viewModel.speedState.filterNotNull()) {
            player.playbackParameters = PlaybackParameters(it)
        }

        subscribeTo(viewModel.settingsOverlayVisible) {
            if (it) {
                controlsVisible = true
            }
        }

        subscribeTo(viewModel.completionOverlay) {
            completionOverlay = it
            if (it != null) {
                controlsVisible = false
                activeSubmenu = null
                completionActionSelection = CompletionOverlayAction.Next
                completionProgress = 0f
                completionRemainingSec = NEXT_EPISODE_TIMER_SEC
                startCompletionTimer(it)
            } else {
                completionTimerJob?.cancel()
                completionProgress = 0f
                completionRemainingSec = null
            }
        }
    }

    override fun onPause() {
        super.onPause()
        player.pause()
        val snapshot = playbackSnapshot
        viewModel.onPauseClick(snapshot.positionMs, snapshot.durationMs)
    }

    override fun onStop() {
        super.onStop()
        val snapshot = playbackSnapshot
        viewModel.onStopClick(snapshot.positionMs, snapshot.durationMs)
    }

    override fun onDestroyView() {
        progressJob?.cancel()
        playPauseHudJob?.cancel()
        seekHudJob?.cancel()
        skipTimerJob?.cancel()
        completionTimerJob?.cancel()
        touchGestureDetector = null
        touchSeekAccumulatorPx = 0f
        genericMotionAccumulator = 0f
        player.removeListener(playerListener)
        player.removeAnalyticsListener(analyticsListener)
        playerHolder.detach()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroyView()
    }

    override fun handleTouchpadEvent(event: MotionEvent): Boolean {
        if (completionOverlay != null) {
            return false
        }
        if (activeSubmenu != null) {
            if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                val verticalScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    .takeIf { it != 0f }
                    ?: event.getAxisValue(MotionEvent.AXIS_SCROLL)
                if (verticalScroll != 0f) {
                    submenuScrollCommand = SubmenuScrollCommand(
                        token = submenuScrollCommand.token + 1,
                        deltaPx = -verticalScroll * 180f,
                    )
                    return true
                }
            }
            return false
        }
        if (controlsVisible) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchSeekAccumulatorPx = 0f
                return touchGestureDetector?.onTouchEvent(event) == true
            }

            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val handled = touchGestureDetector?.onTouchEvent(event) == true
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    touchSeekAccumulatorPx = 0f
                }
                return handled
            }

            MotionEvent.ACTION_BUTTON_PRESS -> {
                if (event.buttonState and MotionEvent.BUTTON_PRIMARY != 0) {
                    toggleControls()
                    return true
                }
            }

            MotionEvent.ACTION_SCROLL -> {
                val horizontalScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (horizontalScroll == 0f) {
                    return false
                }
                genericMotionAccumulator += horizontalScroll
                while (genericMotionAccumulator >= 1f) {
                    performSeekRelative(-SEEK_STEP_MS, showHud = !controlsVisible)
                    genericMotionAccumulator -= 1f
                }
                while (genericMotionAccumulator <= -1f) {
                    performSeekRelative(SEEK_STEP_MS, showHud = !controlsVisible)
                    genericMotionAccumulator += 1f
                }
                return true
            }
        }
        return false
    }

    override fun shouldBlockIdleDim(): Boolean {
        val snapshot = playbackSnapshot
        return snapshot.isPlaying || snapshot.isBuffering
    }

    @Composable
    private fun ComposePlayerScreen() {
        val controlsState by viewModel.controlsState.collectAsState()
        val qualityState by viewModel.qualityState.collectAsState()
        val composeMenuState by viewModel.composeMenuState.collectAsState()

        val rootFocusRequester = remember { FocusRequester() }
        val primaryFocusRequester = remember { FocusRequester() }
        val timelineFocusRequester = remember { FocusRequester() }
        val qualityFocusRequester = remember { FocusRequester() }
        val speedFocusRequester = remember { FocusRequester() }
        val episodesFocusRequester = remember { FocusRequester() }
        val settingsFocusRequester = remember { FocusRequester() }
        val statsFocusRequester = remember { FocusRequester() }
        var currentZone by remember { mutableStateOf(OverlayZone.ROOT) }
        var restoreMenuFocus by remember { mutableStateOf(false) }
        val currentTimeText = rememberCurrentTimeText()

        val closeSubmenu = {
            val submenu = activeSubmenu
            activeSubmenu = null
            restoreMenuFocus = true
            when (submenu) {
                PlayerSubmenuType.QUALITY -> qualityFocusRequester.requestFocusSafely()
                PlayerSubmenuType.SPEED -> speedFocusRequester.requestFocusSafely()
                PlayerSubmenuType.EPISODES -> episodesFocusRequester.requestFocusSafely()
                PlayerSubmenuType.SETTINGS -> settingsFocusRequester.requestFocusSafely()
                PlayerSubmenuType.STATS -> statsFocusRequester.requestFocusSafely()
                null -> Unit
            }
        }

        val hideOverlay = {
            activeSubmenu = null
            controlsVisible = false
            currentZone = OverlayZone.ROOT
            restoreMenuFocus = false
        }

        LaunchedEffect(controlsVisible, completionOverlay) {
            if (completionOverlay != null) {
                return@LaunchedEffect
            }
            if (!controlsVisible) {
                restoreMenuFocus = false
                rootFocusRequester.requestFocusSafely()
            } else if (activeSubmenu == null && !restoreMenuFocus) {
                primaryFocusRequester.requestFocusSafely()
            }
        }

        LaunchedEffect(activeSubmenu) {
            if (activeSubmenu != null) {
                restoreMenuFocus = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocusRequester)
                .focusable()
                .onFocusChanged {
                    if (it.isFocused && !controlsVisible) {
                        currentZone = OverlayZone.ROOT
                    }
                }
                .onPreviewKeyEvent { event ->
                    handlePreviewKeyEvent(
                        event = event,
                        currentZone = currentZone,
                        submenuOpen = activeSubmenu != null,
                        completionOverlayVisible = completionOverlay != null,
                        showOverlay = {
                            controlsVisible = true
                        },
                        hideOverlay = hideOverlay,
                        closeSubmenu = closeSubmenu,
                        focusTimeline = { timelineFocusRequester.requestFocusSafely() },
                        focusPrimary = { primaryFocusRequester.requestFocusSafely() },
                        focusSecondary = { qualityFocusRequester.requestFocusSafely() },
                    )
                }
                .pointerInput(completionOverlay, controlsVisible) {
                    detectTapGestures {
                        if (completionOverlay == null && !controlsVisible) {
                            controlsVisible = true
                        }
                    }
                }
        ) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(AndroidColor.BLACK)
                        setBackgroundColor(AndroidColor.BLACK)
                        isFocusable = false
                        isClickable = false
                        player = this@ComposePlayerFragment.player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize()
            )

            if (statsOverlayVisible) {
                PlayerStatsOverlay(
                    stats = playerStats,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 32.dp, end = 32.dp)
                )
            }

            PlaybackHudOverlay(
                playPauseHud = playPauseHud,
                seekHud = seekHud,
            )

            val showLoadingIndicator =
                currentVideo != null && (
                    playbackSnapshot.isBuffering ||
                        lastPlaybackState == Player.STATE_IDLE ||
                        (playbackSnapshot.durationMs <= 0L && !playbackSnapshot.isPlaying)
                    )
            if (showLoadingIndicator) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xAA101010))
                            .padding(20.dp)
                    ) {
                        AndroidView(
                            factory = { context ->
                                ProgressBar(context).apply {
                                    isIndeterminate = true
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }

            SkipFloatingOverlay(
                skipHud = skipHud,
                selectedAction = skipActionSelection,
                onSkip = { skipCurrentSegment() },
                onWatch = { dismissCurrentSkip() },
            )

            CompletionOverlay(
                overlay = completionOverlay,
                selectedAction = completionActionSelection,
                progress = completionProgress,
                remainingSec = completionRemainingSec,
                onNextClick = { executeCompletionNext() },
                onCloseClick = { executeCompletionClose() },
            )

            AnimatedVisibility(
                visible = controlsVisible && completionOverlay == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x80000000))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (activeSubmenu != null) {
                                closeSubmenu()
                            } else {
                                hideOverlay()
                            }
                        }
                ) {
                    AnimatedVisibility(
                        visible = true,
                        enter = slideInVertically(initialOffsetY = { it / 3 }),
                        exit = slideOutVertically(targetOffsetY = { it / 3 }),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .background(Color(0xCC121212))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {}
                            .padding(horizontal = 28.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = controlsState.title.ifBlank { currentVideo?.title.orEmpty() },
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = controlsState.subtitle.ifBlank { currentVideo?.subtitle.orEmpty() },
                                    fontSize = 15.sp,
                                    color = Color(0xFFBDBDBD),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = currentTimeText,
                                color = Color(0xFFE0E0E0),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 20.dp, top = 4.dp)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged {
                                    if (it.hasFocus) {
                                        currentZone = OverlayZone.PRIMARY
                                    }
                                },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            PlayerActionButton(
                                label = "Предыдущая",
                                iconRes = R.drawable.ic_player_previous,
                                enabled = controlsState.hasPrevious,
                                compact = false,
                                onClick = { playPrevious() }
                            )
                            PlayerActionButton(
                                label = if (playbackSnapshot.isPlaying) "Пауза" else "Пуск",
                                iconRes = if (playbackSnapshot.isPlaying) {
                                    R.drawable.ic_player_pause
                                } else {
                                    R.drawable.ic_player_play
                                },
                                modifier = Modifier.focusRequester(primaryFocusRequester),
                                compact = false,
                                onClick = { togglePlayPause(showHud = false) }
                            )
                            PlayerActionButton(
                                label = "Следующая",
                                iconRes = R.drawable.ic_player_next,
                                enabled = controlsState.hasNext,
                                compact = false,
                                onClick = { playNext() }
                            )
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 6.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                TimelineMeta(
                                    modifier = Modifier.align(Alignment.Bottom),
                                    snapshot = playbackSnapshot,
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TimelineControl(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(timelineFocusRequester)
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            currentZone = OverlayZone.TIMELINE
                                        }
                                    },
                                snapshot = playbackSnapshot,
                                skips = currentVideo?.skips,
                                onSeekTo = { target ->
                                    player.seekTo(target)
                                    updatePlaybackSnapshot()
                                },
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged {
                                    if (it.hasFocus) {
                                        currentZone = OverlayZone.SECONDARY
                                    }
                                },
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            PlayerActionButton(
                                label = "Качество",
                                iconRes = qualityState.toQualityIconRes(),
                                modifier = Modifier.focusRequester(qualityFocusRequester),
                                selected = activeSubmenu == PlayerSubmenuType.QUALITY,
                                onClick = { activeSubmenu = PlayerSubmenuType.QUALITY }
                            )
                            PlayerActionButton(
                                label = "Скорость",
                                iconRes = R.drawable.ic_play_speed,
                                modifier = Modifier.focusRequester(speedFocusRequester),
                                selected = activeSubmenu == PlayerSubmenuType.SPEED,
                                onClick = { activeSubmenu = PlayerSubmenuType.SPEED }
                            )
                            PlayerActionButton(
                                label = "Эпизоды",
                                iconRes = R.drawable.ic_playlist_play_black_24dp,
                                modifier = Modifier.focusRequester(episodesFocusRequester),
                                selected = activeSubmenu == PlayerSubmenuType.EPISODES,
                                onClick = { activeSubmenu = PlayerSubmenuType.EPISODES }
                            )
                            PlayerActionButton(
                                label = "Настройки",
                                iconRes = R.drawable.ic_settings_24,
                                modifier = Modifier.focusRequester(settingsFocusRequester),
                                selected = activeSubmenu == PlayerSubmenuType.SETTINGS,
                                onClick = { activeSubmenu = PlayerSubmenuType.SETTINGS }
                            )
                            PlayerActionButton(
                                label = "Статистика",
                                iconRes = R.drawable.ic_alert_circle_outline,
                                modifier = Modifier.focusRequester(statsFocusRequester),
                                selected = statsOverlayVisible,
                                onClick = { statsOverlayVisible = !statsOverlayVisible }
                            )
                        }
                        }
                        }
                    }
                    activeSubmenu?.takeIf { it != PlayerSubmenuType.STATS }?.let { submenu ->
                        PlayerSubmenuSheet(
                            submenu = submenu,
                            state = composeMenuState,
                            stats = playerStats,
                            scrollCommand = submenuScrollCommand,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 92.dp, end = 28.dp),
                            onQualitySelected = { quality ->
                                viewModel.applyQuality(quality)
                                closeSubmenu()
                            },
                            onSpeedSelected = { speed ->
                                viewModel.applySpeed(speed)
                                closeSubmenu()
                            },
                            onEpisodeSelected = { episodeId ->
                                val snapshot = playbackSnapshot
                                viewModel.applyEpisode(episodeId, snapshot.positionMs, snapshot.durationMs)
                                closeSubmenu()
                            },
                            onSetSkipsEnabled = viewModel::setSkipsEnabled,
                            onSetAutoSkipEnabled = viewModel::setAutoSkipEnabled,
                            onSetAutoplayEnabled = viewModel::setAutoplayEnabled,
                        )
                    }
                }
            }
    }

    private fun handlePreviewKeyEvent(
        event: androidx.compose.ui.input.key.KeyEvent,
        currentZone: OverlayZone,
        submenuOpen: Boolean,
        completionOverlayVisible: Boolean,
        showOverlay: () -> Unit,
        hideOverlay: () -> Unit,
        closeSubmenu: () -> Unit,
        focusTimeline: () -> Unit,
        focusPrimary: () -> Unit,
        focusSecondary: () -> Unit,
    ): Boolean {
        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        val keyCode = event.nativeKeyEvent.keyCode
        return if (completionOverlayVisible) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    completionActionSelection = CompletionOverlayAction.Next
                    true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    completionActionSelection = CompletionOverlayAction.Close
                    true
                }

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    when (completionActionSelection) {
                        CompletionOverlayAction.Next -> executeCompletionNext()
                        CompletionOverlayAction.Close -> executeCompletionClose()
                    }
                    true
                }

                KeyEvent.KEYCODE_BACK -> {
                    executeCompletionClose()
                    true
                }

                else -> false
            }
        } else if (!controlsVisible) {
            if (skipHud != null) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        skipActionSelection = SkipOverlayAction.Skip
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        skipActionSelection = SkipOverlayAction.Watch
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        when (skipActionSelection) {
                            SkipOverlayAction.Skip -> skipCurrentSegment()
                            SkipOverlayAction.Watch -> dismissCurrentSkip()
                        }
                        return true
                    }
                }
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlayPause(showHud = true)
                    true
                }

                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    performSeekRelative(-SEEK_STEP_MS, showHud = true)
                    true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    performSeekRelative(SEEK_STEP_MS, showHud = true)
                    true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showOverlay()
                    true
                }

                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    playNext()
                    true
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    playPrevious()
                    true
                }

                else -> false
            }
        } else if (submenuOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    closeSubmenu()
                    true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlayPause(showHud = false)
                    true
                }

                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    playNext()
                    true
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    playPrevious()
                    true
                }

                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    performSeekRelative(SEEK_STEP_MS, showHud = false)
                    true
                }

                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    performSeekRelative(-SEEK_STEP_MS, showHud = false)
                    true
                }

                else -> false
            }
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    hideOverlay()
                    true
                }

                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlayPause(showHud = false)
                    true
                }

                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    playNext()
                    true
                }

                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    playPrevious()
                    true
                }

                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    performSeekRelative(SEEK_STEP_MS, showHud = false)
                    true
                }

                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    performSeekRelative(-SEEK_STEP_MS, showHud = false)
                    true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    when (currentZone) {
                        OverlayZone.PRIMARY -> {
                            hideOverlay()
                            true
                        }

                        OverlayZone.TIMELINE -> {
                            focusPrimary()
                            true
                        }

                        OverlayZone.SECONDARY -> {
                            focusTimeline()
                            true
                        }

                        else -> false
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    when (currentZone) {
                        OverlayZone.PRIMARY -> {
                            focusTimeline()
                            true
                        }

                        OverlayZone.TIMELINE -> {
                            focusSecondary()
                            true
                        }

                        else -> false
                    }
                }

                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (currentZone == OverlayZone.TIMELINE) {
                        val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            SEEK_STEP_MS
                        } else {
                            -SEEK_STEP_MS
                        }
                        performSeekRelative(delta, showHud = false)
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    private fun playNext() {
        val snapshot = playbackSnapshot
        viewModel.onNextClick(snapshot.positionMs, snapshot.durationMs)
    }

    private fun playPrevious() {
        val snapshot = playbackSnapshot
        viewModel.onPrevClick(snapshot.positionMs, snapshot.durationMs)
    }

    private fun togglePlayPause(showHud: Boolean) {
        if (player.isPlaying) {
            player.pause()
            if (showHud) {
                showPlayPauseHud(isPlaying = false)
            }
        } else {
            player.play()
            if (showHud) {
                showPlayPauseHud(isPlaying = true)
            }
        }
        updatePlaybackSnapshot()
    }

    private fun toggleControls() {
        if (completionOverlay != null) {
            return
        }
        controlsVisible = !controlsVisible
        if (!controlsVisible) {
            activeSubmenu = null
        }
        updateSkipHud()
    }

    private fun startCompletionTimer(overlay: PlayerCompletionOverlay) {
        completionTimerJob?.cancel()
        if (!overlay.autoAdvanceEnabled || overlay.type != PlayerCompletionOverlayType.END_EPISODE) {
            completionProgress = 0f
            completionRemainingSec = null
            return
        }
        completionTimerJob = viewLifecycleOwner.lifecycleScope.launch {
            val totalDurationMs = NEXT_EPISODE_TIMER_SEC * 1_000f
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                completionProgress = (elapsed / totalDurationMs).coerceIn(0f, 1f)
                val remainingSec = ((totalDurationMs - elapsed).coerceAtLeast(0f) / 1000f)
                completionRemainingSec = kotlin.math.ceil(remainingSec).toInt().coerceAtLeast(0)
                if (completionOverlay == null || completionOverlay?.type != PlayerCompletionOverlayType.END_EPISODE) {
                    return@launch
                }
                if (completionProgress >= 1f) {
                    executeCompletionNext()
                    return@launch
                }
                delay(50L)
            }
        }
    }

    private fun executeCompletionNext() {
        completionTimerJob?.cancel()
        completionProgress = 0f
        completionRemainingSec = null
        when (completionOverlay?.type) {
            PlayerCompletionOverlayType.END_EPISODE -> viewModel.playNextEpisodeFromOverlay()
            PlayerCompletionOverlayType.END_SEASON,
            null -> viewModel.closePlayerFromOverlay()
        }
    }

    private fun executeCompletionClose() {
        completionTimerJob?.cancel()
        completionProgress = 0f
        completionRemainingSec = null
        viewModel.closePlayerFromOverlay()
    }

    private fun performSeekRelative(deltaMs: Long, showHud: Boolean) {
        val duration = player.duration.takeIf { it > 0 } ?: playbackSnapshot.durationMs
        val basePosition = seekHud
            ?.takeIf { it.direction.deltaMs.sign == deltaMs.sign }
            ?.targetPositionMs
            ?: player.currentPosition
        val maxDuration = duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val targetPosition = (basePosition + deltaMs).coerceIn(0L, maxDuration)

        player.seekTo(targetPosition)
        updatePlaybackSnapshot()

        if (showHud) {
            showSeekHud(deltaMs, targetPosition)
        }
    }

    private fun showPlayPauseHud(isPlaying: Boolean) {
        playPauseHud = PlayPauseHudState(isPlaying)
        playPauseHudJob?.cancel()
        playPauseHudJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(PLAY_PAUSE_HUD_DURATION_MS)
            playPauseHud = null
        }
    }

    private fun showSeekHud(deltaMs: Long, targetPositionMs: Long) {
        val direction = if (deltaMs >= 0) SeekDirection.Forward else SeekDirection.Backward
        val previous = seekHud
        val accumulatedDelta = if (previous != null && previous.direction == direction) {
            previous.totalDeltaMs + deltaMs
        } else {
            deltaMs
        }

        seekHud = SeekHudState(
            direction = direction,
            totalDeltaMs = accumulatedDelta,
            targetPositionMs = targetPositionMs,
        )
        seekHudJob?.cancel()
        seekHudJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(SEEK_HUD_DURATION_MS)
            seekHud = null
        }
    }

    private fun skipCurrentSegment() {
        val current = skipHud ?: return
        skipTimerJob?.cancel()
        suppressedSkip = SuppressedSkipState(
            key = current.key,
            activationStartMs = activationStart(current.skip),
            activationEndMs = activationEnd(current.skip),
        )
        val targetPosition = current.skip.end.coerceAtLeast(0L)
        Log.d(TAG, "skip type=${current.type} target=$targetPosition position=${playbackSnapshot.positionMs} duration=${playbackSnapshot.durationMs}")
        player.seekTo(targetPosition)
        if (current.type == SkipHudType.Ending) {
            val duration = player.duration.takeIf { it > 0L } ?: playbackSnapshot.durationMs
            viewModel.onEndingSkipped(targetPosition, duration)
        }
        skipHud = null
        skipActionSelection = SkipOverlayAction.Skip
        updatePlaybackSnapshot()
    }

    private fun dismissCurrentSkip() {
        val current = skipHud ?: return
        skipTimerJob?.cancel()
        suppressedSkip = SuppressedSkipState(
            key = current.key,
            activationStartMs = activationStart(current.skip),
            activationEndMs = activationEnd(current.skip),
        )
        skipHud = null
        skipActionSelection = SkipOverlayAction.Skip
    }

    private fun updateSkipHud() {
        clearExpiredSuppressedSkip(playbackSnapshot.positionMs)
        val video = currentVideo ?: run {
            skipHud = null
            skipTimerJob?.cancel()
            return
        }
        if (!preferencesHolder.playerSkips.value) {
            skipHud = null
            skipTimerJob?.cancel()
            return
        }

        val activeSkip = findActiveSkip(video.skips, playbackSnapshot.positionMs) ?: run {
            skipHud = null
            skipTimerJob?.cancel()
            return
        }

        val current = skipHud
        if (current?.key == activeSkip.key) {
            return
        }

        Log.d(
            TAG,
            "skip-active type=${activeSkip.type} start=${activeSkip.skip.start} end=${activeSkip.skip.end} position=${playbackSnapshot.positionMs}"
        )
        skipHud = activeSkip
        skipActionSelection = SkipOverlayAction.Skip
        skipTimerJob?.cancel()
        if (preferencesHolder.playerSkipsTimer.value) {
            val totalDurationMs = SKIP_TIMER_SEC * 1_000f
            skipTimerJob = viewLifecycleOwner.lifecycleScope.launch {
                val startedAt = System.currentTimeMillis()
                while (true) {
                    val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                    val progress = (elapsed / totalDurationMs).coerceIn(0f, 1f)
                    val remainingSec = ((totalDurationMs - elapsed).coerceAtLeast(0f) / 1000f)
                    val roundedRemaining = kotlin.math.ceil(remainingSec).toInt().coerceAtLeast(0)
                    skipHud = activeSkip.copy(
                        progress = progress,
                        autoSkipInSec = roundedRemaining.takeIf { it > 0 }
                    )
                    if (skipHud?.key != activeSkip.key) {
                        return@launch
                    }
                    if (progress >= 1f) {
                        skipCurrentSegment()
                        return@launch
                    }
                    delay(50L)
                }
            }
        }
    }

    private fun findActiveSkip(
        skips: PlayerSkips?,
        positionMs: Long,
    ): SkipHudState? {
        val opening = skips?.opening?.takeIf { isSkipActive("opening", it, positionMs) }
        if (opening != null) {
            return SkipHudState(
                key = skipKey("opening", opening),
                title = "Пропустить опенинг",
                type = SkipHudType.Opening,
                skip = opening,
            )
        }
        val ending = skips?.ending?.takeIf { isSkipActive("ending", it, positionMs) }
        if (ending != null) {
            return SkipHudState(
                key = skipKey("ending", ending),
                title = "Пропустить эндинг",
                type = SkipHudType.Ending,
                skip = ending,
            )
        }
        return null
    }

    private fun isSkipActive(
        type: String,
        skip: PlayerSkips.Skip,
        positionMs: Long,
    ): Boolean {
        val key = skipKey(type, skip)
        val suppressed = suppressedSkip
        if (suppressed?.key == key && positionMs in suppressed.activationStartMs..suppressed.activationEndMs) {
            return false
        }
        val activationStart = activationStart(skip)
        val activationEnd = activationEnd(skip)
        return positionMs in activationStart..activationEnd
    }

    private fun skipKey(type: String, skip: PlayerSkips.Skip): String {
        return "$type:${skip.start}:${skip.end}"
    }

    private fun activationStart(skip: PlayerSkips.Skip): Long {
        return (skip.start - SKIP_EDGE_TOLERANCE_MS).coerceAtLeast(0L)
    }

    private fun activationEnd(skip: PlayerSkips.Skip): Long {
        return skip.end + SKIP_EDGE_TOLERANCE_MS
    }

    private fun clearExpiredSuppressedSkip(positionMs: Long) {
        val suppressed = suppressedSkip ?: return
        if (positionMs !in suppressed.activationStartMs..suppressed.activationEndMs) {
            suppressedSkip = null
        }
    }

    private fun preparePlayer(url: String, seek: Long) {
        playbackSnapshot = ComposePlaybackSnapshot(
            positionMs = seek.coerceAtLeast(0L),
            durationMs = 0L,
            bufferedPositionMs = 0L,
            isPlaying = false,
            isBuffering = true,
        )
        updatePlayerStats()
        updateSkipHud()
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)), false)
        player.prepare()
        player.seekTo(seek)
        updatePlaybackSnapshot()
    }

    private fun initializeTouchpadControls(view: View) {
        val seekTriggerDistancePx = ViewConfiguration.get(view.context).scaledTouchSlop * 8
        touchGestureDetector = GestureDetector(
            view.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean {
                    touchSeekAccumulatorPx = 0f
                    return true
                }

                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    toggleControls()
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (abs(distanceX) <= abs(distanceY)) {
                        return false
                    }
                    touchSeekAccumulatorPx += distanceX
                    while (touchSeekAccumulatorPx >= seekTriggerDistancePx) {
                        performSeekRelative(-SEEK_STEP_MS, showHud = !controlsVisible)
                        touchSeekAccumulatorPx -= seekTriggerDistancePx
                    }
                    while (touchSeekAccumulatorPx <= -seekTriggerDistancePx) {
                        performSeekRelative(SEEK_STEP_MS, showHud = !controlsVisible)
                        touchSeekAccumulatorPx += seekTriggerDistancePx
                    }
                    return true
                }
            }
        )
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                updatePlaybackSnapshot()
                val snapshot = playbackSnapshot
                viewModel.onPlaybackProgress(
                    position = snapshot.positionMs,
                    duration = snapshot.durationMs,
                    isPlaying = snapshot.isPlaying,
                )
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    private fun updatePlaybackSnapshot() {
        val knownDuration = playbackSnapshot.durationMs
        val currentDuration = player.duration.takeIf { it > 0 } ?: knownDuration
        val rawPosition = player.currentPosition.coerceAtLeast(0L)
        val normalizedPosition = EpisodePlaybackRules.clampPosition(rawPosition, currentDuration)
        val normalizedBuffer = player.bufferedPosition
            .coerceAtLeast(0L)
            .let { buffered -> if (currentDuration > 0L) buffered.coerceAtMost(currentDuration) else buffered }
        playbackSnapshot = ComposePlaybackSnapshot(
            positionMs = normalizedPosition,
            durationMs = currentDuration,
            bufferedPositionMs = normalizedBuffer,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
        )
        updatePlayerStats()
        updateSkipHud()
    }

    private fun notifyEpisodeCompleted(snapshot: ComposePlaybackSnapshot, source: String) {
        Log.d(
            TAG,
            "end-detected source=$source position=${snapshot.positionMs} duration=${snapshot.durationMs} state=${player.playbackState}"
        )
        viewModel.onComplete(snapshot.positionMs, snapshot.durationMs)
    }

    private fun isPlaybackFinished(snapshot: ComposePlaybackSnapshot): Boolean {
        return EpisodePlaybackRules.isAtEpisodeEnd(snapshot.positionMs, snapshot.durationMs)
    }

    private fun updatePlayerStats() {
        val snapshot = playbackSnapshot
        val videoFormat = currentVideoFormat ?: player.videoFormat
        val audioFormat = currentAudioFormat ?: player.audioFormat
        val runtime = Runtime.getRuntime()
        val usedMemoryBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val maxMemoryBytes = runtime.maxMemory().coerceAtLeast(0L)
        val availableMemoryBytes = (maxMemoryBytes - usedMemoryBytes).coerceAtLeast(0L)
        playerStats = PlayerStatsState(
            playbackStateLabel = player.playbackState.toPlaybackStateLabel(snapshot.isPlaying),
            bitrateLabel = formatBitrate(estimatedBitrate ?: videoFormat?.bitrate?.toLong()),
            videoSizeLabel = formatVideoSize(videoFormat),
            fpsLabel = formatFps(videoFormat),
            codecLabel = videoFormat?.sampleMimeType
                ?: videoFormat?.codecs
                ?: audioFormat?.sampleMimeType
                ?: "Нет данных",
            bufferLabel = formatBuffer(snapshot.bufferedPositionMs, snapshot.durationMs),
            memoryUsedLabel = formatMemory(usedMemoryBytes),
            memoryAvailableLabel = "${formatMemory(availableMemoryBytes)} / ${formatMemory(maxMemoryBytes)}",
            droppedFramesLabel = droppedFramesCount.toString(),
            rebufferCountLabel = rebufferCount.toString(),
        )
    }
}

private enum class OverlayZone {
    ROOT,
    PRIMARY,
    TIMELINE,
    SECONDARY,
}

private enum class SeekDirection(val iconRes: Int, val deltaMs: Long) {
    Backward(R.drawable.ic_player_previous, -1L),
    Forward(R.drawable.ic_player_next, 1L),
}

private enum class SkipOverlayAction {
    Skip,
    Watch,
}

private enum class CompletionOverlayAction {
    Next,
    Close,
}

private enum class SkipHudType {
    Opening,
    Ending,
}

private data class ComposePlaybackSnapshot(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
)

private data class SuppressedSkipState(
    val key: String,
    val activationStartMs: Long,
    val activationEndMs: Long,
)

private data class PlayPauseHudState(
    val isPlaying: Boolean,
)

private data class SeekHudState(
    val direction: SeekDirection,
    val totalDeltaMs: Long,
    val targetPositionMs: Long,
)

private data class SkipHudState(
    val key: String,
    val title: String,
    val type: SkipHudType,
    val skip: PlayerSkips.Skip,
    val progress: Float = 0f,
    val autoSkipInSec: Int? = null,
)

data class SubmenuScrollCommand(
    val token: Long = 0L,
    val deltaPx: Float = 0f,
)

@Composable
private fun PlaybackHudOverlay(
    playPauseHud: PlayPauseHudState?,
    seekHud: SeekHudState?,
) {
    var lastPlayPauseHud by remember { mutableStateOf<PlayPauseHudState?>(null) }
    LaunchedEffect(playPauseHud) {
        if (playPauseHud != null) {
            lastPlayPauseHud = playPauseHud
        }
    }
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        AnimatedVisibility(
            visible = playPauseHud != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val hudState = lastPlayPauseHud ?: return@AnimatedVisibility
            HudBadge(
                iconRes = if (hudState.isPlaying) {
                    R.drawable.ic_player_play
                } else {
                    R.drawable.ic_player_pause
                },
                iconOnly = true,
                contentDescription = if (hudState.isPlaying) "Пуск" else "Пауза",
            )
        }

        AnimatedVisibility(
            visible = seekHud != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(
                if (seekHud?.direction == SeekDirection.Backward) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                }
            )
        ) {
            seekHud?.let {
                Box(
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    HudBadge(
                        iconRes = it.direction.iconRes,
                        title = formatDelta(it.totalDeltaMs),
                        subtitle = formatDuration(it.targetPositionMs),
                    )
                }
            }
        }
    }
}

@Composable
private fun SkipFloatingOverlay(
    skipHud: SkipHudState?,
    selectedAction: SkipOverlayAction,
    onSkip: () -> Unit,
    onWatch: () -> Unit,
) {
    AnimatedVisibility(
        visible = skipHud != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        skipHud ?: return@AnimatedVisibility
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 24.dp, bottom = 112.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xD9111111))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(180.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1E1E1E))
                        .border(
                            width = if (selectedAction == SkipOverlayAction.Skip) 1.dp else 0.dp,
                            brush = SolidColor(Color(0x55FFFFFF)),
                            shape = RoundedCornerShape(16.dp),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onSkip,
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(skipHud.progress)
                            .background(Color(0xFFFE3635))
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Пропустить",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        skipHud.autoSkipInSec?.let {
                            Text(
                                text = "${it}с",
                                color = Color.White,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selectedAction == SkipOverlayAction.Watch) Color(0xFF2C2C2C) else Color(0xFF1A1A1A))
                        .border(
                            width = if (selectedAction == SkipOverlayAction.Watch) 1.dp else 0.dp,
                            brush = SolidColor(Color(0x55FFFFFF)),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onWatch,
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_player_play),
                        contentDescription = "Смотреть",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HudBadge(
    title: String = "",
    subtitle: String? = null,
    iconRes: Int? = null,
    contentDescription: String = title,
    iconOnly: Boolean = false,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xD9111111))
            .padding(horizontal = 26.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        iconRes?.let {
            Icon(
                painter = painterResource(it),
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(if (iconOnly) 34.dp else 24.dp),
            )
        }
        if (!iconOnly && title.isNotBlank()) {
            Text(
                text = title,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        subtitle?.let {
            Text(
                text = it,
                fontSize = 18.sp,
                color = Color(0xFFD0D0D0),
            )
        }
    }
}

@Composable
private fun rememberCurrentTimeText(): String {
    var currentTimeText by remember { mutableStateOf(formatCurrentClockTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTimeText = formatCurrentClockTime()
            delay(30_000L)
        }
    }
    return currentTimeText
}

@Composable
private fun TimelineMeta(
    modifier: Modifier = Modifier,
    snapshot: ComposePlaybackSnapshot,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        TimelineLegend("Опенинг", Color(0xFF2E8B57))
        TimelineLegend("Эндинг", Color(0xFF1E88E5))
        Text(
            text = "${formatDuration(snapshot.positionMs)} / ${formatDuration(snapshot.durationMs)}",
            fontSize = 14.sp,
            color = Color(0xFFCACACA),
        )
    }
}

@Composable
private fun CompletionOverlay(
    overlay: PlayerCompletionOverlay?,
    selectedAction: CompletionOverlayAction,
    progress: Float,
    remainingSec: Int?,
    onNextClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = overlay != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        overlay ?: return@AnimatedVisibility
        val primaryFocusRequester = remember { FocusRequester() }
        LaunchedEffect(overlay) {
            primaryFocusRequester.requestFocusSafely()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x7A000000))
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(520.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color(0xEE141414))
                    .border(
                        width = 1.dp,
                        brush = SolidColor(Color(0x24FFFFFF)),
                        shape = RoundedCornerShape(30.dp),
                    )
                    .padding(horizontal = 28.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = overlay.title,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = overlay.subtitle,
                        color = Color(0xFFBDBDBD),
                        fontSize = 15.sp,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AutoAdvanceButton(
                        label = overlay.nextEpisodeLabel ?: "Следующая серия",
                        enabled = overlay.type == PlayerCompletionOverlayType.END_EPISODE,
                        selected = selectedAction == CompletionOverlayAction.Next,
                        progress = progress,
                        remainingSec = remainingSec,
                        modifier = Modifier.focusRequester(primaryFocusRequester),
                        onClick = onNextClick,
                    )
                    OverlayTextButton(
                        label = overlay.closeLabel,
                        selected = selectedAction == CompletionOverlayAction.Close,
                        onClick = onCloseClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerStatsOverlay(
    stats: PlayerStatsState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(320.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xC8121212))
            .border(
                width = 1.dp,
                brush = SolidColor(Color(0x26FFFFFF)),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Статистика",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        PlayerStatsRow("Состояние", stats.playbackStateLabel)
        PlayerStatsRow("Битрейт", stats.bitrateLabel)
        PlayerStatsRow("Видео", stats.videoSizeLabel)
        PlayerStatsRow("FPS", stats.fpsLabel)
        PlayerStatsRow("Кодек", stats.codecLabel)
        PlayerStatsRow("Буфер", stats.bufferLabel)
        PlayerStatsRow("Память", stats.memoryUsedLabel)
        PlayerStatsRow("Доступно", stats.memoryAvailableLabel)
        PlayerStatsRow("Потеряно кадров", stats.droppedFramesLabel)
        PlayerStatsRow("Ребуферов", stats.rebufferCountLabel)
    }
}

@Composable
private fun PlayerStatsRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color(0xFF9E9E9E),
            fontSize = 13.sp,
            maxLines = 1,
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OverlayTextButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    focused -> Color(0xFFFE3635)
                    selected -> Color(0xFF343434)
                    else -> Color(0xFF242424)
                }
            )
            .border(
                width = if (focused || selected) 1.dp else 0.dp,
                brush = SolidColor(if (focused) Color(0x55FFFFFF) else Color(0x22FFFFFF)),
                shape = RoundedCornerShape(18.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AutoAdvanceButton(
    label: String,
    enabled: Boolean,
    selected: Boolean,
    progress: Float,
    remainingSec: Int?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .width(220.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) Color(0xFF1E1E1E) else Color(0xFF242424))
            .border(
                width = if (focused || selected) 1.dp else 0.dp,
                brush = SolidColor(if (focused) Color(0x55FFFFFF) else Color(0x22FFFFFF)),
                shape = RoundedCornerShape(18.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .onPreviewKeyEvent { event ->
                if (enabled &&
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
    ) {
        if (enabled) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(Color(0xFFFE3635))
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = if (enabled) Color.White else Color(0xFF8C8C8C),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (enabled) {
                Text(
                    text = "${remainingSec ?: 3}с",
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun PlayerActionButton(
    label: String,
    iconRes: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    compact: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val backgroundColor = when {
        !enabled -> Color(0x33262626)
        focused -> Color(0xFFFE3635)
        selected -> Color(0xFF363636)
        else -> Color(0xFF2A2A2A)
    }
    val textColor = if (enabled) Color.White else Color(0xFF8C8C8C)

    Box(
        modifier = modifier
            .width(if (compact) 58.dp else 64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(backgroundColor)
            .border(
                width = if (focused) 2.dp else if (selected) 1.dp else 1.dp,
                brush = SolidColor(
                    when {
                        focused -> Color(0x55FFFFFF)
                        selected -> Color(0x22FFFFFF)
                        else -> Color.Transparent
                    }
                ),
                shape = RoundedCornerShape(18.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = enabled)
            .onPreviewKeyEvent { event ->
                if (enabled &&
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = textColor,
            modifier = Modifier
                .align(Alignment.Center)
                .size(if (compact) 22.dp else 26.dp),
        )
    }
}

@Composable
private fun TimelineControl(
    modifier: Modifier,
    snapshot: ComposePlaybackSnapshot,
    skips: PlayerSkips?,
    onSeekTo: (Long) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var timelineSize by remember { mutableStateOf(IntSize.Zero) }
    val duration = snapshot.durationMs.takeIf { it > 0 } ?: 1L
    val playedFraction = (snapshot.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val bufferedFraction = (snapshot.bufferedPositionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(9.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (focused) Color(0xFF2B2B2B) else Color(0xFF1F1F1F))
                .pointerInput(duration, timelineSize) {
                    detectTapGestures { offset ->
                        if (timelineSize.width <= 0) return@detectTapGestures
                        val fraction = (offset.x / timelineSize.width.toFloat()).coerceIn(0f, 1f)
                        onSeekTo((duration * fraction).toLong())
                    }
                }
                .onSizeChanged {
                    timelineSize = it
                }
        ) {
            val totalWidth = maxWidth

            TimelineFill(
                width = totalWidth * bufferedFraction,
                color = Color(0xFF4B4B4B),
            )
            TimelineFill(
                width = totalWidth * playedFraction,
                color = Color(0xFFFE3635),
            )
            TimelineMarkerRange(
                totalWidth = totalWidth,
                duration = duration,
                skip = skips?.opening,
                color = Color(0xFF2E8B57),
            )
            TimelineMarkerRange(
                totalWidth = totalWidth,
                duration = duration,
                skip = skips?.ending,
                color = Color(0xFF1E88E5),
            )
            Box(
                modifier = Modifier
                    .offset(x = (totalWidth * playedFraction) - 5.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (focused) Color.White else Color(0xFFE0E0E0))
            )
        }
    }
}

@Composable
private fun TimelineFill(
    width: Dp,
    color: Color,
) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxSize()
            .background(color)
    )
}

@Composable
private fun TimelineMarkerRange(
    totalWidth: Dp,
    duration: Long,
    skip: PlayerSkips.Skip?,
    color: Color,
) {
    if (skip == null || duration <= 0) {
        return
    }
    val startFraction = (skip.start.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    val endFraction = (skip.end.toFloat() / duration.toFloat()).coerceIn(startFraction, 1f)
    val markerWidth = (totalWidth * (endFraction - startFraction)).coerceAtLeast(2.dp)
    Box(
        modifier = Modifier
            .offset(x = totalWidth * startFraction)
            .width(markerWidth)
            .fillMaxSize()
            .background(color.copy(alpha = 0.55f))
    )
}

@Composable
private fun TimelineLegend(
    label: String,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            fontSize = 14.sp,
            color = Color(0xFFBDBDBD),
        )
    }
}

private fun PlayerQuality?.toQualityIconRes(): Int {
    return when (this) {
        PlayerQuality.SD -> R.drawable.ic_quality_sd_base
        PlayerQuality.HD -> R.drawable.ic_quality_hd_base
        PlayerQuality.FULLHD, null -> R.drawable.ic_quality_full_hd_base
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) {
        return "00:00"
    }
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun formatDelta(durationMs: Long): String {
    val seconds = abs(durationMs) / 1000
    return "${seconds}с"
}

private fun formatCurrentClockTime(): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}

private fun formatBitrate(value: Long?): String {
    value ?: return "Нет данных"
    if (value <= 0L) return "Нет данных"
    return String.format("%.2f Мбит/с", value / 1_000_000f)
}

private fun formatVideoSize(format: Format?): String {
    format ?: return "Нет данных"
    val width = format.width.takeIf { it > 0 } ?: return "Нет данных"
    val height = format.height.takeIf { it > 0 } ?: return "Нет данных"
    return "${width}x${height}"
}

private fun formatFps(format: Format?): String {
    val fps = format?.frameRate ?: return "Нет данных"
    if (fps <= 0f) return "Нет данных"
    return String.format("%.2f", fps)
}

private fun formatBuffer(bufferedPositionMs: Long, durationMs: Long): String {
    if (bufferedPositionMs <= 0L) {
        return "0с"
    }
    val bufferedSeconds = (bufferedPositionMs / 1000L).coerceAtLeast(0L)
    val durationSuffix = durationMs.takeIf { it > 0 }?.let { " / ${formatDuration(it)}" }.orEmpty()
    return "${bufferedSeconds}с$durationSuffix"
}

private fun formatMemory(value: Long): String {
    if (value <= 0L) return "n/a"
    return String.format(Locale.US, "%.1f MB", value / 1024f / 1024f)
}

private fun Int.toPlaybackStateLabel(isPlaying: Boolean): String {
    return when (this) {
        Player.STATE_IDLE -> "Ожидание"
        Player.STATE_BUFFERING -> "Буферизация"
        Player.STATE_READY -> if (isPlaying) "Воспроизведение" else "Пауза"
        Player.STATE_ENDED -> "Завершено"
        else -> "Нет данных"
    }
}

private val Long.sign: Long
    get() = when {
        this > 0 -> 1L
        this < 0 -> -1L
        else -> 0L
    }

private fun FocusRequester.requestFocusSafely() {
    runCatching {
        requestFocus()
    }
}
