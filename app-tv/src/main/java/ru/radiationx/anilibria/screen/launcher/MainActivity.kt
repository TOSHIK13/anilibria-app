package ru.radiationx.anilibria.screen.launcher

import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.fragment.GuidedStepNavigator
import ru.radiationx.anilibria.contentprovider.suggestions.SuggestionsContentProvider
import ru.radiationx.anilibria.di.ActivityModule
import ru.radiationx.anilibria.di.MainPagesModule
import ru.radiationx.anilibria.di.NavigationModule
import ru.radiationx.anilibria.di.PlayerModule
import ru.radiationx.anilibria.di.SearchModule
import ru.radiationx.anilibria.di.UpdateModule
import ru.radiationx.anilibria.screen.config.ConfigFragment
import ru.radiationx.anilibria.screen.player.PlayerMotionHandler
import ru.radiationx.anilibria.watchnext.WatchNextPublisher
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.system.LoadTiming
import ru.radiationx.quill.inject
import ru.radiationx.quill.installModules
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo
import com.github.terrakok.cicerone.NavigatorHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.common.showCornerNotice
import ru.radiationx.anilibria.screen.services.changesText
import ru.radiationx.anilibria.screen.services.sentNoticeText
import ru.radiationx.data.external.ExternalServiceSettings
import ru.radiationx.data.external.ExternalSyncEngine
import ru.radiationx.data.external.FirstSyncRunner
import ru.radiationx.data.external.OutboxSource

class MainActivity : FragmentActivity() {
    companion object {
        private const val IDLE_DIM_DELAY_MS = 10 * 60 * 1000L
        private const val KEY_WATCH_NEXT_HANDLED = "watch_next_handled"
    }

    private val viewModel: AppLauncherViewModel by viewModel()

    private val navigator by lazy {
        GuidedStepNavigator(
            this,
            R.id.fragmentContainer
        )
    }

    private val navigatorHolder by inject<NavigatorHolder>()
    private val syncEngine by inject<ExternalSyncEngine>()
    private val firstSyncRunner by inject<FirstSyncRunner>()
    private val serviceSettings by inject<ExternalServiceSettings>()
    private var idleDimOverlay: View? = null
    private var idleDimJob: Job? = null

    // Protects against reopening the player from the same Watch Next intent after Activity recreation.
    private var watchNextHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        installModules(
            ActivityModule(this),
            NavigationModule(),
            PlayerModule(),
            UpdateModule(),
            SearchModule(),
            MainPagesModule(),
        )
        super.onCreate(savedInstanceState)
        watchNextHandled = savedInstanceState?.getBoolean(KEY_WATCH_NEXT_HANDLED) ?: false
        setContentView(R.layout.activity_fragments)
        LoadTiming.mark("startup", "activity_create")
        markFirstDraw()
        idleDimOverlay = findViewById(R.id.idleDimOverlay)
        lifecycle.addObserver(viewModel)

        // «Отмечено в AniList · 5/11» после отправки серии — поверх любого экрана, без фокуса
        lifecycleScope.launch {
            syncEngine.sentEvents.collect { event ->
                if (event.source == OutboxSource.EPISODE && serviceSettings.get(event.serviceId).notifyAfterEpisode) {
                    showCornerNotice(sentNoticeText(event), badge = "AL", check = true, durationMs = 4_000)
                }
            }
        }

        // «Продолжить в фоне» в мастере первой синхронизации: итог поверх любого экрана
        lifecycleScope.launch {
            firstSyncRunner.finishedEvents.collect { changes ->
                showCornerNotice("Синхронизация с AniList завершена · ${changesText(changes)}", badge = "AL", check = true, durationMs = 5_000)
            }
        }

        // быстрый старт: главная открывается по окончании вступительной анимации
        supportFragmentManager.setFragmentResultListener(
            ConfigFragment.RESULT_INTRO_FINISHED,
            this
        ) { _, _ -> viewModel.onIntroFinished() }

