package ru.radiationx.anilibria.common

import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.domain.types.ReleaseId

/**
 * Карточка части франшизы на экране деталей (ряд «Франшиза …»).
 * Прогресс просмотра карточка берёт сама из WatchProgressRepository.
 */
data class FranchiseCard(
    val releaseId: ReleaseId,
    /** «03 · ТВ» — номер части и тип. */
    val orderLabel: String,
    val title: String,
    val image: String,
    /** «2023 · 24 эп.», у фильма — только год. */
    val meta: String,
    /** Всего серий (episodes_total), null — неизвестно. */
    val episodesTotal: Int?,
    /** Сколько серий вышло, null — неизвестно (догружается при наличии прогресса). */
    val episodesAvailable: Int?,
    val isFilm: Boolean,
    /** Открытый сейчас релиз — плашка «ВЫ ЗДЕСЬ». */
    val isCurrent: Boolean,
    /** Коллекция этой части у пользователя, null — не добавлена ни в одну. */
    val collectionType: CollectionType? = null,
    /**
     * Открытый релиз просмотрен целиком, а эта часть не добавлена ни в одну коллекцию —
     * вероятно, пропущенная часть франшизы. Подсвечивается на карточке.
     */
    val isUnseen: Boolean = false,
) : CardItem {

    override fun getId(): Int = releaseId.hashCode()
}

/** Ряд франшизы: заголовок, части и индекс текущего релиза среди них. */
data class FranchiseRowData(
    val title: String,
    val cards: List<FranchiseCard>,
    /** Индекс открытого релиза в [cards], -1 — его нет среди частей. */
    val currentIndex: Int,
    /** Сколько частей [isUnseen] — для подсказки «N частей франшизы не отмечены». */
    val unseenCount: Int = 0,
) {
    /** «часть k из m» для компактной шапки, null — релиз не входит в части. */
    val partText: String?
        get() = if (currentIndex >= 0) "часть ${currentIndex + 1} из ${cards.size}" else null
}
