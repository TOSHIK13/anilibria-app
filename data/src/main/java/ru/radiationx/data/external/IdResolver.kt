package ru.radiationx.data.external

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import ru.radiationx.data.ApiClient
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** Каталог AniLiberty: MAL id → release id (несколько релизов могут делить один MAL id). */
class CatalogIndex(
    val builtAt: Long,
    val releaseToMal: Map<Int, Int>,
) {
    private val malToReleases: Map<Int, List<Int>> = releaseToMal.entries
        .groupBy({ it.value }, { it.key })
        .mapValues { (_, ids) -> ids.sorted() }

    val size: Int get() = releaseToMal.size

    fun releasesOf(malId: Int): List<Int> = malToReleases[malId].orEmpty()

    /**
     * Сырой список сервиса (MAL id) → релизы каталога в порядке сервиса.
     * В ряд идут все релизы с этим MAL id (отдельные релизы — части/сборники); сам релиз
     * и релизы с его же MAL id исключаются, дубликаты убираются.
     */
    fun map(releaseId: Int, malId: Int?, raw: List<ExternalLink>): List<Pair<Int, Int>> {
        val own = malId?.let { releasesOf(it).toSet() }.orEmpty()
        val seen = HashSet<Int>()
        val result = ArrayList<Pair<Int, Int>>()
        raw.forEach { item ->
            if (item.malId == malId) return@forEach
            releasesOf(item.malId).forEach { id ->
                if (id != releaseId && id !in own && seen.add(id)) result.add(id to item.weight)
            }
        }
        return result
    }
}

/**
 * Сопоставление MAL id ↔ release id AniLiberty по индексу каталога
 * (`catalog/releases?include=id,shikimori.id,mal.id`
 * ~39 страниц по 50, до 4 параллельно), файл в filesDir, TTL 24 ч.
 * Строится в фоне при первом открытии деталей; пока его нет, ряды берутся из кеша.
 */
class IdResolver @Inject constructor(
    context: Context,
    @ApiClient private val client: IClient,
    private val apiConfig: ApiConfig,
) {

    private companion object {
        const val TTL_MS = 24 * 60 * 60 * 1000L
        const val RETRY_AFTER_ERROR_MS = 10 * 60 * 1000L
        const val PAGE_LIMIT = 50
        const val PARALLEL = 4
        const val PAGE_TIMEOUT_MS = 15_000L
        const val FIELDS = "id,shikimori.id,mal.id"
    }

    private val file = File(context.filesDir, "similar/catalog_index.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<CatalogIndex?>(null)
    private val running = AtomicBoolean(false)
    private var diskLoaded = false
    private var lastError = 0L

    val index: StateFlow<CatalogIndex?> = state

    /** Release id каталога с этим MAL id (пусто, пока индекса нет). */
    fun releaseIdsByMalId(malId: Int): List<Int> = state.value?.releasesOf(malId).orEmpty()

    /** MAL id релиза по каталогу (null, если нет индекса или у релиза нет MAL). */
    fun malIdByReleaseId(releaseId: Int): Int? = state.value?.releaseToMal?.get(releaseId)

    /** Поднять индекс с диска и, если он устарел или его нет, перестроить в фоне. */
    fun ensure() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (!diskLoaded) {
                    diskLoaded = true
                    readDisk()?.also { state.value = it }
                }
                val now = System.currentTimeMillis()
                val current = state.value
                val fresh = current != null && now - current.builtAt < TTL_MS
                if (!fresh && now - lastError > RETRY_AFTER_ERROR_MS) {
                    coRunCatching { build() }
                        .onSuccess {
                            state.value = it
                            writeDisk(it)
                        }
                        .onFailure {
                            lastError = System.currentTimeMillis()
                            Timber.w(it, "idresolver: catalog index build failed")
                        }
                }
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun build(): CatalogIndex {
        val start = System.currentTimeMillis()
        val first = loadPage(1)
        val totalPages = first.getJSONObject("meta").getJSONObject("pagination").getInt("total_pages")
        val result = HashMap<Int, Int>()
        parse(first, result)
        val semaphore = Semaphore(PARALLEL)
        val pages = coroutineScope {
            (2..totalPages).map { page -> async { semaphore.withPermit { loadPage(page) } } }.awaitAll()
        }
        pages.forEach { parse(it, result) }
        val index = CatalogIndex(System.currentTimeMillis(), result)
        Timber.d("idresolver: catalog index %d releases, %d pages, %d ms",
            index.size, totalPages, System.currentTimeMillis() - start)
        return index
    }

    private suspend fun loadPage(page: Int): JSONObject {
        val args = mapOf(
            "limit" to PAGE_LIMIT.toString(),
            "page" to page.toString(),
            "include" to FIELDS,
        )
        val url = "${apiConfig.animeBaseUrl}/api/v1/anime/catalog/releases"
        // Одна повторная попытка: страница из 39 не должна ронять весь индекс.
        return coRunCatching { withTimeout(PAGE_TIMEOUT_MS) { JSONObject(client.get(url, args)) } }
            .getOrElse { withTimeout(PAGE_TIMEOUT_MS) { JSONObject(client.get(url, args)) } }
    }

    private fun parse(page: JSONObject, into: MutableMap<Int, Int>) {
        val data = page.getJSONArray("data")
        for (i in 0 until data.length()) {
            val release = data.getJSONObject(i)
            val id = release.optInt("id")
            val mal = release.optJSONObject("mal")?.optInt("id")?.takeIf { it > 0 }
                ?: release.optJSONObject("shikimori")?.optInt("id")?.takeIf { it > 0 }
            if (id > 0 && mal != null) into[id] = mal
        }
    }

    private fun readDisk(): CatalogIndex? = try {
        if (!file.exists()) null else {
            val json = JSONObject(file.readText())
            val items = json.getJSONArray("items")
            val map = HashMap<Int, Int>(items.length())
            for (i in 0 until items.length()) {
                val pair = items.getJSONArray(i)
                map[pair.getInt(0)] = pair.getInt(1)
            }
            CatalogIndex(json.getLong("built_at"), map)
        }
    } catch (e: Exception) {
        Timber.w(e, "idresolver: bad catalog index file")
        null
    }

    private fun writeDisk(index: CatalogIndex) {
        try {
            file.parentFile?.mkdirs()
            val items = JSONArray()
            index.releaseToMal.forEach { (id, mal) -> items.put(JSONArray().put(id).put(mal)) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONObject().put("built_at", index.builtAt).put("items", items).toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Timber.w(e, "idresolver: write catalog index")
        }
    }
}
