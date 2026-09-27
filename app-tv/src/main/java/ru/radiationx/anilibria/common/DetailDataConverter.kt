package ru.radiationx.anilibria.common

import androidx.core.text.parseAsHtml
import ru.radiationx.anilibria.screen.details.collection.DetailCollectionGuidedFragment
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.domain.release.Episode
import ru.radiationx.data.entity.domain.release.EpisodeAccess
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.schedule.ReleaseScheduleInfo
import ru.radiationx.data.entity.domain.schedule.ScheduleDay
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.repository.watch.WatchHistoryLogic
import ru.radiationx.shared.ktx.capitalizeDefault
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

/** Серия, с которой продолжится просмотр, и позиция в ней. */
data class ContinueTarget(
    val episodeId: EpisodeId,
    val episode: Episode?,
    val positionMs: Long,
    /** Все серии уже просмотрены — это пересмотр с начала, а не продолжение. */
    val isRewatch: Boolean = false,
)

class DetailDataConverter @Inject constructor() {

    private val ruLocale = Locale("ru")

    private val ratingFormat = DecimalFormat("0.##", DecimalFormatSymbols(ruLocale))

    private val countFormat = DecimalFormat(
        "#,###",
        DecimalFormatSymbols(ruLocale).apply { groupingSeparator = ' ' }
    )

    fun toDetail(
        releaseItem: Release,
        isFull: Boolean,
        accesses: List<EpisodeAccess>,
        collection: CollectionType?,
        scheduleInfo: ReleaseScheduleInfo?,
    ): LibriaDetails = releaseItem.run {
        val total = series?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val released = episodesAvailable ?: episodes.size.takeIf { it > 0 }
        LibriaDetails(
            id = id,
            titleRu = title.orEmpty(),
            titleEn = nameEnglish?.takeIf { !it.equals(title, ignoreCase = true) }.orEmpty(),
            chips = chips(released, total),
            compactMeta = compactMeta(released, total),
            infoLine = infoLine(),
            description = description.orEmpty().parseAsHtml().toString().trim()
                .trim('"'),
            image = poster.orEmpty(),
            backgroundCover = backgroundCover?.takeIf { it.isNotBlank() },
            isFull = isFull,
            isFavorite = favoriteInfo.isAdded,
            hasEpisodes = episodes.isNotEmpty(),
            firstEpisodeLabel = episodes.firstOrNull()?.id?.id,
            progress = progress(accesses, total),
            nextEpisode = nextEpisode(scheduleInfo, released, total),
            collectionName = collection?.let { type ->
                DetailCollectionGuidedFragment.COLLECTION_ITEMS.firstOrNull { it.first == type }?.second
            },
            // Частичный (кэшированный) релиз несёт только «в избранном» — ждём полный.
            ratings = if (isFull) ratings() else null,
        )
    }

    /**
     * Куда ведёт «Продолжить»: последняя открытая серия, а если она досмотрена —
     * следующая с начала (если есть). Если досмотрены вообще все серии — пересмотр
     * с первой. null — релиз ещё не смотрели.
     */
    fun continueTarget(release: Release, accesses: List<EpisodeAccess>): ContinueTarget? {
        val episodes = release.episodes
        if (episodes.isEmpty()) return null
        val watched = accesses.filter { it.seek > 0 || it.isViewed }
        if (watched.isEmpty()) return null

        val indexById = episodes.withIndex().associate { (index, episode) -> episode.id to index }

        // Все серии, у которых есть история просмотра, отмечены как просмотренные полностью,
        // а таких серий не меньше, чем в релизе — сезон досмотрен целиком.
        val allViewed = episodes.isNotEmpty() && episodes.all { episode ->
            accesses.firstOrNull { it.id == episode.id }?.isViewed == true
        }
        if (allViewed) {
            val first = episodes.first()
            return ContinueTarget(first.id, first, 0L, isRewatch = true)
        }

        // При равном времени последнего доступа (например, история пришла одним пакетом
        // с общим таймстемпом) берём серию с бОльшим порядковым номером, а не первую
        // попавшуюся — иначе «следующая после последней» всегда будет второй серией.
        val last = watched.maxWithOrNull(
            compareBy<EpisodeAccess> { it.lastAccessRaw }
                .thenBy { indexById[it.id] ?: -1 }
        ) ?: return null
        val index = indexById[last.id] ?: -1
        val next = if (last.isViewed && index >= 0) episodes.getOrNull(index + 1) else null
        return if (next != null) {
            ContinueTarget(next.id, next, 0L)
        } else {
            ContinueTarget(last.id, episodes.getOrNull(index), last.seek.coerceAtLeast(0L))
        }
    }

