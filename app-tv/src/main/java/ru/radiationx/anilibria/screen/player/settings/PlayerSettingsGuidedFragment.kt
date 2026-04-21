package ru.radiationx.anilibria.screen.player.settings

import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.core.text.isDigitsOnly
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.fragment.FakeGuidedStepFragment
import ru.radiationx.anilibria.screen.player.PlayerController
import ru.radiationx.quill.inject
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo

class PlayerSettingsGuidedFragment : FakeGuidedStepFragment() {

    companion object {
        private const val BUFFER_HEADER_ACTION_ID = 1L
        private const val SKIPS_ACTION_ID = 4L
        private const val AUTO_SKIP_ACTION_ID = 5L
        private const val AUTOPLAY_ACTION_ID = 6L
        private const val BACK_BUFFER_ACTION_ID = 7L
        private const val FORWARD_BUFFER_ACTION_ID = 8L
    }

    private val playerController by inject<PlayerController>()

    private val viewModel by viewModel<PlayerSettingsViewModel>()

    override fun onProvideTheme(): Int = R.style.AppTheme_Player_LeanbackWizard

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance = Guidance(
        "Настройки плеера",
        "Переключатели меняют поведение воспроизведения. Буфер задаётся в секундах: сколько хранить назад и сколько подгружать вперёд.",
        null,
        null
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycle.addObserver(viewModel)

        subscribeTo(viewModel.state) {
            actions = createActions(it)
        }
    }

    override fun onStart() {
        super.onStart()
        playerController.settingsOverlayVisible.value = true
    }

    override fun onStop() {
        playerController.settingsOverlayVisible.value = false
        super.onStop()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            SKIPS_ACTION_ID -> viewModel.setSkipsEnabled(action.isChecked)
            AUTO_SKIP_ACTION_ID -> viewModel.setAutoSkipEnabled(action.isChecked)
            AUTOPLAY_ACTION_ID -> viewModel.setAutoplayEnabled(action.isChecked)
        }
    }

    override fun onGuidedActionEditCanceled(action: GuidedAction) {
        validateBufferAction(action)
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        return validateBufferAction(action)
    }

    private fun createActions(
        state: PlayerSettingsViewModel.PlayerSettingsState,
    ): List<GuidedAction> {
        return listOf(
            GuidedAction.Builder(requireContext())
                .id(SKIPS_ACTION_ID)
                .title("Кнопки пропуска")
                .description("Показывать кнопки для опенинга и эндинга")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(state.skipsEnabled)
                .build(),
            GuidedAction.Builder(requireContext())
                .id(AUTO_SKIP_ACTION_ID)
                .title("Автопропуск")
                .description("Через 5 секунд, если кнопки пропуска включены")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(state.autoSkipEnabled)
                .build(),
            GuidedAction.Builder(requireContext())
                .id(AUTOPLAY_ACTION_ID)
                .title("Автовоспроизведение")
                .description("Переходить к следующей серии автоматически")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(state.autoplayEnabled)
                .build(),
            GuidedAction.Builder(requireContext())
                .id(BUFFER_HEADER_ACTION_ID)
                .title("Буфер видео")
                .description("Значения в секундах. Назад: уже просмотренный кусок. Вперёд: запас до текущей точки.")
                .multilineDescription(true)
                .infoOnly(true)
                .focusable(false)
                .build(),
            createBufferAction(
                actionId = BACK_BUFFER_ACTION_ID,
                title = "Буфер назад",
                value = state.backBufferSeconds,
                hint = "0..600 сек."
            ),
            createBufferAction(
                actionId = FORWARD_BUFFER_ACTION_ID,
                title = "Буфер вперёд",
                value = state.forwardBufferSeconds,
                hint = "1..600 сек."
            ),
        )
    }

    private fun createBufferAction(
        actionId: Long,
        title: String,
        value: Int,
        hint: String,
    ): GuidedAction {
        return GuidedAction.Builder(requireContext())
            .id(actionId)
            .title(title)
            .description("Сейчас: ${formatSeconds(value)}")
            .editDescription(value.toString())
            .descriptionEditable(true)
            .descriptionEditInputType(InputType.TYPE_CLASS_NUMBER)
            .build().apply {
                editTitle = hint
            }
    }

    private fun validateBufferAction(action: GuidedAction): Long {
        if (action.id != BACK_BUFFER_ACTION_ID && action.id != FORWARD_BUFFER_ACTION_ID) {
            return GuidedAction.ACTION_ID_NEXT
        }

        val rawValue = action.editDescription?.toString().orEmpty().trim()
        if (rawValue.isEmpty() || !rawValue.isDigitsOnly()) {
            action.description = "Введите целое число в секундах"
            notifyActionChanged(findActionPositionById(action.id))
            return GuidedAction.ACTION_ID_CURRENT
        }

        val value = rawValue.toIntOrNull()
        val range = if (action.id == BACK_BUFFER_ACTION_ID) 0..600 else 1..600
        if (value == null || value !in range) {
            action.description = "Допустимо: ${range.first}..${range.last} сек."
            notifyActionChanged(findActionPositionById(action.id))
            return GuidedAction.ACTION_ID_CURRENT
        }

        when (action.id) {
            BACK_BUFFER_ACTION_ID -> viewModel.setBackBufferSeconds(value)
            FORWARD_BUFFER_ACTION_ID -> viewModel.setForwardBufferSeconds(value)
        }
        action.description = "Сейчас: ${formatSeconds(value)}"
        notifyActionChanged(findActionPositionById(action.id))
        return GuidedAction.ACTION_ID_NEXT
    }

    private fun formatSeconds(value: Int): String {
        return if (value >= 60) {
            val minutes = value / 60
            val seconds = value % 60
            if (seconds == 0) {
                "$minutes мин."
            } else {
                "$minutes мин. $seconds сек."
            }
        } else {
            "$value сек."
        }
    }
}
