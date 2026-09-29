package ru.radiationx.data.external

import org.json.JSONObject
import javax.inject.Inject

/**
 * Список пользователя AniList целиком: `MediaListCollection` пачками по 500 записей (обычно одна
 * пачка). Записи, попавшие в несколько списков (пользовательские), берутся один раз. Media
 * кешируется в [AniListMediaLookup], чтобы отправка не делала лишних запросов.
 */
class AniListUserList @Inject constructor(
    private val service: AniListService,
    private val store: ExternalTokenStore,
    private val lookup: AniListMediaLookup,
) : UserListSource {

    override val id = AniListService.ID
    override val title = "AniList"

    override suspend fun fetchUserList(): List<RemoteListEntry> {
        val viewerId = store.get(id)?.viewerId?.takeIf { it > 0 } ?: throw ExternalHttpException(401, "AniList: нет viewerId")
        val result = LinkedHashMap<Long, RemoteListEntry>()
        var chunk = 1
        while (true) {
            val vars = JSONObject().put("u", viewerId).put("c", chunk)
            val response = service.graphQl.query(QUERY, vars) ?: break
            val errors = response.optJSONArray("errors")
            val collection = response.optJSONObject("data")?.optJSONObject("MediaListCollection")
            if (collection == null) {
                if (errors != null && errors.length() > 0) {
                    val e = errors.optJSONObject(0)
                    throw ExternalHttpException(e?.optInt("status", 400) ?: 400, "AniList: ${e?.optString("message").orEmpty()}")
                }
                break
            }
            val lists = collection.optJSONArray("lists")
            for (i in 0 until (lists?.length() ?: 0)) {
                val entries = lists!!.getJSONObject(i).optJSONArray("entries") ?: continue
                for (k in 0 until entries.length()) {
                    parse(entries.getJSONObject(k))?.also { result.putIfAbsent(it.externalId, it) }
                }
            }
            if (!collection.optBoolean("hasNextChunk")) break
            chunk++
        }
        return result.values.toList()
    }

    private fun parse(o: JSONObject): RemoteListEntry? {
        val media = o.optJSONObject("media") ?: return null
        val mediaId = media.optLong("id")
        val malId = media.optInt("idMal").takeIf { it > 0 }
        val episodes = media.optInt("episodes", 0).takeIf { it > 0 }
        val format = media.optString("format").takeIf { it.isNotEmpty() && it != "null" }
        if (malId != null && mediaId > 0) lookup.remember(malId, MediaInfo(mediaId, episodes, format))
        return RemoteListEntry(
            malId = malId,
            externalId = o.optLong("id"),
            status = o.optString("status"),
            progress = o.optInt("progress"),
            score = o.optInt("score").takeIf { it > 0 }?.toDouble(),
            updatedAt = o.optLong("updatedAt") * 1000,
            mediaId = mediaId,
            title = media.optJSONObject("title")?.optString("romaji")?.takeIf { it.isNotEmpty() && it != "null" },
            episodes = episodes,
            format = format,
        )
    }

    private companion object {
        const val QUERY = "query(\$u:Int,\$c:Int){MediaListCollection(userId:\$u,type:ANIME,chunk:\$c,perChunk:500)" +
                "{hasNextChunk lists{entries{id status progress updatedAt score(format:POINT_10) media{id idMal episodes format title{romaji}}}}}}"
    }
}
