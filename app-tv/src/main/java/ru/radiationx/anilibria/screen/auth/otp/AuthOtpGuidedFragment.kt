package ru.radiationx.anilibria.screen.auth.otp

import android.os.Bundle
import android.view.View
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.leanback.widget.GuidedActionsStylist
import kotlinx.coroutines.flow.filterNotNull
import ru.radiationx.anilibria.common.fragment.FakeGuidedStepFragment
import ru.radiationx.anilibria.screen.auth.GuidedProgressAction
import ru.radiationx.anilibria.screen.auth.GuidedProgressActionsStylist
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo

class AuthOtpGuidedFragment : FakeGuidedStepFragment() {

    companion object {
        private const val COMPLETE_ACTION_ID = 1L
        private const val REFRESH_ACTION_ID = 2L
    }

    private val completeAction by lazy {
        GuidedProgressAction.Builder(requireContext())
            .id(COMPLETE_ACTION_ID)
            .title("Готово")
            .description("Нажмите, когда введёте код")
            .build()
    }

    private val refreshAction by lazy {
        GuidedProgressAction.Builder(requireContext())
            .id(REFRESH_ACTION_ID)
            .title("Обновить код")
            .description("Запросить новый код")
            .build()
    }

    private val viewModel by viewModel<AuthOtpViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycle.addObserver(viewModel)

        subscribeTo(viewModel.otpInfoData.filterNotNull()) {
            guidanceStylist.apply {
                titleView?.text = "Код: ${it.code}"
                descriptionView?.text = buildDescription(it.description, viewModel.state.value)
            }
        }

        subscribeTo(viewModel.state) {
            viewModel.otpInfoData.value?.also { otpInfo ->
                guidanceStylist.descriptionView?.text = buildDescription(otpInfo.description, it)
            }

            val primaryActions = when (it.buttonState) {
                AuthOtpViewModel.ButtonState.COMPLETE -> listOf(completeAction, refreshAction)
                AuthOtpViewModel.ButtonState.EXPIRED,
                AuthOtpViewModel.ButtonState.REPEAT -> listOf(refreshAction)
            }

            actions = if (it.error.isEmpty()) {
                primaryActions
            } else {
                val errorAction = GuidedAction.Builder(requireContext())
                    .title("Ошибка")
                    .multilineDescription(true)
                    .description(it.error)
                    .infoOnly(true)
                    .focusable(false)
                    .build()
                primaryActions + errorAction
            }
            completeAction.updateProgress(it.progress)
            refreshAction.updateProgress(it.progress)
        }
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance =
        GuidanceStylist.Guidance(
            "Запрашивается код",
            "Запрашивается код",
            "Авторизация",
            null
        )

    override fun onCreateActionsStylist(): GuidedActionsStylist = GuidedProgressActionsStylist()

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            COMPLETE_ACTION_ID -> viewModel.onCompleteClick()
            REFRESH_ACTION_ID -> viewModel.onRefreshClick()
        }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        super.onCreateActions(actions, savedInstanceState)
        actions.add(completeAction)
    }

    private fun buildDescription(
        description: String,
        state: AuthOtpViewModel.State,
    ): String {
        val timer = if (state.remainingSeconds > 0) {
            "Код действует: ${state.remainingSeconds.formatTimer()}"
        } else {
            "Время действия кода истекло"
        }
        return "$description\n$timer"
    }

    private fun Long.formatTimer(): String {
        val minutes = this / 60
        val seconds = this % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun GuidedProgressAction.updateProgress(progress: Boolean) {
        updateAction {
            showProgress = progress
            isEnabled = !progress
        }
    }

    private fun <T : GuidedAction> T.updateAction(block: T.() -> Unit) {
        findButtonActionById(id)?.apply {
            block()
            notifyButtonActionChanged(findButtonActionPositionById(id))
        }
        findActionById(id)?.apply {
            block()
            notifyActionChanged(findActionPositionById(id))
        }
    }
}
