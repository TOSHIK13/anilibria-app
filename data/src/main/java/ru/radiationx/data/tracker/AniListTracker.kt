package ru.radiationx.data.tracker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.radiationx.data.external.AniListAuth
import ru.radiationx.data.external.AniListService
import ru.radiationx.data.external.AniListTokens
import ru.radiationx.data.external.AniListValidation
import ru.radiationx.data.external.DesiredState
import ru.radiationx.data.external.DesiredStatus
import ru.radiationx.data.external.ExternalOutbox
import ru.radiationx.data.external.ExternalServiceSettings
import ru.radiationx.data.external.ExternalSyncJournal
import ru.radiationx.data.external.ExternalSyncState
import ru.radiationx.data.external.ExternalTokenStore
import ru.radiationx.data.external.JournalDirection
import ru.radiationx.data.external.JournalMeta
import ru.radiationx.data.external.JournalOrigin
import ru.radiationx.data.external.JournalReason
import ru.radiationx.data.external.JournalResult
import ru.radiationx.data.external.OutboxSource
import java.io.IOException
import javax.inject.Inject

/**
 * AniList как трекер: вход по токену и состояние аккаунта; серии и смена коллекции ставятся в
 * очередь [ExternalOutbox] (отправляет [ru.radiationx.data.external.ExternalSyncEngine]).
 * Реестр не присылает события, пока сервис не привязан; при истёкшем входе они копятся в очереди.
 */
class AniListTracker @Inject constructor(
    private val auth: AniListAuth,
    private val store: ExternalTokenStore,
    private val settings: ExternalServiceSettings,
    private val outbox: ExternalOutbox,
    private val syncState: ExternalSyncState,
    private val journal: ExternalSyncJournal,
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
        if (!settings.get(id).sendWatched) return
        val ref = event.release
        val title = ref.title ?: "Релиз ${ref.releaseId.id}"
        val malId = malIdOf(ref) ?: return skipped(ref, title, "серия не отправлена · у релиза нет MAL id", JournalOrigin.EPISODE)
        val total = event.episodesTotal
        val desired = if (event.episodeOrdinal == null) {
            val all = total ?: event.episodesWatched
            DesiredState(DesiredStatus.COMPLETED, all, total)
        } else {
            DesiredState(null, event.episodesWatched, total)
        }
        enqueue(ref, malId, title, desired, OutboxSource.EPISODE, event.episodeOrdinal?.toInt())
    }

    override suspend fun onCollectionChanged(event: TrackerCollectionChangedEvent) {
        if (!settings.get(id).sendCollection) return
        val ref = event.release
        val title = ref.title ?: "Релиз ${ref.releaseId.id}"
        val malId = malIdOf(ref) ?: return skipped(ref, title, "смена коллекции не отправлена · у релиза нет MAL id")
        enqueue(ref, malId, title, DesiredState(DesiredStatus.of(event.collection)), OutboxSource.COLLECTION, null)
    }

    private fun malIdOf(ref: TrackerReleaseRef): Int? = (ref.malId ?: ref.shikimoriId)?.takeIf { it > 0 }

    private fun enqueue(
        ref: TrackerReleaseRef,
        malId: Int,
        title: String,
        desired: DesiredState,
        source: OutboxSource,
        episode: Int?,
    ) {
        outbox.enqueue(id, malId, ref.releaseId.id, title, desired, source, episode)
        syncState.markPending(id, malId, ref.releaseId.id)
    }

    private fun skipped(ref: TrackerReleaseRef, title: String, text: String, origin: JournalOrigin = JournalOrigin.COLLECTION) {
        journal.record(
            id, JournalDirection.OUT, null, ref.releaseId.id, title, text, JournalResult.SKIPPED,
            meta = JournalMeta(origin = origin, reason = JournalReason.RELEASE_NO_MAL),
        )
    }
}
