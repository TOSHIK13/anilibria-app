package ru.radiationx.anilibria.similar

import kotlinx.coroutines.flow.Flow
import ru.radiationx.data.entity.domain.types.ReleaseId

/** Сервис-источник «Похожих»: ключ в кеше / similar.json и подпись ряда. */
enum class SimilarSource(val key: String, val title: String) {
    ANILIST("al", "AniList"),
    SHIKIMORI("sh", "Shikimori"),

    /** Заготовка: нужен X-MAL-CLIENT-ID, живой источник MAL пока не опрашивает. */
    MAL("mal", "MAL"),
}

/**
 * Один похожий тайтл. [id] — release id AniLiberty (в сыром ответе сервиса — MAL id),
 * [weight] — вес сервиса (голоса AniList, num_recommendations MAL; у Shikimori 0 — только порядок).
 */
data class SimilarItem(
    val id: Int,
    val weight: Int,
)

/**
 * Ряд сервиса: [items] — совпадения с каталогом AniLiberty в порядке сервиса,
 * [total] — сколько тайтлов вернул сервис всего (в т.ч. вне каталога),
 * [hasMore] — у сервиса есть следующая страница (AniList), её грузит [SimilarReleasesSource.loadMore].
 */
data class SimilarList(
    val items: List<SimilarItem>,
    val total: Int,
    val hasMore: Boolean = false,
)

/** Похожие релиза по всем сервисам; [fetchedAt] — когда данные получены (epoch ms). */
data class SimilarData(
    val lists: Map<SimilarSource, SimilarList>,
    val fetchedAt: Long,
)

/**
 * Источник «Похожих» для экрана деталей.
 *
 * Сейчас — [LiveSimilarReleasesSource] (ТВ сам спрашивает сервисы, кеш на диске).
 * Позже сборку можно перенести на GitHub (scripts/reco/build_similar.py): тогда источник
 * будет просто заполнять тот же кеш ([SimilarCacheStorage], формат item из similar.json),
 * UI и [SimilarReleasesRepository] не меняются.
 *
 * Flow, а не одно значение: сначала кеш, затем свежие данные и страницы, догруженные [loadMore].
 */
interface SimilarReleasesSource {
    fun observe(releaseId: ReleaseId, malId: Int): Flow<SimilarData>

    /**
     * Следующая страница сервиса (сейчас только AniList) — по требованию, когда фокус
     * подходит к концу ряда. Результат приходит в [observe]. false — нечего/нельзя грузить.
     */
    suspend fun loadMore(releaseId: ReleaseId, source: SimilarSource): Boolean
}
