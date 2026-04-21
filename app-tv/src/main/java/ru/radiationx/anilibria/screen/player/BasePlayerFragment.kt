package ru.radiationx.anilibria.screen.player

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.leanback.app.VideoSupportFragment
import androidx.leanback.app.VideoSupportFragmentGlueHost
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.ListRow
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.leanback.LeanbackPlayerAdapter
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.ui.presenter.cust.CustomListRowPresenter
import ru.radiationx.data.datasource.holders.PreferencesHolder
import ru.radiationx.data.player.PlayerBufferConfig
import ru.radiationx.data.player.PlayerDataSourceProvider
import ru.radiationx.quill.get

open class BasePlayerFragment : VideoSupportFragment() {

    @UnstableApi
    protected var playerGlue: VideoPlayerGlue? = null
        private set

    protected var player: ExoPlayer? = null
        private set

    protected var skipsPart: PlayerSkipsPart? = null
        private set

    private var touchGestureDetector: GestureDetector? = null
    private var touchSeekAccumulatorPx = 0f
    private var genericMotionAccumulator = 0f

    @SuppressLint("RestrictedApi")
    @UnstableApi
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initializePlayer()
        initializeRows()
        initializePlaybackShortcuts()
        initializeTouchpadControls(view)
        isControlsOverlayAutoHideEnabled = true

        skipsPart = PlayerSkipsPart(
            parent = view as FrameLayout,
            skipButtonText = getString(R.string.player_skip),
            coroutineScope = viewLifecycleOwner.lifecycleScope,
            playerSkipsEnabled = get<PreferencesHolder>().playerSkips,
            playerSkipsTimer = get<PreferencesHolder>().playerSkipsTimer,
            controlsOverlayVisibleProvider = {
                isControlsOverlayVisible
            },
            onSeek = {
                player?.seekTo(it)
            },
            onSkipShow = {
                isShowOrHideControlsOverlayOnUserInteraction = false
                hideControlsOverlay(false)
            },
            onSkipHide = {
                isShowOrHideControlsOverlayOnUserInteraction = true
            }
        )

