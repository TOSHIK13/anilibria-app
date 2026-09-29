package ru.radiationx.data.external

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.domain.release.EpisodeAccess
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.data.repository.CollectionRepository
import ru.radiationx.data.repository.EpisodeProgressRepository
import ru.radiationx.data.repository.ReleaseRepository
import ru.radiationx.data.repository.WatchProgressRepository
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

enum class PullSkip {
    /** Сервис не привязан или вход истёк. */
    NOT_LINKED,

    /** Переключатель «Получать изменения» выключен. */
    DISABLED,

    /** С прошлого чтения прошло меньше 30 минут. */
    TOO_SOON,

    /** В очереди отправки есть элементы в процессе отправки. */
    OUTBOX_BUSY,

    /** Нет входа в AniLiberty или не готов каталог/история просмотра. */
    NOT_READY,

    /** Чтение уже идёт. */
    BUSY,
}

sealed class PullOutcome {
    data class Skipped(val reason: PullSkip) : PullOutcome()

    /** Ошибка сети/сервиса (уже записана в журнал, если это не обрыв связи). */
    object Failed : PullOutcome()

    /**
     * [applied] — изменений применено к AniLiberty, [queued] — поставлено в очередь отправки.
     * [compareOnly] — первая синхронизация не выполнена: только сравнение, ничего не применялось.
     */
    data class Done(val applied: Int, val queued: Int, val compareOnly: Boolean) : PullOutcome()
}

/**
 * Получение изменений из AniList и трёхстороннее слияние ([ThreeWayMerge]) с коллекциями и прогрессом
 * AniLiberty. Применённые к AniLiberty изменения идут под меткой [RemoteOrigin] и не попадают в очередь
 * отправки. До первой синхронизации ([ExternalServiceSettings.isFirstSyncDone]) только сравнивает списки
 * ([compare]) и пишет в журнал, ничего не применяя и не отправляя.
 */
