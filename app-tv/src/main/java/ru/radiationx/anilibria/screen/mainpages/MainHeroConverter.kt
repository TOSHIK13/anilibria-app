package ru.radiationx.anilibria.screen.mainpages

import androidx.core.text.parseAsHtml
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.schedule.ReleaseScheduleInfo
import ru.radiationx.shared.ktx.capitalizeDefault
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

/**
 * Hero-блок главных страниц: сведения о релизе карточки в фокусе.
 * [backgroundKey] определяет смену фона (кроссфейд только при смене картинки).
 */
data class MainHeroState(
    val key: String,
    val title: String,
    val meta: String?,
    val line: Line?,
    val description: String?,
    /** Широкий фон релиза; null — вместо него размытый [posterUrl]. */
    val coverUrl: String?,
    val posterUrl: String?,
) {
    /** Строка статуса; [marker] (▶ или ●) рисуется акцентным цветом. */
    data class Line(val marker: String?, val text: String)

    val backgroundKey: String?
        get() = coverUrl?.let { "cover:$it" } ?: posterUrl?.let { "blur:$it" }
}

/** Где остановились в серии релиза. */
data class MainHeroProgress(
    val ordinalLabel: String?,
    val episodeName: String?,
    val positionSec: Int,
    val durationSec: Int?,
)

class MainHeroConverter @Inject constructor() {

    private val ruLocale = Locale("ru")

    private val ratingFormat = DecimalFormat("0.##", DecimalFormatSymbols(ruLocale))

    private val countFormat = DecimalFormat(
        "#,###",
        DecimalFormatSymbols(ruLocale).apply { groupingSeparator = THIN_SPACE }
    )

    /** Карточка без данных релиза (YouTube или релиз не загрузился). */
    fun fromCard(card: LibriaCard, progress: MainHeroProgress?): MainHeroState = MainHeroState(
        key = card.key(),
        title = card.title,
        meta = card.description.takeIf { it.isNotBlank() && card.continueInfo == null },
        line = progress?.let { progressLine(it) },
        description = null,
        coverUrl = null,
        posterUrl = card.image.takeIf { it.isNotBlank() },
    )