        playerGlue?.playbackListener = object : VideoPlayerGlue.PlaybackListener {
            @UnstableApi
            override fun onUpdateProgress(position: Long, duration: Long, isPlaying: Boolean) {
                skipsPart?.update(position)
                onPlaybackProgress(position, duration, isPlaying)
            }
        }
    }

    override fun onVideoSizeChanged(videoWidth: Int, videoHeight: Int) {
        if (videoWidth == 0 || videoHeight == 0) {
            return
        }
        super.onVideoSizeChanged(videoWidth, videoHeight)
    }

    override fun onPause() {
        super.onPause()
        playerGlue?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        skipsPart = null
        playerGlue?.playbackListener = null
        touchGestureDetector = null
        touchSeekAccumulatorPx = 0f
        genericMotionAccumulator = 0f
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        releasePlayer()
    }

    protected open fun onCompletePlaying() {}
    protected open fun onPreparePlaying() {}
    protected open fun onSeek(position: Long, duration: Long) {}
    protected open fun onPlaybackProgress(position: Long, duration: Long, isPlaying: Boolean) {}

    @UnstableApi
    private fun initializeRows() {
        val playerGlue = this.playerGlue ?: return
        val controlsRow = playerGlue.controlsRow ?: return

        val rowsPresenter = ClassPresenterSelector().apply {
            addClassPresenter(ListRow::class.java, CustomListRowPresenter())
            addClassPresenter(controlsRow.javaClass, playerGlue.playbackRowPresenter)
        }
        val rowsAdapter = ArrayObjectAdapter(rowsPresenter).apply {
            add(controlsRow)
        }

        adapter = rowsAdapter
    }

    @UnstableApi
    private fun initializePlayer() {
        if (player != null) {
            throw RuntimeException("Player already initialized")
        }

        val dataSourceProvider = get<PlayerDataSourceProvider>()
        val preferencesHolder = get<PreferencesHolder>()
        val dataSourceType = dataSourceProvider.get()
        val dataSourceFactory = DefaultDataSource.Factory(requireContext(), dataSourceType.factory)
        val mediaSourceFactory = DefaultMediaSourceFactory(requireContext()).apply {
            setDataSourceFactory(dataSourceFactory)
        }
        val loadControl = PlayerBufferConfig.createLoadControl(
            preferencesHolder.playerForwardBufferSeconds.value,
            preferencesHolder.playerBackBufferSeconds.value,
        )
        val player = ExoPlayer.Builder(requireContext())
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()

        player.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                when (playbackState) {
                    Player.STATE_ENDED -> onCompletePlaying()
                    Player.STATE_READY -> onPreparePlaying()
                    Player.STATE_BUFFERING -> {
                    }

                    Player.STATE_IDLE -> {
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    onSeek(player.currentPosition, player.duration)
                }
            }
        })

        val playerAdapter = LeanbackPlayerAdapter(requireContext(), player, 500)

        val playerGlue = VideoPlayerGlue(requireContext(), playerAdapter).apply {
            host = VideoSupportFragmentGlueHost(this@BasePlayerFragment)
        }

        this.player = player
        this.playerGlue = playerGlue
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }

    protected fun preparePlayer(url: String) {
        player?.setMediaItem(MediaItem.fromUri(Uri.parse(url)), false)
        player?.prepare()
    }

    private fun initializePlaybackShortcuts() {
        setOnKeyInterceptListener { view, keyCode, event ->
            handlePlaybackShortcut(view, keyCode, event)
        }
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
                    toggleControlsOverlay()
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (kotlin.math.abs(distanceX) <= kotlin.math.abs(distanceY)) {
                        return false
                    }
                    touchSeekAccumulatorPx += distanceX
                    while (touchSeekAccumulatorPx >= seekTriggerDistancePx) {
                        player?.seekBack()
                        touchSeekAccumulatorPx -= seekTriggerDistancePx
                    }
                    while (touchSeekAccumulatorPx <= -seekTriggerDistancePx) {
                        player?.seekForward()
                        touchSeekAccumulatorPx += seekTriggerDistancePx
                    }
                    return true
                }
            }
        )
    }

    fun handleTouchpadEvent(event: MotionEvent): Boolean {
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
                    toggleControlsOverlay()
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
                    player?.seekBack()
                    genericMotionAccumulator -= 1f
                }
                while (genericMotionAccumulator <= -1f) {
                    player?.seekForward()
                    genericMotionAccumulator += 1f
                }
                return true
            }
        }
        return false
    }

    private fun handlePlaybackShortcut(view: View, keyCode: Int, event: KeyEvent): Boolean {
        if (isControlsOverlayVisible) {
            playAfterTimelineSeek(view, keyCode, event)
            return false
        }

        val supportedKey = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> true

            else -> false
        }
        if (!supportedKey) {
            return false
        }

        if (event.action != KeyEvent.ACTION_DOWN) {
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                val player = player ?: return true
                if (player.isPlaying) {
                    playerGlue?.pause()
                } else {
                    playerGlue?.play()
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> player?.seekBack()
            KeyEvent.KEYCODE_DPAD_RIGHT -> player?.seekForward()
        }
        return true
    }

    private fun playAfterTimelineSeek(view: View, keyCode: Int, event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return
        }
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return
        }
        if (view.rootView.findFocus()?.id != androidx.leanback.R.id.playback_progress) {
            return
        }
        if (player?.isPlaying != true) {
            return
        }
        view.post {
            playerGlue?.play()
        }
    }

    private fun toggleControlsOverlay() {
        if (isControlsOverlayVisible) {
            hideControlsOverlay(true)
        } else {
            showControlsOverlay(true)
        }
    }

    private companion object {
        private const val SEEK_STEP_MS = 10_000L
    }
}
