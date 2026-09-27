package ru.radiationx.anilibria.similar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.radiationx.data.SharedBuildConfig
import ru.radiationx.data.di.providers.SimpleClientWrapper
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Сервис попросил подождать (HTTP 429); до [untilMs] запросы к нему не делаются. */
class SimilarRateLimitException(val untilMs: Long) : IOException("rate limited")

/** Страница рекомендаций AniList: [items] — MAL id + голоса (rating ≥ 1). */
data class AniListPage(
    val items: List<SimilarItem>,
    /** Рекомендаций с rating ≥ 1 на странице, включая тайтлы без MAL id. */
    val count: Int,
    /** Есть следующая страница с положительными голосами. */
    val hasNext: Boolean,
)

/**
 * Запросы к внешним сервисам «Похожих». Короткие таймауты, User-Agent с именем приложения
 * (без него Cloudflare AniList отвечает 403), свой интервал между запросами на сервис.
 */
class SimilarServicesApi @Inject constructor(
    private val clientWrapper: SimpleClientWrapper,
    buildConfig: SharedBuildConfig,
) {

    companion object {
        private const val SHIKIMORI_URL = "https://shikimori.io/api/animes/%d/similar"
        private const val ANILIST_URL = "https://graphql.anilist.co"

        /** Вложенные recommendations у Media отдают максимум 25 за страницу. */
        private const val ANILIST_NESTED_PER_PAGE = 25

        /** «Страница» строки = 2 вложенные страницы (50 рекомендаций) одним запросом через алиасы. */
        const val ANILIST_PAGE_SIZE = 50
        private const val ANILIST_PARTS = ANILIST_PAGE_SIZE / ANILIST_NESTED_PER_PAGE

        /** AniList сейчас 30 запросов/мин на IP. */
        private const val ANILIST_INTERVAL_MS = 2_100L

        /** Shikimori: 5 rps / 90 rpm. */
        private const val SHIKIMORI_INTERVAL_MS = 700L

        private val JSON = "application/json".toMediaType()
    }

    private val userAgent = "AniLibertyTV/${buildConfig.versionName}"

    private val client: OkHttpClient by lazy {
        clientWrapper.get().newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val aniList = Throttle(ANILIST_INTERVAL_MS)
    private val shikimori = Throttle(SHIKIMORI_INTERVAL_MS)

    /** Весь список Shikimori (MAL id в порядке сервиса). 404 — пустой список. */
    suspend fun getShikimoriSimilar(malId: Int): List<SimilarItem> = shikimori.run {
        val request = Request.Builder()
            .url(SHIKIMORI_URL.format(malId))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        val body = execute(request, allow404 = true) ?: return@run emptyList()
        val array = JSONArray(body)
        val seen = HashSet<Int>()
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.optInt("id")?.takeIf { it > 0 && seen.add(it) }?.let { SimilarItem(it, 0) }
        }
    }

    /**
     * Страница [page] (с 1) рекомендаций AniList по MAL id, sort RATING_DESC, только rating ≥ 1.
     * Тайтла нет на AniList — пустая страница без продолжения.
     */
    suspend fun getAniListRecommendations(malId: Int, page: Int): AniListPage = aniList.run {
        val first = (page - 1) * ANILIST_PARTS + 1
        val fields = (0 until ANILIST_PARTS).joinToString(" ") { k ->
            "p$k:Media(idMal:\$m,type:ANIME){recommendations(page:${first + k},perPage:$ANILIST_NESTED_PER_PAGE," +
                    "sort:RATING_DESC){pageInfo{hasNextPage} nodes{rating mediaRecommendation{id idMal}}}}"
        }
        val payload = JSONObject()
            .put("query", "query(\$m:Int){$fields}")
            .put("variables", JSONObject().put("m", malId))
        val request = Request.Builder()
            .url(ANILIST_URL)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        // AniList на отсутствующий Media отвечает 404 с data.p0 = null.
        val body = execute(request, allow404 = true) ?: return@run AniListPage(emptyList(), 0, false)
        val data = JSONObject(body).optJSONObject("data") ?: return@run AniListPage(emptyList(), 0, false)
        val items = ArrayList<SimilarItem>()
        val seen = HashSet<Int>()
        var count = 0
        var hasNext = true
        for (k in 0 until ANILIST_PARTS) {
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
                media.optInt("idMal").takeIf { it > 0 }?.also { items.add(SimilarItem(it, rating)) }
            }
            val more = connection.optJSONObject("pageInfo")?.optBoolean("hasNextPage") == true
            if (!more || lastRating < 1 || nodes.length() < ANILIST_NESTED_PER_PAGE) {
                hasNext = false
                break
            }
        }
        AniListPage(items, count, hasNext)
    }

    private fun execute(request: Request, allow404: Boolean): String? {
        client.newCall(request).execute().use { response ->
            if (response.code == 429) {
                val retry = response.header("Retry-After")?.toLongOrNull() ?: 60L
                throw SimilarRateLimitException(System.currentTimeMillis() + retry * 1000)
            }
            if (response.code == 404 && allow404) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${request.url.host}")
            return response.body?.string()
        }
    }

    /** Один запрос к сервису за раз, пауза между запросами, после 429 — ждать Retry-After. */
    private class Throttle(private val intervalMs: Long) {
        private val mutex = Mutex()
        private var last = 0L
        private var blockedUntil = 0L

        suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
            val now = System.currentTimeMillis()
            if (now < blockedUntil) throw SimilarRateLimitException(blockedUntil)
            val wait = last + intervalMs - now
            if (wait > 0) delay(wait)
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: SimilarRateLimitException) {
                blockedUntil = e.untilMs
                throw e
            } finally {
                last = System.currentTimeMillis()
            }
        }
    }
}
