package ru.radiationx.data.tracker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.radiationx.data.external.AniListAuth
import ru.radiationx.data.external.AniListService
import ru.radiationx.data.external.AniListTokens
import ru.radiationx.data.external.AniListValidation
import ru.radiationx.data.external.ExternalTokenStore
import java.io.IOException
import javax.inject.Inject

/**
 * AniList как трекер. Этап 2: вход по токену и состояние аккаунта; отправка серий и коллекций
 * появится на этапе 3 (пока [onEpisodeWatched] и [onCollectionChanged] ничего не делают).
 */
class AniListTracker @Inject constructor(
    private val auth: AniListAuth,
    private val store: ExternalTokenStore,
) : AnimeTracker {

    override val id: String = AniListService.ID

    override val title: String = "AniList"

    override val brandColor: Int = 0xFF02A9FF.toInt()

    override val linkMethod: TrackerLinkMethod = TrackerLinkMethod.TokenPaste(auth.authorizeUrl)

    override fun observeState(): Flow<TrackerState> = store.observe(id).map {
        AniListTokens.state(it, System.currentTimeMillis())
    }

    /** [code] — вставленный токен (или URL/фрагмент с `access_token=`). Не подошёл — исключение. */
    override suspend fun link(code: String?) {
        when (val result = auth.link(code.orEmpty())) {
            is AniListValidation.Ok -> Unit
            is AniListValidation.Invalid -> throw IllegalArgumentException(result.reason)
            is AniListValidation.NetworkError -> throw IOException(result.message)
        }
    }

    override suspend fun unlink() = auth.unlink()

    override suspend fun onEpisodeWatched(event: TrackerEpisodeWatchedEvent) {
        // этап 3
    }

    override suspend fun onCollectionChanged(event: TrackerCollectionChangedEvent) {
        // этап 3
    }
}