    fun fromRelease(
        card: LibriaCard,
        release: Release,
        progress: MainHeroProgress?,
        scheduleInfo: ReleaseScheduleInfo?,
    ): MainHeroState = MainHeroState(
        key = card.key(),
        title = release.title?.takeIf { it.isNotBlank() } ?: card.title,
        meta = release.meta().takeIf { it.isNotEmpty() },
        line = progress?.let { progressLine(it) } ?: release.statusLine(scheduleInfo),
        description = release.description
            ?.parseAsHtml()
            ?.toString()
            ?.replace(whitespaceRegex, " ")
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() },
        coverUrl = release.backgroundCover?.takeIf { it.isNotBlank() },
        posterUrl = release.poster?.takeIf { it.isNotBlank() } ?: card.image.takeIf { it.isNotBlank() },
    )

    /** Название серии без «Серия N» (как в деталях). */
    fun episodeDisplayName(title: String?): String? = title
        ?.split(" • ")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !episodeLabelRegex.matches(it) }
        ?.joinToString(" • ")
        ?.takeIf { it.isNotEmpty() }

    fun isOngoing(release: Release): Boolean = release.statusCode == Release.STATUS_CODE_PROGRESS

    private fun LibriaCard.key(): String = when (val type = type) {
        is LibriaCard.Type.Release -> "release:${type.releaseId.id}"
        is LibriaCard.Type.Youtube -> "youtube:${type.link}"
    }

    private fun Release.meta(): String {
        val total = series?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val released = episodesAvailable ?: episodes.size.takeIf { it > 0 }
        return listOfNotNull(
            year?.trim()?.takeIf { it.isNotEmpty() },
            season?.trim()?.takeIf { it.isNotEmpty() }?.capitalizeDefault(),
            types.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            episodesText(released, total),
            ageRating?.trim()?.takeIf { it.isNotEmpty() },
            shikimoriRating?.takeIf { it > 0 }?.let { "★ ${ratingFormat.format(it)} Shikimori" },
        ).joinToString(SEPARATOR)
    }

    private fun Release.episodesText(released: Int?, total: Int?): String? {
        if (isFilm()) return null
        val count = when {
            total == null -> released?.let { "вышло $it эп." }
            isOngoing(this) && released != null && released < total -> "$released из $total эп."
            else -> "$total эп."
        } ?: return null
        val duration = averageEpisodeDurationMin?.takeIf { it > 0 }?.let { " по $it мин" }.orEmpty()
        return count + duration
    }

    private fun Release.isFilm(): Boolean = types.any {
        it.trim().equals("Фильм", ignoreCase = true) || it.trim().equals("MOVIE", ignoreCase = true)
    }

    private fun progressLine(progress: MainHeroProgress): MainHeroState.Line {
        val position = formatTime(progress.positionSec)
        val time = progress.durationSec?.let { "$position из ${formatTime(it)}" } ?: position
        val text = listOfNotNull(
            progress.ordinalLabel?.let { "Серия $it" },
            progress.episodeName?.let { "«$it»" },
            time,
        ).joinToString(SEPARATOR)
        return MainHeroState.Line(PLAY_MARKER, text)
    }

    private fun Release.statusLine(info: ReleaseScheduleInfo?): MainHeroState.Line? {
        if (!isOngoing(this)) {
            val favorites = favoriteInfo.rating
            val text = if (favorites > 0) {
                "Релиз завершён$SEPARATOR${countFormat.format(favorites)} в избранном"
            } else {
                "Релиз завершён"
            }
            return MainHeroState.Line(null, text)
        }
        info ?: return null
        val day = info.publishDay
        val next = info.nextEpisodeNumber
        if (next != null && !info.fullSeasonIsReleased) {
            day?.dayAt()?.let { return MainHeroState.Line(NEXT_MARKER, "Серия $next$SEPARATOR$it") }
        }
        return day?.dayEvery()?.let { MainHeroState.Line(null, "Выходит $it") }
    }

    /** m:ss без ведущего нуля у минут. */
    private fun formatTime(totalSec: Int): String {
        val sec = totalSec.coerceAtLeast(0)
        return "%d:%02d".format(Locale.US, sec / 60, sec % 60)
    }

    private fun Int.dayAt(): String? = when (this) {
        Calendar.MONDAY -> "в понедельник"
        Calendar.TUESDAY -> "во вторник"
        Calendar.WEDNESDAY -> "в среду"
        Calendar.THURSDAY -> "в четверг"
        Calendar.FRIDAY -> "в пятницу"
        Calendar.SATURDAY -> "в субботу"
        Calendar.SUNDAY -> "в воскресенье"
        else -> null
    }

    private fun Int.dayEvery(): String? = when (this) {
        Calendar.MONDAY -> "по понедельникам"
        Calendar.TUESDAY -> "по вторникам"
        Calendar.WEDNESDAY -> "по средам"
        Calendar.THURSDAY -> "по четвергам"
        Calendar.FRIDAY -> "по пятницам"
        Calendar.SATURDAY -> "по субботам"
        Calendar.SUNDAY -> "по воскресеньям"
        else -> null
    }

    private companion object {
        const val SEPARATOR = " · "
        const val THIN_SPACE = ' '

        /** ▶ с селектором текстового начертания, чтобы не рисовался эмодзи. */
        const val PLAY_MARKER = "▶︎"
        const val NEXT_MARKER = "●"

        val episodeLabelRegex = Regex("^(Серия|Эпизод)\\s+[\\d.,]+$", RegexOption.IGNORE_CASE)
        val whitespaceRegex = Regex("\\s+")
    }
}
