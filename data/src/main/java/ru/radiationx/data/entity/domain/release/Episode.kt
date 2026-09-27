package ru.radiationx.data.entity.domain.release

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import ru.radiationx.data.entity.domain.types.EpisodeId
import java.util.Date

@Parcelize
data class Episode(
    val id: EpisodeId,
    val serverId: String,
    val title: String?,
    val qualityInfo: QualityInfo,
    val updatedAt: Date?,
    val skips: PlayerSkips?,
    /** Абсолютный URL превью серии 720x405 (V1 `preview.optimized.preview`). */
    val previewUrl: String? = null,
    /** Длительность серии в секундах (V1 `duration`). */
    val durationSec: Int? = null,
) : Parcelable
