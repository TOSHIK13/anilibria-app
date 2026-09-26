package ru.radiationx.anilibria.common

import androidx.leanback.widget.Row
import ru.radiationx.data.entity.domain.types.ReleaseId

data class LibriaDetails(
    val id: ReleaseId,
    val titleRu: String,
    val titleEn: String,
    /** Плашки под названием: первая — статус релиза. */
    val chips: List<DetailChip>,
    /** «Жанр · Жанр · 1 234 в избранном», пусто — строки нет. */
    val infoLine: String,
    val description: String,
    val image: String,
    /** Широкий фон 1920x1080; есть только у полного релиза. */
    val backgroundCover: String?,
    /** Полный релиз загружен: только тогда известно, есть ли [backgroundCover]. */
    val isFull: Boolean,
    val isFavorite: Boolean,
    val hasEpisodes: Boolean,
    /** Номер первой серии для кнопки «Смотреть · серия N». */
    val firstEpisodeLabel: String?,
    /** Прогресс просмотра; null — релиз ещё не смотрели. */
    val progress: DetailProgress?,
    /** Плашка «Следующая серия»; null — не показывать. */
    val nextEpisode: DetailNextEpisode?,
    /** Название коллекции пользователя, null — релиз не в коллекции. */
    val collectionName: String?,
) {
    val hasViewed: Boolean get() = progress != null
}

data class DetailChip(
    val text: String,
    val isStatus: Boolean = false,
)

data class DetailProgress(
    /** Номер серии, с которой продолжится просмотр. */
    val episodeLabel: String,
    val positionSec: Int,
    val durationSec: Int?,
    val episodeName: String?,
    val watchedCount: Int,
    val totalCount: Int?,
)

data class DetailNextEpisode(
    val title: String,
    val subtitle: String,
)

data class DetailsState(
    val loadingProgress: Boolean = false,
    val updateProgress: Boolean = false
)

class LibriaDetailsRow(
    id: Long,
    var details: LibriaDetails? = null,
    var state: DetailsState? = null
) : Row(id, null)