class ExternalPullSync @Inject constructor(
    private val userList: AniListUserList,
    private val idResolver: IdResolver,
    private val collections: CollectionRepository,
    private val watchProgress: WatchProgressRepository,
    private val episodeProgress: EpisodeProgressRepository,
    private val releases: ReleaseRepository,
    private val authRepository: AuthRepository,
    private val store: ExternalTokenStore,
    private val outbox: ExternalOutbox,
    private val syncState: ExternalSyncState,
    private val journal: ExternalSyncJournal,
    private val settings: ExternalServiceSettings,
) {

    companion object {
        const val MIN_INTERVAL_MS = 30L * 60 * 1000
        private const val INDEX_TIMEOUT_MS = 90_000L
        private const val HISTORY_TIMEOUT_MS = 30_000L
        private const val DETAIL_REMOTE = "изменено в AniList с другого устройства"
    }

    private val serviceId = AniListService.ID
    private val mutex = Mutex()

    /** Всё, что нужно для сравнения/слияния: список AniList, коллекции AniLiberty и каталог. */
    private class Gathered(
        val remote: Map<Int, RemoteListEntry>,
        val noMal: List<RemoteListEntry>,
        val total: Int,
        val collections: Map<ReleaseId, CollectionType>,
        val index: CatalogIndex,
    )

    /**
     * Читает список AniList и сливает с локальными данными. [force] — без ограничения «не чаще раза
     * в 30 минут» («Синхронизировать сейчас»).
     */
    suspend fun pull(force: Boolean = false, manual: Boolean = false): PullOutcome {
        if (store.activeToken(serviceId) == null) return PullOutcome.Skipped(PullSkip.NOT_LINKED)
        if (!settings.get(serviceId).receiveChanges) return PullOutcome.Skipped(PullSkip.DISABLED)
        if (!force && System.currentTimeMillis() - syncState.lastPullAt(serviceId) < MIN_INTERVAL_MS) {
            return PullOutcome.Skipped(PullSkip.TOO_SOON)
        }
        // устаревшее не читаем: элементы в процессе отправки блокируют, ERROR — нет
        if (outbox.items.value.any { it.serviceId == serviceId && it.state == OutboxState.PENDING }) {
            return PullOutcome.Skipped(PullSkip.OUTBOX_BUSY)
        }
        if (!mutex.tryLock()) return PullOutcome.Skipped(PullSkip.BUSY)
        try {
            if (authRepository.getAuthState() != AuthState.AUTH) return PullOutcome.Skipped(PullSkip.NOT_READY)
            val gathered = try {
                gather() ?: return PullOutcome.Skipped(PullSkip.NOT_READY)
            } catch (e: ExternalRateLimitException) {
                Timber.w("external pull: rate limited")
                return PullOutcome.Failed
            } catch (e: ExternalHttpException) {
                // 401 / «Invalid token» уже перевели сервис в «вход истёк» (ExternalHttpClient)
                val expired = store.activeToken(serviceId) == null
                journal.record(
                    serviceId, JournalDirection.CHECK, null, null, "AniList",
                    "проверка не выполнена", JournalResult.ERROR,
                    detail = if (expired) "Вход в AniList истёк (HTTP ${e.code}) · нужен новый вход" else "AniList отклонил запрос (HTTP ${e.code})",
                )
                return PullOutcome.Failed
            } catch (e: IOException) {
                Timber.w(e, "external pull: no connection")
                return PullOutcome.Failed
            } catch (e: JSONException) {
                Timber.w(e, "external pull: bad response")
                return PullOutcome.Failed
            }
            return if (settings.isFirstSyncDone(serviceId)) merge(gathered, manual) else checkOnly(gathered, manual)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Сравнение списков для мастера первой синхронизации: совпадает / расходится / только AniList /
     * только у нас / нет в каталоге. Ничего не применяет и не отправляет. Ошибки сети и AniList
     * ([IOException], [ExternalHttpException]) пробрасываются; null-результата нет — если нет входа
     * в AniLiberty или каталог не загрузился, бросается [IOException].
     */
    suspend fun compare(): FirstSyncReport {
        if (store.activeToken(serviceId) == null) throw IOException("AniList: вход не выполнен")
        if (authRepository.getAuthState() != AuthState.AUTH) throw IOException("Нет входа в AniLiberty")
        val gathered = gather() ?: throw IOException("Каталог AniLiberty ещё не загружен")
        return report(gathered)
    }

    private suspend fun gather(): Gathered? {
        idResolver.ensure()
        val index = withTimeoutOrNull(INDEX_TIMEOUT_MS) { idResolver.index.first { it != null } } ?: return null
        // без истории просмотра локальный прогресс выглядел бы нулевым
        withTimeoutOrNull(HISTORY_TIMEOUT_MS) { watchProgress.observeEpisodes().first() } ?: return null
        val entries = userList.fetchUserList()
        val localCollections = collections.getCollectionIds()
        val supported = entries.filter { SyncSnapshot.statusOf(it.status) != null }
        val withMal = supported.filter { it.malId != null }
            .groupBy { it.malId!! }
            .mapValues { (_, list) -> list.maxByOrNull { it.updatedAt }!! }
        return Gathered(withMal, supported.filter { it.malId == null }, entries.size, localCollections, index)
    }

    private fun localRelease(malId: Int, g: Gathered): ReleaseId? {
        val ids = g.index.releasesOf(malId)
        return (ids.firstOrNull { ReleaseId(it) in g.collections } ?: ids.firstOrNull())?.let { ReleaseId(it) }
    }

    private fun localSnapshot(rid: ReleaseId?, g: Gathered): SyncSnapshot {
        val type = rid?.let { g.collections[it] } ?: return SyncSnapshot.ABSENT
        val progress = watchProgress.currentProgress(rid)?.watched ?: 0
        return SyncSnapshot(statusOf(type), progress)
    }

    private fun universe(g: Gathered): Set<Int> {
        val local = g.collections.keys.mapNotNull { g.index.releaseToMal[it.id] }
        return LinkedHashSet<Int>().apply {
            addAll(g.remote.keys)
            addAll(local)
            // записи, которые раньше были в AniList: их исчезновение — удаление
            syncState.all.value.values.filter { it.serviceId == serviceId && it.remoteStatus != null }.forEach { add(it.malId) }
        }
    }

    private fun report(g: Gathered): FirstSyncReport {
        val matching = ArrayList<FirstSyncItem>()
        val differing = ArrayList<FirstSyncItem>()
        val onlyRemote = ArrayList<FirstSyncItem>()
        val onlyLocal = ArrayList<FirstSyncItem>()
        val notInCatalog = ArrayList<FirstSyncItem>()
        val local = g.collections.keys.mapNotNull { g.index.releaseToMal[it.id] }.toSet()
        for (malId in LinkedHashSet<Int>().apply { addAll(g.remote.keys); addAll(local) }) {
            val entry = g.remote[malId]
            val remote = entry?.let { SyncSnapshot.ofRemote(it.status, it.progress) }
            if (g.index.releasesOf(malId).isEmpty()) {
                if (entry != null) {
                    notInCatalog += FirstSyncItem(
                        malId, null, entry.title ?: "MAL $malId", null, remote,
                        episodes = entry.episodes, isMovie = entry.format == "MOVIE" || entry.episodes == 1,
                    )
                }
                continue
            }
            val rid = localRelease(malId, g)
            val snap = localSnapshot(rid, g).takeIf { it.status != null }
            val item = FirstSyncItem(
                malId, rid?.id, entry?.title ?: rid?.let { "Релиз ${it.id}" } ?: "MAL $malId", snap, remote,
                episodes = entry?.episodes, isMovie = entry?.let { it.format == "MOVIE" || it.episodes == 1 } ?: false,
            )
            when (FirstSyncCompare.group(snap, remote)) {
                FirstSyncCompare.Group.MATCHING -> matching += item
                FirstSyncCompare.Group.DIFFERING -> differing += item
                FirstSyncCompare.Group.ONLY_REMOTE -> onlyRemote += item
                FirstSyncCompare.Group.ONLY_LOCAL -> onlyLocal += item
                FirstSyncCompare.Group.NONE -> Unit
            }
        }
        return FirstSyncReport(
            matching, differing, onlyRemote, onlyLocal, notInCatalog,
            noMalId = g.noMal.size, remoteTotal = g.total, createdAt = System.currentTimeMillis(),
        )
    }

    /** До первой синхронизации: только сравнение и запись в журнал. */
    private fun checkOnly(g: Gathered, manual: Boolean): PullOutcome {
        val report = report(g)
        syncState.recordStats(serviceId, report.linked, report.remoteTotal)
        journal.record(
            serviceId, JournalDirection.CHECK, null, null, "AniList",
            "${checkName(manual)} · найдено изменений: ${report.changesCount}", JournalResult.SKIPPED,
            detail = "первая синхронизация не выполнена · изменения не применялись и не отправлялись",
        )
        return PullOutcome.Done(0, 0, compareOnly = true)
    }

    private class Planned(
        val malId: Int,
        val rid: ReleaseId,
        val entry: RemoteListEntry?,
        val result: MergeResult,
    )

    private suspend fun merge(g: Gathered, manual: Boolean): PullOutcome {
        val records = ArrayList<PulledTitle>()
        val planned = ArrayList<Planned>()
        var linked = 0
        for (malId in universe(g)) {
            val entry = g.remote[malId]
            if (g.index.releasesOf(malId).isEmpty()) {
                if (entry != null) skipOnce(malId, null, entry.title ?: "MAL $malId", "нет на AniLiberty (MAL id $malId)")
                continue
            }
            if (entry != null) linked++
            val rid = localRelease(malId, g) ?: continue
            val local = localSnapshot(rid, g)
            val remote = entry?.let { SyncSnapshot.ofRemote(it.status, it.progress) } ?: SyncSnapshot.ABSENT
            val (bL, bR) = syncState.get(serviceId, malId)?.baseSnapshots(local)
                ?: (SyncSnapshot.ABSENT to SyncSnapshot.ABSENT)
            val result = ThreeWayMerge.merge(MergeInput(bL, bR, local, null, remote, entry?.updatedAt ?: 0))
            if (result.kind == MergeKind.NO_CHANGE || (!result.changesLocal && result.push == null)) {
                records += PulledTitle(malId, rid.id, result.newBaseLocal, result.newBaseRemote)
            } else {
                planned += Planned(malId, rid, entry, result)
            }
        }
        g.noMal.forEach { skipOnce(null, null, it.title ?: "AniList ${it.mediaId}", "нет MAL id — пропущено") }

        var applied = 0
        var queued = 0
        val options = settings.get(serviceId)
        for (p in planned) {
            val r = p.result
            var title = p.entry?.title ?: "MAL ${p.malId}"
            if (r.changesLocal) {
                try {
                    title = applyLocal(p) ?: title
                } catch (e: IOException) {
                    Timber.w(e, "external pull: apply failed for mal %d", p.malId)
                    journal.record(
                        serviceId, JournalDirection.IN, p.malId, p.rid.id, title,
                        "не удалось применить изменение из AniList", JournalResult.ERROR, detail = e.message,
                    )
                    continue // база не обновляется: повторим при следующем чтении
                } catch (e: Exception) {
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    Timber.w(e, "external pull: apply failed for mal %d", p.malId)
                    journal.record(
                        serviceId, JournalDirection.IN, p.malId, p.rid.id, title,
                        "не удалось применить изменение из AniList", JournalResult.ERROR, detail = e.message,
                    )
                    continue
                }
                applied++
                journal.record(
                    serviceId, JournalDirection.IN, p.malId, p.rid.id, title,
                    describeApplied(r, p.entry), JournalResult.DONE,
                    detail = if (r.kind == MergeKind.MERGED) "изменено с двух сторон · прогресс — максимум, статус — более свежий" else DETAIL_REMOTE,
                )
            }
            val push = r.push
            if (push != null && (options.sendCollection || options.sendWatched)) {
                val total = p.entry?.episodes
                outbox.enqueue(
                    serviceId, p.malId, p.rid.id, title, push.copy(totalEpisodes = total),
                    OutboxSource.MANUAL,
                )
                syncState.markPending(serviceId, p.malId, p.rid.id)
                queued++
            }
            records += PulledTitle(p.malId, p.rid.id, r.newBaseLocal, r.newBaseRemote)
        }

        syncState.recordPulled(serviceId, records, linked, g.total)
        val found = applied + queued
        journal.record(
            serviceId, JournalDirection.CHECK, null, null, "AniList",
            "${checkName(manual)} · найдено изменений: $found",
            if (found > 0) JournalResult.DONE else JournalResult.UP_TO_DATE,
            detail = if (queued > 0) "к отправке в AniList: $queued" else null,
        )
        return PullOutcome.Done(applied, queued, compareOnly = false)
    }

    private fun checkName(manual: Boolean) = if (manual) "Проверка по запросу" else "Плановая проверка"

    /** IN SKIPPED без повторов: та же запись уже есть в журнале — не дублируем. */
    private fun skipOnce(malId: Int?, releaseId: Int?, title: String, text: String) {
        val exists = journal.entries.value.any {
            it.serviceId == serviceId && it.direction == JournalDirection.IN &&
                it.result == JournalResult.SKIPPED && it.text == text && it.title == title && it.malId == malId
        }
        if (!exists) {
            journal.record(serviceId, JournalDirection.IN, malId, releaseId, title, text, JournalResult.SKIPPED)
        }
    }

    private fun describeApplied(r: MergeResult, entry: RemoteListEntry?): String {
        val status = r.setStatus
        val raise = r.raiseProgressTo
        val total = entry?.episodes
        return when {
            status == DesiredStatus.REMOVE -> "запись удалена в AniList → убрано из коллекций AniLiberty"
            status != null -> "«${status.ru}» → коллекция AniLiberty" + (raise?.let { " · отмечены серии 1–$it" } ?: "")
            raise != null -> "прогресс ${if (total != null) "$raise/$total" else raise} → отмечены серии 1–$raise"
            else -> ""
        }
    }

    /** Применяет входящее изменение к AniLiberty (под [RemoteOrigin]); возвращает название релиза, если загружено. */
    private suspend fun applyLocal(p: Planned): String? =
        applyLocalChange(p.rid, p.result.setStatus, p.result.raiseProgressTo)

    /**
     * Меняет коллекцию/прогресс релиза AniLiberty под меткой [RemoteOrigin] (в очередь отправки не
     * попадает). [setStatus]: null — не менять, [DesiredStatus.REMOVE] — убрать; [raiseProgressTo] —
     * отметить серии 1..N. Для мастера первой синхронизации. Возвращает название релиза, если загружено.
     */
    suspend fun applyLocalChange(rid: ReleaseId, setStatus: DesiredStatus?, raiseProgressTo: Int?): String? =
        withContext(RemoteOrigin) { applyLocalInner(rid, setStatus, raiseProgressTo) }

    private suspend fun applyLocalInner(rid: ReleaseId, status: DesiredStatus?, raise: Int?): String? {
        if (status == DesiredStatus.REMOVE) {
            collections.setReleaseCollection(rid, null)
            return null
        }
        if (status != null) collections.setReleaseCollection(rid, collectionOf(status))
        val becomesCompleted = status == DesiredStatus.COMPLETED
        if (raise == null && !becomesCompleted) return null
        val release = releases.getRelease(rid)
        val episodes = release.episodes.sortedBy { it.id.id.toFloatOrNull() ?: Float.MAX_VALUE }
        if (episodes.isEmpty()) return release.title
        val watched = watchProgress.currentProgress(rid)?.watched ?: 0
        val target = raise ?: 0
        if (becomesCompleted && (target == 0 || target >= episodes.size)) {
            // «Просмотрено» без числа серий (фильм) или на весь релиз
            if (watched < episodes.size) episodeProgress.markAllViewed(release)
        } else if (target > 0) {
            val accesses = episodeProgress.getAccesses(release).associateBy { it.id }
            episodes.take(target).forEach { episode ->
                val access = accesses[episode.id]
                if (access?.isViewed != true) {
                    episodeProgress.importAccess(
                        episode,
                        EpisodeAccess(episode.id, access?.seek ?: 0L, true, System.currentTimeMillis()),
                    )
                }
            }
        }
        return release.title
    }

    private fun statusOf(type: CollectionType): DesiredStatus = DesiredStatus.of(type)

    private fun collectionOf(status: DesiredStatus): CollectionType = when (status) {
        DesiredStatus.PLANNING -> CollectionType.PLANNED
        DesiredStatus.CURRENT -> CollectionType.WATCHING
        DesiredStatus.COMPLETED -> CollectionType.WATCHED
        DesiredStatus.PAUSED -> CollectionType.POSTPONED
        DesiredStatus.DROPPED -> CollectionType.ABANDONED
        DesiredStatus.REMOVE -> throw IllegalArgumentException("REMOVE has no collection")
    }
}
