package ru.radiationx.anilibria.screen.launcher

import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.fragment.GuidedStepNavigator
import ru.radiationx.anilibria.contentprovider.suggestions.SuggestionsContentProvider
import ru.radiationx.anilibria.di.ActivityModule
import ru.radiationx.anilibria.di.NavigationModule
import ru.radiationx.anilibria.di.PlayerModule
import ru.radiationx.anilibria.di.SearchModule
import ru.radiationx.anilibria.di.UpdateModule
import ru.radiationx.anilibria.screen.player.PlayerMotionHandler
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.quill.inject
import ru.radiationx.quill.installModules
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo
import com.github.terrakok.cicerone.NavigatorHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    companion object {
        private const val IDLE_DIM_DELAY_MS = 10 * 60 * 1000L
    }

    private val viewModel: AppLauncherViewModel by viewModel()

    private val navigator by lazy {
        GuidedStepNavigator(
            this,
            R.id.fragmentContainer
        )
    }

    private val navigatorHolder by inject<NavigatorHolder>()
    private var idleDimOverlay: View? = null
    private var idleDimJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        installModules(
            ActivityModule(this),
            NavigationModule(),
            PlayerModule(),
            UpdateModule(),
            SearchModule(),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragments)
        idleDimOverlay = findViewById(R.id.idleDimOverlay)
        lifecycle.addObserver(viewModel)

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
        handleIntent(intent)
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

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == SuggestionsContentProvider.INTENT_ACTION) {
            val uri = intent.data ?: return
            val id = uri.lastPathSegment?.toInt() ?: return
            viewModel.openRelease(ReleaseId(id))
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
