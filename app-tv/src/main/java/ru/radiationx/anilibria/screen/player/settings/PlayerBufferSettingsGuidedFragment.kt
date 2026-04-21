package ru.radiationx.anilibria.screen.player.settings

import android.os.Bundle
import android.text.InputType
import androidx.core.text.isDigitsOnly
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.fragment.FakeGuidedStepFragment
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.data.datasource.holders.PreferencesHolder
import ru.radiationx.quill.inject

enum class PlayerBufferTarget {
    BACK,
    FORWARD,
}

class PlayerBufferSettingsGuidedFragment : FakeGuidedStepFragment() {

    companion object {
        private const val VALUE_ACTION_ID = 1L
        private const val SAVE_ACTION_ID = 2L
        private const val ARG_TARGET = "player_buffer_target"

        fun newInstance(target: PlayerBufferTarget): PlayerBufferSettingsGuidedFragment {
            return PlayerBufferSettingsGuidedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TARGET, target.name)
                }
            }
        }
    }

    private val preferencesHolder by inject<PreferencesHolder>()
    private val guidedRouter by inject<GuidedRouter>()

    private val target by lazy {
        PlayerBufferTarget.valueOf(requireArguments().getString(ARG_TARGET) ?: PlayerBufferTarget.FORWARD.name)
    }

    override fun onProvideTheme(): Int = R.style.AppTheme_Player_LeanbackWizard

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance = Guidance(
        if (target == PlayerBufferTarget.BACK) "Буфер назад" else "Буфер вперёд",
        if (target == PlayerBufferTarget.BACK) {
            "Сколько секунд уже просмотренного видео держать в памяти. Допустимо: 0..600 секунд."
        } else {
            "Сколько секунд видео держать вперёд от текущей точки. Допустимо: 1..600 секунд."
        },
        null,
        null
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += GuidedAction.Builder(requireContext())
            .id(VALUE_ACTION_ID)
            .title("Значение в секундах")
            .description(currentValue().toString())
            .editDescription(currentValue().toString())
            .descriptionEditable(true)
            .descriptionEditInputType(InputType.TYPE_CLASS_NUMBER)
            .build()
    }

    override fun onCreateButtonActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += GuidedAction.Builder(requireContext())
            .id(SAVE_ACTION_ID)
            .title("Сохранить")
            .build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == SAVE_ACTION_ID && saveValue()) {
            guidedRouter.close()
        }
    }

    override fun onGuidedActionEditCanceled(action: GuidedAction) {
        validateValue(action)
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        return if (validateValue(action)) {
            GuidedAction.ACTION_ID_NEXT
        } else {
            GuidedAction.ACTION_ID_CURRENT
        }
    }

    private fun saveValue(): Boolean {
        val valueAction = findActionById(VALUE_ACTION_ID) ?: return false
        if (!validateValue(valueAction)) {
            return false
        }
        val value = valueAction.editDescription?.toString()?.trim()?.toIntOrNull() ?: return false
        when (target) {
            PlayerBufferTarget.BACK -> preferencesHolder.playerBackBufferSeconds.value = value
            PlayerBufferTarget.FORWARD -> preferencesHolder.playerForwardBufferSeconds.value = value
        }
        return true
    }

    private fun validateValue(action: GuidedAction): Boolean {
        val rawValue = action.editDescription?.toString().orEmpty().trim()
        if (rawValue.isEmpty() || !rawValue.isDigitsOnly()) {
            action.description = "Введите целое число"
            notifyActionChanged(findActionPositionById(action.id))
            return false
        }
        val value = rawValue.toIntOrNull()
        val range = if (target == PlayerBufferTarget.BACK) 0..600 else 1..600
        if (value == null || value !in range) {
            action.description = "Допустимо: ${range.first}..${range.last}"
            notifyActionChanged(findActionPositionById(action.id))
            return false
        }
        action.description = value.toString()
        notifyActionChanged(findActionPositionById(action.id))
        return true
    }

    private fun currentValue(): Int {
        return when (target) {
            PlayerBufferTarget.BACK -> preferencesHolder.playerBackBufferSeconds.value
            PlayerBufferTarget.FORWARD -> preferencesHolder.playerForwardBufferSeconds.value
        }
    }
}
