package ru.radiationx.anilibria.ui.fragments.settings

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.apptheme.AppThemeController
import ru.radiationx.anilibria.apptheme.AppThemeMode
import ru.radiationx.anilibria.navigation.Screens
import ru.radiationx.data.SharedBuildConfig
import ru.radiationx.data.analytics.AnalyticsConstants
import ru.radiationx.data.analytics.features.SettingsAnalytics
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.quill.inject
import taiwa.TaiwaAction
import taiwa.bottomsheet.bottomSheetTaiwa

class SettingsFragment : BaseSettingFragment() {

    private val authRepository by inject<AuthRepository>()

    private val settingsAnalytics by inject<SettingsAnalytics>()

    private val sharedBuildConfig by inject<SharedBuildConfig>()

    private val appThemeController by inject<AppThemeController>()

    private val themeTaiwa by bottomSheetTaiwa()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)

        findPreference<SwitchPreferenceCompat>("notifications.all")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                (newValue as? Boolean)?.also(settingsAnalytics::notificationMainChange)
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("notifications.service")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                (newValue as? Boolean)?.also(settingsAnalytics::notificationSystemChange)
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("episodes_is_reverse")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                (newValue as? Boolean)?.also(settingsAnalytics::episodesOrderChange)
                true
            }
        }

        findPreference<Preference>("app_theme")?.apply {
            setOnPreferenceClickListener {
                showThemeTaiwa()
                false
            }
        }

        findPreference<Preference>("app_account")?.apply {
            setOnPreferenceClickListener {
                lifecycleScope.launch {
                    if (authRepository.getAuthState() != AuthState.AUTH) {
                        startActivity(Screens.Auth().createIntent(requireContext()))
                    }
                }
                false
            }
        }

        findPreference<Preference>("about.application")?.apply {
            summary = "Версия ${sharedBuildConfig.versionName} (${sharedBuildConfig.buildDate})"
        }

        findPreference<Preference>("about.check_update")?.apply {
            setOnPreferenceClickListener {
                settingsAnalytics.checkUpdatesClick()
                val intent = Screens.AppUpdateScreen(true, AnalyticsConstants.screen_settings)
                    .createIntent(requireContext())
                startActivity(intent)
                false
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        appThemeController.observeMode().onEach { mode ->
            findPreference<Preference>("app_theme")?.apply {
                summary = mode.getTitle()
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        authRepository.observeAuthState().onEach { authState ->
            val user = authRepository.getUser()
            findPreference<Preference>("app_account")?.apply {
                summary = when (authState) {
                    AuthState.AUTH -> user?.nick ?: "Аккаунт подключен"
                    else -> "Авторизоваться"
                }
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

    }

    private fun showThemeTaiwa() {
        val currentValue = appThemeController.getMode()
        themeTaiwa.setContent {
            header {
                toolbar { title(getString(R.string.pref_title_theme_mode)) }
            }
            body {
                AppThemeMode.entries.forEach { mode ->
                    radioItem {
                        title(mode.getTitle())
                        select(mode == currentValue)
                        action(TaiwaAction.Close)
                        onClick { appThemeController.setMode(mode) }
                    }
                }
            }
        }
        themeTaiwa.show()
    }

    private fun AppThemeMode.getTitle(): String {
        return when (this) {
            AppThemeMode.LIGHT -> R.string.pref_value_theme_mode_light
            AppThemeMode.DARK -> R.string.pref_value_theme_mode_dark
            AppThemeMode.SYSTEM -> R.string.pref_value_theme_mode_system
        }.let { getString(it) }
    }
}
