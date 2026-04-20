package ru.radiationx.anilibria.screen.player.settings

import android.os.Bundle
import android.view.View
import androidx.leanback.widget.GuidedAction
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.screen.player.BasePlayerGuidedFragment
import ru.radiationx.anilibria.screen.player.PlayerController
import ru.radiationx.quill.inject
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo

class PlayerSettingsGuidedFragment : BasePlayerGuidedFragment() {

    companion object {
        private const val SKIPS_ACTION_ID = 4L
        private const val AUTO_SKIP_ACTION_ID = 5L
        private const val AUTOPLAY_ACTION_ID = 6L
    }

    private val playerController by inject<PlayerController>()

    private val viewModel by viewModel<PlayerSettingsViewModel> { argExtra }

    override fun onProvideTheme(): Int = R.style.AppTheme_Player_LeanbackWizard

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

    private fun createActions(
        state: PlayerSettingsViewModel.PlayerSettingsState,
    ): List<GuidedAction> {
        return listOf(
            GuidedAction.Builder(requireContext())
                .id(SKIPS_ACTION_ID)
                .title("Кнопки пропуска")
                .description("Опенинг и эндинг")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(state.skipsEnabled)
                .build(),
            GuidedAction.Builder(requireContext())
                .id(AUTO_SKIP_ACTION_ID)
                .title("Автопропуск через 5 сек.")
                .description("Работает, когда включены кнопки пропуска")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(state.autoSkipEnabled)
                .build(),
            GuidedAction.Builder(requireContext())
                .id(AUTOPLAY_ACTION_ID)
                .title("Автовоспроизведение")
                .description("Переходить к следующей серии")
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(state.autoplayEnabled)
                .build(),
        )
    }
}
