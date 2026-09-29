package ru.radiationx.anilibria.similar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.external.ExternalLink
import ru.radiationx.data.external.ExternalServiceRegistry
import ru.radiationx.data.external.IdResolver
import ru.radiationx.data.external.SimilarPage
import ru.radiationx.data.external.SimilarProvider
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

/**
 * «Похожие» напрямую с сервисов, с кешем на диске ([SimilarCacheStorage], TTL 7 дней).
 *
 * - сразу отдаёт кеш (даже устаревший), в фоне обновляет, если он старше [TTL_MS];
 * - Shikimori: один запрос — весь список; AniList: только первая страница (50, rating ≥ 1),
 *   следующие — [loadMore], когда фокус подходит к концу ряда;
 * - MAL id → release id по [IdResolver]; пока индекса нет, показываются ранее
 *   сопоставленные ряды, после построения индекса ряды пересобираются из сырых списков;
 * - ошибки сети молча: остаётся кеш, либо ряда нет.
 */
class LiveSimilarReleasesSource @Inject constructor(
    private val storage: SimilarCacheStorage,
    private val idResolver: IdResolver,
    private val registry: ExternalServiceRegistry,
) : SimilarReleasesSource {

    private companion object {
        const val TTL_MS = 7 * 24 * 60 * 60 * 1000L
        const val MAX_ENTRIES = 16
    }

    private class Entry(val releaseId: Int, val malId: Int) {
        val mutex = Mutex()
        val state = MutableStateFlow<SimilarCacheItem?>(null)
        var diskLoaded = false
    }

    private val entries = object : LinkedHashMap<Int, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    override fun observe(releaseId: ReleaseId, malId: Int): Flow<SimilarData> = channelFlow {
        idResolver.ensure()
        val entry = entry(releaseId.id, malId)
        launch {
            entry.mutex.withLock { loadDisk(entry) }
            refreshIfStale(entry)
        }
        // Индекс появился / обновился — пересобрать ряды из сырых списков.
        launch {
            idResolver.index.filterNotNull().collect { index ->
                entry.mutex.withLock {
                    loadDisk(entry)
                    val item = entry.state.value ?: return@withLock
                    if (item.indexBuiltAt != index.builtAt && item.raw.isNotEmpty()) {
                        save(entry, remap(entry, item))
                    }
                }
            }
        }
        entry.state
            .filterNotNull()
            .map { it.toData() }
            .distinctUntilChanged()
            .collect { send(it) }
    }.flowOn(Dispatchers.IO)

    override suspend fun loadMore(releaseId: ReleaseId, source: SimilarSource): Boolean {
        if (source != SimilarSource.ANILIST) return false
        val entry = synchronized(entries) { entries[releaseId.id] } ?: return false
        return entry.mutex.withLock {
            val item = entry.state.value ?: return@withLock false
            val page = item.alNextPage.takeIf { it > 0 } ?: return@withLock false
            coRunCatching { fetch(SimilarSource.ANILIST, entry.malId, page) }
                .onFailure { Timber.w(it, "similar: anilist page $page for ${entry.malId}") }
                .map { result ->
                    val old = item.raw[SimilarSource.ANILIST].orEmpty()
                    val known = old.mapTo(HashSet()) { it.id }
                    val merged = old + result.links.map { SimilarItem(it.malId, it.weight) }.filter { known.add(it.id) }
                    save(
                        entry, remap(
                            entry, item.copy(
                                raw = item.raw + (SimilarSource.ANILIST to merged),
                                totals = item.totals + (SimilarSource.ANILIST to
                                        (item.totals[SimilarSource.ANILIST] ?: 0) + result.count),
                                alNextPage = if (result.hasNext) page + 1 else 0,
                            )
                        )
                    )
                    true
                }
                .getOrDefault(false)
        }
    }

    private fun entry(releaseId: Int, malId: Int): Entry = synchronized(entries) {
        entries[releaseId]?.takeIf { it.malId == malId }
            ?: Entry(releaseId, malId).also { entries[releaseId] = it }
    }

    private fun loadDisk(entry: Entry) {
        if (entry.diskLoaded) return
        entry.diskLoaded = true
        val item = storage.read(entry.releaseId)
            ?.takeIf { it.malId == null || it.malId == entry.malId }
            ?: return
        val index = idResolver.index.value
        entry.state.value = if (index != null && item.indexBuiltAt != index.builtAt && item.raw.isNotEmpty()) {
            remap(entry, item).also { storage.write(entry.releaseId, it) }
        } else {
            item
        }
    }

    private suspend fun refreshIfStale(entry: Entry) = entry.mutex.withLock {
        val old = entry.state.value
        val now = System.currentTimeMillis()
        if (old != null && now - old.fetchedAt < TTL_MS) return@withLock
        val (shikimori, aniList) = coroutineScope {
            val sh = async { coRunCatching { fetch(SimilarSource.SHIKIMORI, entry.malId, 1) } }
            val al = async { coRunCatching { fetch(SimilarSource.ANILIST, entry.malId, 1) } }
            sh.await() to al.await()
        }
        shikimori.exceptionOrNull()?.also { Timber.w(it, "similar: shikimori ${entry.malId}") }
        aniList.exceptionOrNull()?.also { Timber.w(it, "similar: anilist ${entry.malId}") }
        val sh = shikimori.getOrNull()
        val al = aniList.getOrNull()
        if (sh == null && al == null) return@withLock

        val raw = HashMap(old?.raw.orEmpty())
        val totals = HashMap(old?.totals.orEmpty())
        sh?.also {
            raw[SimilarSource.SHIKIMORI] = it.links.map { l -> SimilarItem(l.malId, l.weight) }
            totals[SimilarSource.SHIKIMORI] = it.count
        }
        al?.also {
            raw[SimilarSource.ANILIST] = it.links.map { l -> SimilarItem(l.malId, l.weight) }
            totals[SimilarSource.ANILIST] = it.count
        }
        val item = SimilarCacheItem(
            mapped = old?.mapped.orEmpty(),
            totals = totals,
            // Частичный успех не продлевает кеш: упавший сервис спросим при следующем открытии.
            fetchedAt = if (sh != null && al != null) now else old?.fetchedAt ?: 0L,
            malId = entry.malId,
            raw = raw,
            alNextPage = if (al != null) (if (al.hasNext) 2 else 0) else old?.alNextPage ?: 0,
            indexBuiltAt = 0,
        )
        save(entry, remap(entry, item))
    }

    private suspend fun fetch(source: SimilarSource, malId: Int, page: Int): SimilarPage {
        val provider = registry.withCapability<SimilarProvider>(source.serviceId.orEmpty())
            ?: error("no similar provider for $source")
        return provider.similar(malId, page)
    }

    /** Сопоставить сырые списки с каталогом; без индекса — оставить прежние ряды. */
    private fun remap(entry: Entry, item: SimilarCacheItem): SimilarCacheItem {
        val index = idResolver.index.value ?: return item
        return item.copy(
            mapped = item.mapped + item.raw.mapValues { (_, list) ->
                index.map(entry.releaseId, entry.malId, list.map { ExternalLink(it.id, weight = it.weight) })
                    .map { (id, weight) -> SimilarItem(id, weight) }
            },
            indexBuiltAt = index.builtAt,
        )
    }

    private fun save(entry: Entry, item: SimilarCacheItem) {
        entry.state.value = item
        storage.write(entry.releaseId, item)
    }
}
