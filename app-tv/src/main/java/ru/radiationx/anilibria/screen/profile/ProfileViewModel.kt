package ru.radiationx.anilibria.screen.profile

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.anilibria.screen.AuthGuidedScreen
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.PlayerBufferSettingsGuidedScreen
import ru.radiationx.anilibria.screen.player.settings.PlayerBufferTarget
import ru.radiationx.data.datasource.holders.PreferencesHolder
import ru.radiationx.data.entity.domain.other.ProfileItem
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val preferencesHolder: PreferencesHolder,
    private val guidedRouter: GuidedRouter,
) : LifecycleViewModel() {

    val state = MutableStateFlow(SettingsState())

    init {
        combine(
            authRepository.observeUser(),
            preferencesHolder.playerSkips,
            preferencesHolder.playerSkipsTimer,
            preferencesHolder.playerAutoplay,
            preferencesHolder.playerBackBufferSeconds,
            preferencesHolder.playerForwardBufferSeconds,
        ) { values ->
            SettingsState(
                profile = values[0] as ProfileItem?,
                skipsEnabled = values[1] as Boolean,
                autoSkipEnabled = values[2] as Boolean,
                autoplayEnabled = values[3] as Boolean,
                backBufferSeconds = values[4] as Int,
                forwardBufferSeconds = values[5] as Int,
            )
        }.onEach {
            state.value = it
        }.launchIn(viewModelScope)
    }

    fun onAuthClick() {
        if (state.value.profile == null) {
            guidedRouter.open(AuthGuidedScreen())
        } else {
            viewModelScope.launch {
                coRunCatching {
                    authRepository.signOut()
                }.onFailure {
                    Timber.e(it)
                }
            }
        }
    }

    fun onSkipsClick() {
        preferencesHolder.playerSkips.value = !preferencesHolder.playerSkips.value
    }

    fun onAutoSkipClick() {
        preferencesHolder.playerSkipsTimer.value = !preferencesHolder.playerSkipsTimer.value
    }

    fun onAutoplayClick() {
        preferencesHolder.playerAutoplay.value = !preferencesHolder.playerAutoplay.value
    }

    fun onBackBufferClick() {
        guidedRouter.open(PlayerBufferSettingsGuidedScreen(PlayerBufferTarget.BACK))
    }

    fun onForwardBufferClick() {
        guidedRouter.open(PlayerBufferSettingsGuidedScreen(PlayerBufferTarget.FORWARD))
    }

    data class SettingsState(
        val profile: ProfileItem? = null,
        val skipsEnabled: Boolean = true,
        val autoSkipEnabled: Boolean = true,
        val autoplayEnabled: Boolean = true,
        val backBufferSeconds: Int = 0,
        val forwardBufferSeconds: Int = 50,
    )
}
