package ru.radiationx.anilibria.common

import ru.radiationx.data.entity.domain.types.EpisodeId
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
    /** Карточка ряда «Продолжить просмотр» (кадр серии, таймкод); OK сразу запускает плеер. */
    val continueInfo: ContinueInfo? = null,
) : CardItem {

    override fun getId(): Int {
        return type.hashCode()
    }

    /**
     * Серия, с которой продолжить просмотр.
     * @param episodeId null — номер серии неизвестен (тогда OK открывает детали)
     * @param ordinalLabel номер серии для подписи («12», «12.5»)
     * @param previewUrl кадр серии 720x405, null — вместо него размытый постер
     * @param durationSec длительность серии, null — неизвестна (без полосы таймкода)
     * @param positionSec где остановились
     */
    data class ContinueInfo(
        val episodeId: EpisodeId?,
        val ordinalLabel: String?,
        val previewUrl: String?,
        val durationSec: Int?,
        val positionSec: Int,
    )

    sealed class Type {
        data class Release(val releaseId: ReleaseId) : Type()
        data class Youtube(val link: String) : Type()
    }
}