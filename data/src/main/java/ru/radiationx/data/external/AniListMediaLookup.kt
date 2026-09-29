package ru.radiationx.data.external

import android.content.Context
import org.json.JSONObject
import javax.inject.Inject

sealed class MediaLookupResult {
    /** [entry] — запись списка пользователя (null — тайтла нет в списке). */
    data class Found(val media: MediaInfo, val entry: RemoteEntry?) : MediaLookupResult()

    /** Тайтла с таким MAL id нет на AniList. */
    object NotFound : MediaLookupResult()
}

/**
 * Сопоставление MAL id → AniList Media и чтение/запись одной записи списка. Один GraphQL-запрос
 * на чтение, одна мутация на запись; запросы идут через [AniListService.http] (лимит, 429, токен).
 * Соответствие malId → {id, episodes, format} кешируется на диск навсегда, запись списка — нет.
 */
class AniListMediaLookup @Inject constructor(
    context: Context,
    private val service: AniListService,
) {

    private val cache = ExternalDiskCache(context, "anilist/media", maxItems = 20_000)

    /** Ранее найденные данные тайтла без сети. */
    fun cached(malId: Int): MediaInfo? = cache.read(malId.toString())?.let { json ->
        MediaInfo(
            id = json.optLong("id"),
            episodes = json.optInt("episodes", 0).takeIf { it > 0 },
            format = json.optString("format").takeIf { it.isNotEmpty() },
        )
    }

    /** Запоминает данные тайтла, полученные из списка пользователя (без отдельного запроса). */
    fun remember(malId: Int, info: MediaInfo) {
        cache.write(
            malId.toString(),
            JSONObject().put("id", info.id).put("episodes", info.episodes ?: 0).put("format", info.format.orEmpty())
        )
    }

    suspend fun lookup(malId: Int): MediaLookupResult {
        val response = service.graphQl.query(LOOKUP, JSONObject().put("m", malId)) ?: return MediaLookupResult.NotFound
        val media = data(response)?.optJSONObject("Media") ?: return MediaLookupResult.NotFound
        val info = MediaInfo(
            id = media.getLong("id"),
            episodes = media.optInt("episodes", 0).takeIf { it > 0 },
            format = media.optString("format").takeIf { it.isNotEmpty() && it != "null" },
        )
        cache.write(
            malId.toString(),
            JSONObject().put("id", info.id).put("episodes", info.episodes ?: 0).put("format", info.format.orEmpty())
        )
        return MediaLookupResult.Found(info, media.optJSONObject("mediaListEntry")?.let(::entry))
    }

    suspend fun save(mediaId: Long, status: String, progress: Int): RemoteEntry {
        val vars = JSONObject().put("m", mediaId).put("s", status).put("p", progress)
        val response = service.graphQl.query(SAVE, vars) ?: throw ExternalHttpException(404, "AniList: not found")
        val saved = data(response)?.optJSONObject("SaveMediaListEntry")
            ?: throw ExternalHttpException(200, "AniList: пустой ответ")
        return entry(saved)
    }

    suspend fun delete(entryId: Long) {
        val response = service.graphQl.query(DELETE, JSONObject().put("id", entryId))
        data(response ?: return)
    }

    /** `data` ответа; GraphQL-ошибки без данных превращаются в исключение. */
    private fun data(response: JSONObject): JSONObject? {
        val data = response.optJSONObject("data")
        val errors = response.optJSONArray("errors")
        if (data == null && errors != null && errors.length() > 0) {
            val message = errors.optJSONObject(0)?.optString("message").orEmpty()
            throw ExternalHttpException(errors.optJSONObject(0)?.optInt("status", 400) ?: 400, "AniList: $message")
        }
        return data
    }

    private fun entry(o: JSONObject) = RemoteEntry(
        id = o.getLong("id"),
        status = o.optString("status"),
        progress = o.optInt("progress"),
        updatedAtSec = o.optLong("updatedAt"),
    )

    private companion object {
        const val LOOKUP = "query(\$m:Int){Media(idMal:\$m,type:ANIME){id episodes format title{romaji} " +
                "mediaListEntry{id status progress updatedAt}}}"
        const val SAVE = "mutation(\$m:Int,\$s:MediaListStatus,\$p:Int){SaveMediaListEntry(mediaId:\$m,status:\$s,progress:\$p)" +
                "{id status progress updatedAt}}"
        const val DELETE = "mutation(\$id:Int){DeleteMediaListEntry(id:\$id){deleted}}"
    }
}
