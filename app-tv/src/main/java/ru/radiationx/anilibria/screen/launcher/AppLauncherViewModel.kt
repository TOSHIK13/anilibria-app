package ru.radiationx.anilibria.screen.launcher

import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.screen.AuthGuidedScreen
import ru.radiationx.anilibria.screen.ConfigScreen
import ru.radiationx.anilibria.screen.DetailsScreen
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.MainPagesScreen
import ru.radiationx.anilibria.screen.PlayerScreen
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.interactors.StartupConfigChecker
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.data.system.LoadTiming
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

class AppLauncherViewModel @Inject constructor(
    private val apiConfig: ApiConfig,
    private val startupConfigChecker: StartupConfigChecker,
    private val router: Router,
    private val authRepository: AuthRepository
) : LifecycleViewModel() {

    private var firstLaunch = true
    private var configShown = false
    private var splashShown = false

    val appReadyState = MutableStateFlow<Unit?>(null)

    fun openRelease(id: ReleaseId) {
        router.navigateTo(DetailsScreen(id))
    }

    // Открытие из системного ряда «Продолжить просмотр»: детали релиза под плеером,
    // чтобы «Назад» возвращал на карточку релиза.
    fun openPlayer(releaseId: ReleaseId, episodeId: EpisodeId) {
        router.navigateTo(DetailsScreen(releaseId))
        router.navigateTo(PlayerScreen(releaseId, episodeId))
    }

    fun coldLaunch() {
        initWithConfig()
        //initMain()
    }

    private fun initWithConfig() {
        apiConfig
            .observeNeedConfig()
            .distinctUntilChanged()
            .onEach {
                if (it) {
                    showConfig()
                } else {
                    // первый запуск или возврат с экрана конфигурации после фоновой проверки
                    if (firstLaunch || configShown) {
                        configShown = false
                        initMain()
                    }
                }
            }
            .launchIn(viewModelScope)

        if (startupConfigChecker.canStartWithoutConfig()) {
            // сохранённый конфиг: экран проверки пропускаем, показываем только вступительную
            // анимацию; главная откроется по её окончании (onIntroFinished), сеть не ждём —
            // проверка адреса и загрузка профиля идут в фоне параллельно анимации
            LoadTiming.mark("startup", "config_skipped", "tag=${apiConfig.tag}")
            showSplash()
            startUserLoading()
            viewModelScope.launch {
                coRunCatching {
                    startupConfigChecker.checkInBackground()
                }.onFailure {
                    Timber.e(it)
                }
            }
        } else {
            showConfig()
        }
    }

    /** Конец вступительной анимации в режиме splash-only. */
    fun onIntroFinished() {
        // экран конфигурации уже заменил splash, либо главная уже открыта
        if (configShown || (!splashShown && !firstLaunch)) return
        splashShown = false
        initMain(loadUser = false)
    }

    private fun showSplash() {
        splashShown = true
        router.newRootScreen(ConfigScreen(splashOnly = true))
    }

    private fun showConfig() {
        // если показан splash (фоновая проверка не нашла живой адрес) — конфигурация заменяет его
        splashShown = false
        configShown = true
        router.newRootScreen(ConfigScreen())
    }

    private fun initMain(loadUser: Boolean = true) {
        firstLaunch = false
        viewModelScope.launch {
            router.newRootScreen(MainPagesScreen())
            if (authRepository.getAuthState() == AuthState.NO_AUTH) {
                router.navigateTo(AuthGuidedScreen())
            }
            appReadyState.value = Unit
        }
        if (loadUser) {
            startUserLoading()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startUserLoading() {
        GlobalScope.launch {
            coRunCatching {
                authRepository.loadUser()
            }.onFailure {
                Timber.e(it)
            }
        }
    }

}