        subscribeTo(viewModel.appReadyState) {
            handleIntent(intent)
        }

        if (savedInstanceState == null) {
            viewModel.coldLaunch()
        }
        resetIdleDimTimer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == WatchNextPublisher.ACTION_WATCH_NEXT) {
            setIntent(intent)
            watchNextHandled = false
        }
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_WATCH_NEXT_HANDLED, watchNextHandled)
    }

    override fun onResumeFragments() {
        super.onResumeFragments()
        navigatorHolder.setNavigator(navigator)
    }

    override fun onPause() {
        navigatorHolder.removeNavigator()
        idleDimJob?.cancel()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resetIdleDimTimer()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            resetIdleDimTimer()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        resetIdleDimTimer()
        if (ev.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            val playerFragment = findCurrentPlayerHandler()
            if (playerFragment?.handleTouchpadEvent(ev) == true) {
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        resetIdleDimTimer()
        if (ev.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            val playerFragment = findCurrentPlayerHandler()
            if (playerFragment?.handleTouchpadEvent(ev) == true) {
                return true
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetIdleDimTimer()
    }

    private fun markFirstDraw() {
        if (!LoadTiming.enabled) return
        val decorView = window.decorView
        val listener = object : ViewTreeObserver.OnDrawListener {
            private var drawn = false
            override fun onDraw() {
                if (drawn) return
                drawn = true
                LoadTiming.markOnce("startup", "window_drawn")
                val listener = this
                decorView.post { decorView.viewTreeObserver.removeOnDrawListener(listener) }
            }
        }
        decorView.viewTreeObserver.addOnDrawListener(listener)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == SuggestionsContentProvider.INTENT_ACTION) {
            val uri = intent.data ?: return
            val id = uri.lastPathSegment?.toInt() ?: return
            viewModel.openRelease(ReleaseId(id))
        }
        if (intent.action == WatchNextPublisher.ACTION_WATCH_NEXT) {
            // Before the app is ready, the appReadyState subscription handles the intent.
            if (watchNextHandled || viewModel.appReadyState.value == null) return
            val episodeId = WatchNextPublisher.parseIntent(intent) ?: return
            watchNextHandled = true
            viewModel.openPlayer(episodeId.releaseId, episodeId)
        }
    }

    private fun findCurrentPlayerHandler(): PlayerMotionHandler? {
        return findPlayerHandlerRecursive(supportFragmentManager.findFragmentById(R.id.fragmentContainer))
    }

    private fun findPlayerHandlerRecursive(fragment: Fragment?): PlayerMotionHandler? {
        when {
            fragment == null -> return null
            fragment is PlayerMotionHandler && fragment.isVisible -> return fragment
        }
        val fragments = fragment.childFragmentManager.fragments.asReversed()
        for (child in fragments) {
            val playerFragment = findPlayerHandlerRecursive(child)
            if (playerFragment != null) {
                return playerFragment
            }
        }
        return null
    }

    private fun resetIdleDimTimer() {
        hideIdleDim()
        idleDimJob?.cancel()
        idleDimJob = lifecycleScope.launch {
            delay(IDLE_DIM_DELAY_MS)
            if (findCurrentPlayerHandler()?.shouldBlockIdleDim() == true) {
                resetIdleDimTimer()
                return@launch
            }
            showIdleDim()
        }
    }

    private fun showIdleDim() {
        idleDimOverlay?.apply {
            visibility = View.VISIBLE
            animate().cancel()
            animate()
                .alpha(1f)
                .setDuration(250L)
                .start()
        }
    }

    private fun hideIdleDim() {
        idleDimOverlay?.apply {
            if (visibility != View.VISIBLE && alpha == 0f) {
                return
            }
            animate().cancel()
            animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction {
                    visibility = View.GONE
                }
                .start()
        }
    }
}
