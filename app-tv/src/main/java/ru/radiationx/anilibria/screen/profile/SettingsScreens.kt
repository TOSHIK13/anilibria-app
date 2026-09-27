package ru.radiationx.anilibria.screen.profile

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import com.github.terrakok.cicerone.androidx.FragmentScreen
import ru.radiationx.anilibria.common.fragment.FakeGuidedStepFragment
import ru.radiationx.anilibria.common.fragment.GuidedAppScreen
import ru.radiationx.anilibria.screen.config.ConfigFragment

/** Боковая панель выбора значения настройки плеера. */
class SettingsChoiceGuidedScreen(private val choice: SettingsChoice) : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return SettingsChoiceGuidedFragment.newInstance(choice)
    }
}

/** Подтверждение «Выйти из аккаунта?». */
class SettingsSignOutGuidedScreen : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return SettingsSignOutGuidedFragment()
    }
}

class SettingsAboutGuidedScreen : GuidedAppScreen() {
    override fun createFragment(factory: FragmentFactory): FakeGuidedStepFragment {
        return SettingsAboutGuidedFragment()
    }
}

/** Экран проверки адресов API, открытый из настроек: по окончании возвращается назад. */
class SettingsConfigScreen : FragmentScreen {
    override fun createFragment(factory: FragmentFactory): Fragment {
        return ConfigFragment.newInstance(splashOnly = false, fromSettings = true)
    }
}
