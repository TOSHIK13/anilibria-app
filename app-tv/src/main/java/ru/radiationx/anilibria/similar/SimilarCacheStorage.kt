package ru.radiationx.anilibria.similar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.radiationx.data.external.ExternalDiskCache
import timber.log.Timber
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

/** Кеш «Похожих» по releaseId поверх [ExternalDiskCache] (filesDir/similar/items, LRU 500). */
class SimilarCacheStorage @Inject constructor(
    context: Context,
) {

    private val cache = ExternalDiskCache(context, "similar/items", maxItems = 500)

    fun read(releaseId: Int): SimilarCacheItem? {
        val json = cache.read(releaseId.toString()) ?: return null
        return try {
            decode(json)
        } catch (e: Exception) {
            Timber.w(e, "similar cache: bad file $releaseId")
            cache.delete(releaseId.toString())
            null
        }
    }

    fun write(releaseId: Int, item: SimilarCacheItem) {
        cache.write(releaseId.toString(), encode(item))
    }

    /** Размер кеша на диске (для отладки/отчёта): файлов, байт. */
    fun stats(): Pair<Int, Long> = cache.stats()

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
