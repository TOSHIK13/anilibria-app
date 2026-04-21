package ru.radiationx.anilibria.screen.player.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.datasource.holders.PreferencesHolder
import javax.inject.Inject

class PlayerSettingsViewModel @Inject constructor(
    private val preferencesHolder: PreferencesHolder,
) : LifecycleViewModel() {

    val state = MutableStateFlow(PlayerSettingsState())

    init {
        combine(
            preferencesHolder.playerSkips,
            preferencesHolder.playerSkipsTimer,
            preferencesHolder.playerAutoplay,
            preferencesHolder.playerBackBufferSeconds,
            preferencesHolder.playerForwardBufferSeconds,
        ) { skips, skipTimer, autoplay, backBufferSeconds, forwardBufferSeconds ->
            PlayerSettingsState(
                skipsEnabled = skips,
                autoSkipEnabled = skipTimer,
                autoplayEnabled = autoplay,
                backBufferSeconds = backBufferSeconds,
                forwardBufferSeconds = forwardBufferSeconds,
            )
        }.onEach {
            state.value = it
        }.launchIn(viewModelScope)
    }

    fun setSkipsEnabled(value: Boolean) {
        preferencesHolder.playerSkips.value = value
    }

    fun setAutoSkipEnabled(value: Boolean) {
        preferencesHolder.playerSkipsTimer.value = value
    }

    fun setAutoplayEnabled(value: Boolean) {
        preferencesHolder.playerAutoplay.value = value
    }

    fun setBackBufferSeconds(value: Int) {
        preferencesHolder.playerBackBufferSeconds.value = value
    }

    fun setForwardBufferSeconds(value: Int) {
        preferencesHolder.playerForwardBufferSeconds.value = value
    }

    data class PlayerSettingsState(
        val skipsEnabled: Boolean = true,
        val autoSkipEnabled: Boolean = true,
        val autoplayEnabled: Boolean = true,
        val backBufferSeconds: Int = 0,
        val forwardBufferSeconds: Int = 50,
    )
}
