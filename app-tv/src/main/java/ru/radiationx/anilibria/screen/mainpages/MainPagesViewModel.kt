package ru.radiationx.anilibria.screen.mainpages

import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.UpdateScreen
import ru.radiationx.data.repository.CheckerRepository
import ru.radiationx.data.external.AniListService
import ru.radiationx.data.external.AniListTokens
import ru.radiationx.data.external.ExternalTokenStore
import ru.radiationx.data.tracker.TrackerState
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

class MainPagesViewModel @Inject constructor(
    private val checkerRepository: CheckerRepository,
    private val router: Router,
    tabsController: MainPagesTabsController,
    private val tokenStore: ExternalTokenStore,
) : LifecycleViewModel() {

    val hasUpdatesData = MutableStateFlow(false)

    /** Запросы страниц открыть вкладку (id из [MainPagesFragmentFactory]). */
    val openTabEvent = tabsController.openTabEvent

    /** Разовое уведомление «вход в AniList истёк»; фрагмент показывает и вызывает [onExpiredNoticeShown]. */
    val expiredNotice = MutableStateFlow(false)

    init {
        // Один раз на каждое наступление «истёк»: метка — linkedAt токена, после нового входа сбрасывается сама.
        tokenStore.observe(AniListService.ID).onEach { token ->
            val expired = AniListTokens.state(token, System.currentTimeMillis()) is TrackerState.Expired
            if (token != null && expired && tokenStore.expiredNoticeShownFor(AniListService.ID) != token.linkedAt) {
                tokenStore.markExpiredNoticeShown(AniListService.ID, token.linkedAt)
                expiredNotice.value = true
            }
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            coRunCatching {
                checkerRepository.checkUpdate(true)
            }.onSuccess {
                hasUpdatesData.value = it.hasUpdate
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    fun onExpiredNoticeShown() {
        expiredNotice.value = false
    }

    fun onAppUpdateClick() {
        router.navigateTo(UpdateScreen())
    }
}