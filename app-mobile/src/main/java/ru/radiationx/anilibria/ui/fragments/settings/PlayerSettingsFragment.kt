package ru.radiationx.anilibria.ui.fragments.settings

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.R
import ru.radiationx.data.SharedBuildConfig
import ru.radiationx.data.analytics.features.SettingsAnalytics
import ru.radiationx.data.analytics.features.mapper.toAnalyticsQuality
import ru.radiationx.data.datasource.holders.PreferencesHolder
import ru.radiationx.data.entity.common.PlayerQuality
import ru.radiationx.data.entity.common.PlayerTransport
import ru.radiationx.quill.inject
import taiwa.TaiwaAction
import taiwa.bottomsheet.bottomSheetTaiwa

class PlayerSettingsFragment : BaseSettingFragment() {

    private val appPreferences by inject<PreferencesHolder>()

    private val settingsAnalytics by inject<SettingsAnalytics>()

    private val sharedBuildConfig by inject<SharedBuildConfig>()

    private val qualityTaiwa by bottomSheetTaiwa()

    private val transportTaiwa by bottomSheetTaiwa()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences_player)

        findPreference<Preference>("player_quality")?.apply {
            setOnPreferenceClickListener {
                settingsAnalytics.qualityClick()
                showQualityTaiwa()
                false
            }
        }

        findPreference<Preference>("player_transport")?.apply {
            isVisible = sharedBuildConfig.debug
            setOnPreferenceClickListener {
                showTransportTaiwa()
                false
            }
        }

        findPreference<EditTextPreference>("player_forward_buffer_seconds")?.apply {
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            setOnPreferenceChangeListener { _, newValue ->
                parseBufferSeconds(newValue, min = 1, max = 600)?.let {
                    appPreferences.playerForwardBufferSeconds.value = it
                    true
                } ?: false
            }
        }

        findPreference<EditTextPreference>("player_back_buffer_seconds")?.apply {
            setOnBindEditTextListener { editText ->
                editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            setOnPreferenceChangeListener { _, newValue ->
                parseBufferSeconds(newValue, min = 0, max = 600)?.let {
                    appPreferences.playerBackBufferSeconds.value = it
                    true
                } ?: false
            }
        }

        findPreference<SwitchPreferenceCompat>("player_skips")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                appPreferences.playerSkips.value = newValue as Boolean
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("player_skips_timer")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                appPreferences.playerSkipsTimer.value = newValue as Boolean
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("player_inactive_timer")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                appPreferences.playerInactiveTimer.value = newValue as Boolean
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("player_auto_play")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                appPreferences.playerAutoplay.value = newValue as Boolean
                true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        appPreferences.playerQuality.onEach { quality ->
            findPreference<Preference>("player_quality")?.apply {
                icon = getQualityIcon(quality)
                summary = getQualityTitle(quality)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        appPreferences.playerTransport.onEach { transport ->
            findPreference<Preference>("player_transport")?.apply {
                summary = getTransportTitle(transport)
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        appPreferences.playerForwardBufferSeconds.onEach { value ->
            findPreference<EditTextPreference>("player_forward_buffer_seconds")?.apply {
                text = value.toString()
                summary = "Хранить вперед до $value сек."
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        appPreferences.playerBackBufferSeconds.onEach { value ->
            findPreference<EditTextPreference>("player_back_buffer_seconds")?.apply {
                text = value.toString()
                summary = if (value == 0) {
                    "Назад не хранить"
                } else {
                    "Хранить назад до $value сек."
                }
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun showQualityTaiwa() {
        val currentValue = appPreferences.playerQuality.value
        qualityTaiwa.setContent {
            header {
                toolbar {
                    title(getString(R.string.pref_quality))
                }
            }
            body {
                PlayerQuality.entries.forEach { quality ->
                    radioItem {
                        icon(getQualityIconRes(quality))
                        title(getQualityTitle(quality))
                        select(quality == currentValue)
                        action(TaiwaAction.Close)
                        onClick {
                            settingsAnalytics.qualityChange(quality.toAnalyticsQuality())
                            appPreferences.playerQuality.value = quality
                        }
                    }
                }
            }
        }
        qualityTaiwa.show()
    }

    private fun showTransportTaiwa() {
        val currentValue = appPreferences.playerTransport.value
        transportTaiwa.setContent {
            header {
                toolbar {
                    title(getString(R.string.pref_transport))
                }
            }
            body {
                PlayerTransport.entries.forEach { transport ->
                    radioItem {
                        title(getTransportTitle(transport))
                        select(transport == currentValue)
                        action(TaiwaAction.Close)
                        onClick {
                            appPreferences.playerTransport.value = transport
                        }
                    }
                }
            }
        }
        transportTaiwa.show()
    }

    private fun getQualityIcon(quality: PlayerQuality): Drawable? {
        return ContextCompat.getDrawable(requireContext(), getQualityIconRes(quality))
    }

    private fun getQualityIconRes(quality: PlayerQuality): Int {
        return when (quality) {
            PlayerQuality.SD -> R.drawable.ic_quality_sd_base
            PlayerQuality.HD -> R.drawable.ic_quality_hd_base
            PlayerQuality.FULLHD -> R.drawable.ic_quality_full_hd_base
        }
    }

    private fun getQualityTitle(quality: PlayerQuality): String {
        return when (quality) {
            PlayerQuality.SD -> "480p"
            PlayerQuality.HD -> "720p"
            PlayerQuality.FULLHD -> "1080p"
        }
    }

    private fun getTransportTitle(transport: PlayerTransport): String {
        return when (transport) {
            PlayerTransport.SYSTEM -> "Системный"
            PlayerTransport.OKHTTP -> "OkHttp"
            PlayerTransport.CRONET -> "Cronet"
        }
    }

    private fun parseBufferSeconds(value: Any?, min: Int, max: Int): Int? {
        return value
            ?.toString()
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(min, max)
    }
}
