package ru.radiationx.data.repository.watch

import ru.radiationx.data.entity.domain.release.Release

object ReleaseCardCacheLogic {

    fun fromRelease(release: Release): ReleaseCardInfo = ReleaseCardInfo(
        id = release.id.id,
        title = release.title,
        poster = release.poster,
        year = release.year,
        season = release.season,
        // Карточке нужен только первый жанр.
        genres = release.genres.take(1),
        series = release.series,
        torrentUpdate = release.torrentUpdate,
        episodesAvailable = release.episodesAvailable,
    )

    /**
     * Новый дисковый кэш: [fresh] заменяют старые записи с тем же id, сначала идут релизы
     * из [priority] (текущий ряд) в его порядке, затем остальные (свежие, потом старые).
     * Не больше [max] записей.
     */
    fun merge(
        old: List<ReleaseCardInfo>,
        fresh: List<ReleaseCardInfo>,
        priority: List<Int>,
        max: Int,
    ): List<ReleaseCardInfo> {
        val byId = LinkedHashMap<Int, ReleaseCardInfo>()
        fresh.forEach { byId[it.id] = it }
        old.forEach { if (it.id !in byId) byId[it.id] = it }
        val result = LinkedHashMap<Int, ReleaseCardInfo>()
        priority.forEach { id -> byId[id]?.also { result[id] = it } }
        byId.forEach { (id, info) -> if (id !in result) result[id] = info }
        return result.values.take(max)
    }

    /** Какие из [ids] надо обновить с сервера: ещё не обновлённые в этом процессе. */
    fun toRefresh(ids: List<Int>, refreshed: Set<Int>): List<Int> =
        ids.distinct().filter { it !in refreshed }
}
