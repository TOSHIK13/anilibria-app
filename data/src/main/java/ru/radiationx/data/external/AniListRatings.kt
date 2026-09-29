package ru.radiationx.data.external

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Оценки пользователя AniList (шкала 1..10) для UI. Чтение — `score(format: POINT_10)`, запись —
 * `scoreRaw` (score * 10), поэтому настройки формата оценок аккаунта не затрагиваются.
 * Запись идёт напрямую (не через outbox): результат сразу виден пользователю, ошибка — исключение.
 */
class AniListRatings @Inject constructor(
    private val store: ExternalTokenStore,
    private val lookup: AniListMediaLookup,
    private val idResolver: IdResolver,
) {

    /** malId -> оценка (0 — нет оценки / нет записи). Живёт до конца процесса. */
    private val scores = ConcurrentHashMap<Int, Int>()

    fun isLinked(): Boolean = store.activeToken(AniListService.ID) != null

    /** MAL id релиза по индексу каталога (null, пока индекса нет или у релиза нет MAL). */
    fun malIdOf(releaseId: Int): Int? = idResolver.malIdByReleaseId(releaseId)

    /** Оценка 1..10; null — не вошли, нет записи или оценка не выставлена. */
    suspend fun getScore(malId: Int, refresh: Boolean = false): Int? {
        if (!isLinked()) return null
        if (!refresh) scores[malId]?.let { return it.takeIf { s -> s > 0 } }
        val found = lookup.lookup(malId) as? MediaLookupResult.Found ?: return null
        val score = found.entry?.score ?: 0
        scores[malId] = score
        return score.takeIf { it > 0 }
    }

    /** Сохраняет оценку 1..10 (0 — снять). Запись создаётся, если её не было. Бросает при ошибке сети/входа. */
    suspend fun setScore(malId: Int, score: Int) {
        if (!isLinked()) throw ExternalHttpException(401, "AniList: вход не выполнен")
        val value = score.coerceIn(0, 10)
        val mediaId = lookup.cached(malId)?.id
            ?: (lookup.lookup(malId) as? MediaLookupResult.Found)?.media?.id
            ?: throw ExternalHttpException(404, "AniList: тайтл не найден")
        val saved = lookup.saveScore(mediaId, value)
        scores[malId] = saved.score
    }
}