    /** Когда выходят серии онгоинга: анонс релиза или день из расписания. */
    fun scheduleAnnounce(release: Release): String? {
        if (release.statusCode == Release.STATUS_CODE_COMPLETE) return null
        val originalAnnounce = release.announce?.trim()?.trim('.')?.capitalizeDefault()?.takeIf { it.isNotEmpty() }
        return originalAnnounce ?: release.days.firstOrNull()?.toAnnounce2()
    }

    private fun Release.isOngoing(): Boolean = statusCode == Release.STATUS_CODE_PROGRESS

    private fun Release.isFilm(): Boolean = types.any {
        it.trim().equals("Фильм", ignoreCase = true) || it.trim().equals("MOVIE", ignoreCase = true)
    }

    private fun Release.chips(released: Int?, total: Int?): List<DetailChip> {
        val status = DetailChip(
            text = if (isOngoing()) "Онгоинг" else "Релиз завершён",
            isStatus = true
        )
        val type = types.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val yearSeason = listOfNotNull(
            year?.trim()?.takeIf { it.isNotEmpty() },
            season?.trim()?.takeIf { it.isNotEmpty() }?.capitalizeDefault()
        ).joinToString(" ").takeIf { it.isNotEmpty() }
        val episodesText = episodesText(released, total)
        val age = ageRating?.trim()?.takeIf { it.isNotEmpty() }
        val shikimoriChip = shikimoriRating?.let { "★ ${ratingFormat.format(it)} Shikimori" }
        // Вторая оценка рядом с первой — та же карточка API, доп. запросов не нужно.
        val malChip = malRating?.let { "★ ${ratingFormat.format(it)} MyAnimeList" }
        return listOf(status) + listOfNotNull(type, yearSeason, episodesText, age, shikimoriChip, malChip)
            .map { DetailChip(it) }
    }

    private fun Release.episodesText(released: Int?, total: Int?): String? {
        if (isFilm()) return null
        val count = when {
            total == null -> released?.let { "вышло $it эп." }
            isOngoing() && released != null && released < total -> "$released из $total эп."
            else -> "$total эп."
        } ?: return null
        val duration = averageEpisodeDurationMin?.let { " по $it мин" }.orEmpty()
        return count + duration
    }

    private fun Release.compactMeta(released: Int?, total: Int?): String {
        val episodes = if (isFilm()) null else (total ?: released)?.let { "$it эп." }
        return listOfNotNull(
            year?.trim()?.takeIf { it.isNotEmpty() },
            types.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            episodes
        ).joinToString(" · ")
    }

    /** Узкий неразрывный пробел между разрядами: «16 538». */
    private val thinCountFormat = DecimalFormat(
        "#,###",
        DecimalFormatSymbols(ruLocale).apply { groupingSeparator = ' ' }
    )

    private val scoreFormat = DecimalFormat("0.00", DecimalFormatSymbols(ruLocale))

