package ru.radiationx.anilibria.common

import ru.radiationx.data.entity.domain.types.ReleaseId

data class LibriaCard(
    val title: String,
    val description: String,
    val image: String,
    val type: Type,
    /** Всего серий (episodes_total) — запасной знаменатель для индикатора просмотра. */
    val episodesTotal: Int? = null,
    /** Сколько серий уже вышло; null — неизвестно (догружается из полного релиза). */
    val episodesAvailable: Int? = null,
    /** Тип релиза «Фильм» — без полосы прогресса, бейдж «ФИЛЬМ». */
    val isFilm: Boolean = false,
    /** fresh_at (последнее обновление), unix-секунды; null — неизвестно. */
    val freshAt: Long? = null,
) : CardItem {

    override fun getId(): Int {
        return type.hashCode()
    }

    sealed class Type {
        data class Release(val releaseId: ReleaseId) : Type()
        data class Youtube(val link: String) : Type()
    }
}