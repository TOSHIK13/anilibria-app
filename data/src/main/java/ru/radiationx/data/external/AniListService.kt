package ru.radiationx.data.external

import org.json.JSONObject
import ru.radiationx.data.SharedBuildConfig
import ru.radiationx.data.di.providers.SimpleClientWrapper
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Тонкая обёртка над [ExternalHttpClient] для GraphQL AniList. */
class AniListGraphQl(private val http: ExternalHttpClient) {

    /** Ответ целиком (с `data`) или null, если AniList ответил 404 (нет Media). */
    suspend fun query(query: String, variables: JSONObject, token: String? = null): JSONObject? {
        val payload = JSONObject().put("query", query).put("variables", variables)
        return http.postJson(URL, payload.toString(), allow404 = true, token = token)?.let { JSONObject(it) }
    }

    private companion object {
        const val URL = "https://graphql.anilist.co"
    }
}

class AniListService @Inject constructor(
    clientWrapper: SimpleClientWrapper,
    buildConfig: SharedBuildConfig,
    private val tokenStore: ExternalTokenStore,
) : SimilarProvider {

    companion object {
        const val ID = "anilist"

        /** Вложенные recommendations у Media отдают максимум 25 за страницу. */
        private const val NESTED_PER_PAGE = 25

        /** «Страница» = 2 вложенные страницы (50 рекомендаций) одним запросом через алиасы. */
        private const val PAGE_SIZE = 50
        private const val PARTS = PAGE_SIZE / NESTED_PER_PAGE

        /** AniList: ~30 запросов/мин на IP. */
        private const val INTERVAL_MS = 2_100L
    }

    override val id = ID
    override val title = "AniList"

    val http = ExternalHttpClient(
        tag = "anilist",
        clientProvider = {
            clientWrapper.get().newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
        },
        minIntervalMs = INTERVAL_MS,
        userAgent = "AniLibertyTV/${buildConfig.versionName}",
        tokenProvider = { tokenStore.activeToken(ID) },
        onUnauthorized = { tokenStore.markRevoked(ID) },
    )

    val graphQl = AniListGraphQl(http)

    /** Рекомендации по MAL id, sort RATING_DESC, только rating >= 1. */
    override suspend fun similar(malId: Int, page: Int): SimilarPage {
        val empty = SimilarPage(emptyList(), 0, false)
        val first = (page - 1) * PARTS + 1
        val fields = (0 until PARTS).joinToString(" ") { k ->
            "p$k:Media(idMal:\$m,type:ANIME){recommendations(page:${first + k},perPage:$NESTED_PER_PAGE," +
                    "sort:RATING_DESC){pageInfo{hasNextPage} nodes{rating mediaRecommendation{id idMal}}}}"
        }
        val response = graphQl.query("query(\$m:Int){$fields}", JSONObject().put("m", malId))
            ?: return empty
        val data = response.optJSONObject("data") ?: return empty
        val items = ArrayList<ExternalLink>()
        val seen = HashSet<Int>()
        var count = 0
        var hasNext = true
        for (k in 0 until PARTS) {
            val connection = data.optJSONObject("p$k")?.optJSONObject("recommendations")
            val nodes = connection?.optJSONArray("nodes")
            if (connection == null || nodes == null || nodes.length() == 0) {
                hasNext = false
                break
            }
            var lastRating = 0
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                val rating = node.optInt("rating")
                lastRating = rating
                val media = node.optJSONObject("mediaRecommendation") ?: continue
                if (rating < 1 || !seen.add(media.optInt("id"))) continue
                count++
                media.optInt("idMal").takeIf { it > 0 }?.also { items.add(ExternalLink(it, null, rating)) }
            }
            val more = connection.optJSONObject("pageInfo")?.optBoolean("hasNextPage") == true
            if (!more || lastRating < 1 || nodes.length() < NESTED_PER_PAGE) {
                hasNext = false
                break
            }
        }
        return SimilarPage(items, count, hasNext)
    }
}
