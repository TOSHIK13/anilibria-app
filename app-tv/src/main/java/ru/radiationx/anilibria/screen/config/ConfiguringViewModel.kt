package ru.radiationx.anilibria.screen.config

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.entity.common.ConfigScreenState
import ru.radiationx.data.interactors.ConfiguringInteractor
import ru.radiationx.shared.ktx.EventFlow
import javax.inject.Inject

class ConfiguringViewModel @Inject constructor(
    private val apiConfig: ApiConfig,
    private val configuringInteractor: ConfiguringInteractor
) : LifecycleViewModel() {

    private var configuringStarted = false
    private val introFinished = CompletableDeferred<Unit>()
    val screenStateData = MutableStateFlow<ConfigScreenState?>(null)
    val completeEvent = EventFlow<Unit>()

    /**
     * Запускается сразу при открытии экрана, параллельно вступительной анимации.
     * Переход на главную произойдёт после max(анимация, проверка).
     */
    fun startConfiguring() {
        if (configuringStarted) {
            return
        }
        configuringStarted = true
        configuringInteractor.setCompletionGate { introFinished.await() }
        apiConfig
            .observeNeedConfig()
            .onEach {
                if (!it) {
                    completeEvent.emit(Unit)
                }
            }
            .launchIn(viewModelScope)

        configuringInteractor
            .observeScreenState()
            .onEach {
                screenStateData.value = it
            }
            .launchIn(viewModelScope)

        configuringInteractor.initCheck()
    }

    fun onIntroAnimationFinished() {
        introFinished.complete(Unit)
    }

    fun endConfiguring() {
        //router.exit()
    }

    fun repeatCheck() = configuringInteractor.repeatCheck()

    fun nextCheck() = configuringInteractor.nextCheck()

    fun skipCheck() = configuringInteractor.skipCheck()

    override fun onCleared() {
        super.onCleared()
        configuringInteractor.finishCheck()
    }
}