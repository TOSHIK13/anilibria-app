package ru.radiationx.data.external

import org.json.JSONArray
import ru.radiationx.data.SharedBuildConfig
import ru.radiationx.data.di.providers.SimpleClientWrapper
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ShikimoriService @Inject constructor(
    clientWrapper: SimpleClientWrapper,
    buildConfig: SharedBuildConfig,
) : SimilarProvider {

    private companion object {
        const val URL = "https://shikimori.io/api/animes/%d/similar"

        /** Shikimori: 5 rps / 90 rpm. */
        const val INTERVAL_MS = 700L
    }

    override val id = "shikimori"
    override val title = "Shikimori"

    val http = ExternalHttpClient(
        tag = "shikimori",
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
    )

    /** Весь список одним запросом (MAL id в порядке сервиса); 404 — пусто. Есть только страница 1. */
    override suspend fun similar(malId: Int, page: Int): SimilarPage {
        val empty = SimilarPage(emptyList(), 0, false)
        if (page > 1) return empty
        val body = http.get(URL.format(malId), allow404 = true) ?: return empty
        val array = JSONArray(body)
        val seen = HashSet<Int>()
        val links = (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.optInt("id")?.takeIf { it > 0 && seen.add(it) }?.let { ExternalLink(it) }
        }
        return SimilarPage(links, links.size, false)
    }
}
