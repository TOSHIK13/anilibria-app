package ru.radiationx.anilibria.screen.services

import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.external.AniListAuth
import ru.radiationx.data.external.AniListService
import ru.radiationx.data.external.AniListTokens
import ru.radiationx.data.external.ExternalServiceOptions
import ru.radiationx.data.external.ExternalServiceSettings
import ru.radiationx.data.external.ExternalTokenStore
import ru.radiationx.data.tracker.TrackerState
import javax.inject.Inject

data class ServiceUi(
    val state: TrackerState,
    val options: ExternalServiceOptions,
)

class AniListServiceViewModel @Inject constructor(
    store: ExternalTokenStore,
    private val settings: ExternalServiceSettings,
    private val auth: AniListAuth,
    private val router: Router,
) : LifecycleViewModel() {

    val ui: Flow<ServiceUi> = combine(
        store.observe(AniListService.ID),
        settings.observe(AniListService.ID),
    ) { token, options ->
        ServiceUi(AniListTokens.state(token, System.currentTimeMillis()), options)
    }

    fun update(transform: (ExternalServiceOptions) -> ExternalServiceOptions) =
        settings.update(AniListService.ID, transform)

    fun refreshLogin() = router.navigateTo(AniListLinkScreen())

    fun disconnect() = auth.unlink()

    fun close() = router.exit()
}
