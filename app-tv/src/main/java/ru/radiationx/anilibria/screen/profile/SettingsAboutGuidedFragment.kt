package ru.radiationx.anilibria.screen.profile

import android.os.Bundle
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import ru.radiationx.anilibria.BuildConfig
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.fragment.FakeGuidedStepFragment
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.quill.inject

/** «О приложении»: версия, исходный код, лицензия. */
class SettingsAboutGuidedFragment : FakeGuidedStepFragment() {

    companion object {
        private const val ACTION_CLOSE = 1L
        private const val ACTION_SOURCE = 2L
        private const val ACTION_LICENSE = 3L
    }

    private val guidedRouter by inject<GuidedRouter>()

    override fun onProvideTheme(): Int = R.style.AppTheme_Player_LeanbackWizard

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance = Guidance(
        "AniLiberty TV Mod",
        "Неофициальный клиент AniLiberty для Android TV на основе приложения AniLibria.\n\n" +
                "Версия ${BuildConfig.VERSION_NAME}, сборка от ${BuildConfig.BUILD_DATE}",
        null,
        null
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += GuidedAction.Builder(requireContext())
            .id(ACTION_SOURCE)
            .title("Исходный код")
            .description("github.com/TOSHIK13/anilibria-app")
            .infoOnly(true)
            .build()
        actions += GuidedAction.Builder(requireContext())
            .id(ACTION_LICENSE)
            .title("Лицензия")
            .description("GNU GPL v3")
            .infoOnly(true)
            .build()
        actions += GuidedAction.Builder(requireContext())
            .id(ACTION_CLOSE)
            .title("Закрыть")
            .build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ACTION_CLOSE) {
            guidedRouter.close()
        }
    }
}
