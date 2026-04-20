package ru.radiationx.anilibria.screen.player.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.player.PlayerExtra
import ru.radiationx.data.datasource.holders.PreferencesHolder
import javax.inject.Inject

class PlayerSettingsViewModel @Inject constructor(
    @Suppress("UNUSED_PARAMETER") argExtra: PlayerExtra,
    private val preferencesHolder: PreferencesHolder,
) : LifecycleViewModel() {

    val state = MutableStateFlow(PlayerSettingsState())

    init {
        combine(
            preferencesHolder.playerSkips,
            preferencesHolder.playerSkipsTimer,
            preferencesHolder.playerAutoplay,
        ) { skips, skipTimer, autoplay ->
            PlayerSettingsState(
                skipsEnabled = skips,
                autoSkipEnabled = skipTimer,
                autoplayEnabled = autoplay,
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

    data class PlayerSettingsState(
        val skipsEnabled: Boolean = true,
        val autoSkipEnabled: Boolean = true,
        val autoplayEnabled: Boolean = true,
    )
}