    private fun Release.ratings(): DetailRatings? {
        val scores = listOfNotNull(
            score("Shikimori", shikimoriRating, shikimoriVotes, "shikimori.io"),
            score("MyAnimeList", malRating, malVotes, "myanimelist.net"),
            ownRatingVotes?.takeIf { it > 0 }?.let {
                score("AniLiberty", ownRatingAverage, it, "оценки пользователей")
            },
        )
        val stats = collectionStats?.let { stats ->
            listOfNotNull(
                stat(stats.favorites, "В избранном"),
                stat(stats.watching, "Смотрят"),
                stat(stats.planned, "Запланировали"),
                stat(stats.watched, "Просмотрели"),
                stat(stats.postponed, "Отложили"),
                stat(stats.abandoned, "Бросили"),
            )
        }.orEmpty()
        if (scores.isEmpty() && stats.isEmpty()) return null
        return DetailRatings(scores, stats)
    }

    private fun score(source: String, rating: Double?, votes: Int?, caption: String): DetailRatingScore? {
        rating ?: return null
        return DetailRatingScore(
            source = source,
            value = scoreFormat.format(rating),
            votes = votes?.takeIf { it > 0 }
                ?.let { "${thinCountFormat.format(it)} ${votesWord(it)}" }
                .orEmpty(),
            caption = caption,
        )
    }

    private fun stat(value: Int?, label: String): DetailRatingStat? =
        value?.let { DetailRatingStat(thinCountFormat.format(it), label) }

    /** голос / голоса / голосов. */
    private fun votesWord(count: Int): String {
        val mod100 = count % 100
        val mod10 = count % 10
        return when {
            mod100 in 11..14 -> "голосов"
            mod10 == 1 -> "голос"
            mod10 in 2..4 -> "голоса"
            else -> "голосов"
        }
    }

    private fun Release.infoLine(): String {
        val parts = genres.map { it.trim().capitalizeDefault() }.filter { it.isNotEmpty() }.toMutableList()
        val favorites = favoriteInfo.rating
        if (favorites > 0) {
            parts.add("${countFormat.format(favorites)} в избранном")
        }
        return parts.joinToString(" · ")
    }

    private fun Release.progress(accesses: List<EpisodeAccess>, total: Int?): DetailProgress? {
        val target = continueTarget(this, accesses) ?: return null
        return DetailProgress(
            episodeLabel = target.episodeId.id,
            positionSec = (target.positionMs / 1000L).toInt(),
            durationSec = target.episode?.durationSec?.takeIf { it > 0 },
            episodeName = target.episode?.displayName(),
            watchedCount = accesses.count { it.isViewed },
            totalCount = total,
            isRewatch = target.isRewatch,
        )
    }

    /** Название серии без «Серия N». */
    private fun Episode.displayName(): String? = title
        ?.split(" • ")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !episodeLabelRegex.matches(it) }
        ?.joinToString(" • ")
        ?.takeIf { it.isNotEmpty() }

    private fun Release.nextEpisode(
        info: ReleaseScheduleInfo?,
        released: Int?,
        total: Int?,
    ): DetailNextEpisode? {
        info ?: return null
        if (!isOngoing() || info.fullSeasonIsReleased) return null
        val next = info.nextEpisodeNumber ?: return null
        val day = info.publishDay ?: return null
        val at = day.dayAt() ?: return null
        val every = day.dayEvery() ?: return null
        val releasedText = info.publishedEpisodeOrdinal?.let { WatchHistoryLogic.ordinalLabel(it) }
            ?: released?.toString()
        val subtitle = listOfNotNull(
            releasedText?.let { "вышло $it" + total?.let { t -> " из $t" }.orEmpty() },
            every
        ).joinToString(" · ")
        return DetailNextEpisode(
            title = "Серия $next · $at",
            subtitle = subtitle
        )
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

    private fun String.toAnnounce2(): String {
        val calendarDay = ScheduleDay.toCalendarDay(this)
        return "Серии выходят ${calendarDay.dayAt().orEmpty()}"
    }

    private companion object {
        val episodeLabelRegex = Regex("^(Серия|Эпизод)\\s+[\\d.,]+$", RegexOption.IGNORE_CASE)
    }
}
