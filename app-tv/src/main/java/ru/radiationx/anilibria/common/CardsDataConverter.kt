package ru.radiationx.anilibria.common

import android.content.Context
import ru.radiationx.data.entity.domain.feed.FeedItem
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.domain.youtube.YoutubeItem
import ru.radiationx.data.repository.watch.ReleaseCardCacheLogic
import ru.radiationx.data.repository.watch.ReleaseCardInfo
import ru.radiationx.shared.ktx.android.relativeDate
import ru.radiationx.shared.ktx.capitalizeDefault
import ru.radiationx.shared.ktx.decapitalizeDefault
import java.util.Date

class CardsDataConverter(
    private val context: Context,
) {

    fun toCard(releaseItem: Release): LibriaCard =
        toCard(ReleaseCardCacheLogic.fromRelease(releaseItem))

    /** Карточка релиза из кратких данных (в т.ч. из дискового кэша «Продолжить просмотр»). */
    fun toCard(info: ReleaseCardInfo): LibriaCard = info.run {
        val torrentDate = torrentUpdate.takeIf { it != 0 }?.let { Date(it * 1000L) }
        val seasonText = "${year.orEmpty()} ${season.orEmpty()}"
        val genreText = genres.firstOrNull()?.capitalizeDefault()
        val seriesText = "Серии: ${series?.trim() ?: "Не доступно"}"
        val updateText = torrentDate?.let {
            "Обновлен ${it.relativeDate(context).decapitalizeDefault()}"
        }
        val descItems = listOfNotNull(seasonText, genreText, seriesText, updateText)
        LibriaCard(
            title.orEmpty(),
            descItems.joinToString(" • "),
            poster.orEmpty(),
            LibriaCard.Type.Release(ReleaseId(id)),
            episodesTotal = series?.trim()?.toIntOrNull()?.takeIf { it > 0 },
            episodesAvailable = episodesAvailable,
        )
    }

    fun toCard(youtubeItem: YoutubeItem) = youtubeItem.run {
        LibriaCard(
            title.orEmpty(),
            "Вышел ${Date(timestamp * 1000L).relativeDate(context).decapitalizeDefault()}",
            image.orEmpty(),
            LibriaCard.Type.Youtube(youtubeItem.link)
        )
    }

    fun toCard(feedItem: FeedItem): LibriaCard = feedItem.run {
        when {
            release != null -> toCard(release!!)
            youtube != null -> toCard(youtube!!)
            else -> throw RuntimeException("WataFuq")
        }
    }
}