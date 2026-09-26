package ru.radiationx.anilibria.screen.config

import android.os.Bundle
import android.view.View
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.core.view.isVisible
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionManager
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.MotionLayoutListener
import ru.radiationx.anilibria.databinding.FragmentConfigBinding
import ru.radiationx.data.entity.common.ConfigScreenState
import ru.radiationx.data.system.LoadTiming
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo

class ConfigFragment : Fragment(R.layout.fragment_config) {

    companion object {
        private const val ARG_SPLASH_ONLY = "splash_only"

        /** Результат для activity: вступительная анимация в режиме splash-only закончилась. */
        const val RESULT_INTRO_FINISHED = "config_intro_finished"

        /** Пауза на готовом логотипе в режиме splash-only (вместо выезда прогресса). */
        private const val SPLASH_HOLD_MS = 250L

        fun newInstance(splashOnly: Boolean) = ConfigFragment().apply {
            arguments = bundleOf(ARG_SPLASH_ONLY to splashOnly)
        }
    }

    private val binding by viewBinding<FragmentConfigBinding>()

    private val viewModel: ConfiguringViewModel by viewModel()

    private var introFinished = false

    private val splashOnly: Boolean
        get() = arguments?.getBoolean(ARG_SPLASH_ONLY) == true

    private val splashTransitionListener = object : MotionLayoutListener() {
        override fun onTransitionCompleted(motionLayout: MotionLayout, currentId: Int) {
            if (currentId != R.id.logo_end || introFinished) return
            introFinished = true
            viewLifecycleOwner.lifecycleScope.launch {
                delay(SPLASH_HOLD_MS)
                LoadTiming.mark("startup", "intro_end", "splash")
                parentFragmentManager.setFragmentResult(RESULT_INTRO_FINISHED, Bundle.EMPTY)
            }
        }
    }

    private val startTransitionListener = object : MotionLayoutListener() {
        override fun onTransitionCompleted(motionLayout: MotionLayout, currentId: Int) {
            when (currentId) {
                R.id.logo_end -> motionLayout.transitionToState(R.id.logo_end_progress)
                R.id.logo_end_progress -> onIntroFinished()
            }
        }
    }

    private val completeTransitionListener = object : MotionLayoutListener() {
        override fun onTransitionCompleted(motionLayout: MotionLayout, currentId: Int) {
            if (currentId == R.id.logo_end) {
                viewModel.endConfiguring()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (splashOnly) {
            // быстрый старт: только анимация логотипа, проверка адреса идёт в фоне в лаунчере
            binding.mainConstraint.post {
                LoadTiming.mark("startup", "intro_start", "splash")
                binding.mainConstraint.setTransitionListener(splashTransitionListener)
                binding.mainConstraint.transitionToState(R.id.logo_end)
            }
            return
        }

        viewLifecycleOwner.lifecycle.addObserver(viewModel)

        binding.configActionRepeat.setOnClickListener { viewModel.repeatCheck() }
        binding.configActionSkip.setOnClickListener { viewModel.skipCheck() }
        binding.configActionNext.setOnClickListener { viewModel.nextCheck() }

        // проверка идёт параллельно анимации, а не после неё
        viewModel.startConfiguring()

        binding.mainConstraint.post {
            LoadTiming.mark("startup", "intro_start", "config")
            binding.mainConstraint.transitionToEnd()
            binding.mainConstraint.setTransitionListener(startTransitionListener)
        }
        subscribeTo(viewModel.screenStateData.filterNotNull(), ::updateScreen)
        subscribeTo(viewModel.completeEvent) { startCompleteTransition() }
    }

    private fun onIntroFinished() {
        if (introFinished) return
        introFinished = true
        LoadTiming.mark("startup", "intro_end", "config")
        viewModel.screenStateData.value?.also { updateScreen(it) }
        viewModel.onIntroAnimationFinished()
    }

    private fun updateScreen(screenState: ConfigScreenState) {
        // не мешаем MotionLayout-анимации: состояние применится после неё
        if (!introFinished) return
        binding.configErrorText.text = screenState.status
        binding.configActionNext.setText(
            if (screenState.hasNext) {
                R.string.config_action_next
            } else {
                R.string.config_action_restart
            }
        )

        TransitionManager.beginDelayedTransition(binding.mainConstraint)
        binding.configProgressBar.isVisible = !screenState.needRefresh
        binding.configErrorGroup.isVisible = screenState.needRefresh
        binding.configActionNext.isVisible = screenState.needRefresh
        binding.configActionRepeat.requestFocus()
        binding.configActionRepeat.post {
            binding.configActionRepeat.requestFocus()
        }
    }

    private fun startCompleteTransition() {
        binding.mainConstraint.post {
            binding.mainConstraint.transitionToState(R.id.logo_end)
            binding.mainConstraint.setTransitionListener(completeTransitionListener)
        }
    }
}