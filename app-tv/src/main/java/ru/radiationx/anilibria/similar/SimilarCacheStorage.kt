package ru.radiationx.anilibria.similar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Запись кеша «Похожих» одного релиза.
 *
 * На диске — item будущего similar.json плюс служебные поля живого источника:
 * `{"al":[[release_id,votes],..],"al_total":N,"sh":[release_id,..],"sh_total":N,
 *   "mal":[[release_id,n],..],"mal_total":N,"fetched_at":<ms>,
 *   "mal_id":X,"al_src":[[mal_id,votes],..],"sh_src":[mal_id,..],"al_next":<page>,"index_at":<ms>}`.
 *
 * [mapped] — уже сопоставленные с каталогом release id, [raw] — ответ сервиса в MAL id
 * (по нему ряды пересобираются, когда появляется / обновляется индекс каталога).
 * [alNextPage] — с какой страницы AniList продолжать догрузку (0 — список полный).
 */
data class SimilarCacheItem(
    val mapped: Map<SimilarSource, List<SimilarItem>>,
    val totals: Map<SimilarSource, Int>,
    val fetchedAt: Long,
    val malId: Int? = null,
    val raw: Map<SimilarSource, List<SimilarItem>> = emptyMap(),
    val alNextPage: Int = 0,
    val indexBuiltAt: Long = 0,
) {
    fun toData(): SimilarData = SimilarData(
        lists = mapped
            .filterValues { it.isNotEmpty() }
            .mapValues { (source, items) ->
                SimilarList(
                    items = items,
                    total = maxOf(totals[source] ?: 0, items.size),
                    hasMore = source == SimilarSource.ANILIST && alNextPage > 0,
                )
            },
        fetchedAt = fetchedAt,
    )
}

/** Кеш по releaseId: файл на релиз в filesDir, LRU по времени доступа, не больше [MAX_ITEMS]. */
class SimilarCacheStorage @Inject constructor(
    context: Context,
) {

    private companion object {
        const val MAX_ITEMS = 500
        const val DIR = "similar/items"
    }

    private val dir = File(context.filesDir, DIR)
    private val lock = Any()

    fun read(releaseId: Int): SimilarCacheItem? = synchronized(lock) {
        val file = File(dir, "$releaseId.json")
        if (!file.exists()) return null
        try {
            val item = decode(JSONObject(file.readText()))
            file.setLastModified(System.currentTimeMillis())
            item
        } catch (e: Exception) {
            Timber.w(e, "similar cache: bad file $releaseId")
            file.delete()
            null
        }
    }

    fun write(releaseId: Int, item: SimilarCacheItem) = synchronized(lock) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$releaseId.json.tmp")
            tmp.writeText(encode(item).toString())
            val file = File(dir, "$releaseId.json")
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            prune()
        } catch (e: Exception) {
            Timber.w(e, "similar cache: write $releaseId")
        }
    }

    /** Размер кеша на диске (для отладки/отчёта): файлов, байт. */
    fun stats(): Pair<Int, Long> = synchronized(lock) {
        val files = dir.listFiles().orEmpty()
        files.size to files.sumOf { it.length() }
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        if (files.size <= MAX_ITEMS) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_ITEMS)
            .forEach { it.delete() }
    }

    private fun encode(item: SimilarCacheItem): JSONObject = JSONObject().apply {
        SimilarSource.values().forEach { source ->
            val list = item.mapped[source] ?: return@forEach
            put(source.key, list.toJson(weighted = source != SimilarSource.SHIKIMORI))
            put(source.key + "_total", item.totals[source] ?: list.size)
        }
        put("fetched_at", item.fetchedAt)
        item.malId?.also { put("mal_id", it) }
        item.raw.forEach { (source, list) ->
            put(source.key + "_src", list.toJson(weighted = source != SimilarSource.SHIKIMORI))
        }
        if (item.alNextPage > 0) put("al_next", item.alNextPage)
        if (item.indexBuiltAt > 0) put("index_at", item.indexBuiltAt)
    }

    private fun decode(json: JSONObject): SimilarCacheItem {
        val mapped = HashMap<SimilarSource, List<SimilarItem>>()
        val totals = HashMap<SimilarSource, Int>()
        val raw = HashMap<SimilarSource, List<SimilarItem>>()
        SimilarSource.values().forEach { source ->
            json.optJSONArray(source.key)?.also { mapped[source] = it.toItems() }
            if (json.has(source.key + "_total")) totals[source] = json.getInt(source.key + "_total")
            json.optJSONArray(source.key + "_src")?.also { raw[source] = it.toItems() }
        }
        return SimilarCacheItem(
            mapped = mapped,
            totals = totals,
            fetchedAt = json.optLong("fetched_at"),
            malId = json.optInt("mal_id").takeIf { it > 0 },
            raw = raw,
            alNextPage = json.optInt("al_next"),
            indexBuiltAt = json.optLong("index_at"),
        )
    }

    private fun List<SimilarItem>.toJson(weighted: Boolean): JSONArray = JSONArray().also { array ->
        forEach { item ->
            array.put(if (weighted) JSONArray().put(item.id).put(item.weight) else item.id)
        }
    }

    /** Элемент — `id` или `[id, weight]`. */
    private fun JSONArray.toItems(): List<SimilarItem> = (0 until length()).map { i ->
        val value = get(i)
        if (value is JSONArray) SimilarItem(value.getInt(0), value.optInt(1)) else SimilarItem(getInt(i), 0)
    }
}
