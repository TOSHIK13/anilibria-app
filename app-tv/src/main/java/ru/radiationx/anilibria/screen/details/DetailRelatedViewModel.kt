package ru.radiationx.anilibria.screen.details

import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import ru.radiationx.anilibria.common.FranchiseCard
import ru.radiationx.anilibria.common.FranchiseRowData
import ru.radiationx.anilibria.screen.DetailsScreen
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.release.ReleaseFranchise
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.CollectionRepository
import javax.inject.Inject

/**
 * Ряд франшизы (V1 franchises/release/{id}): все части по порядку, включая открытый релиз.
 * Есть ли ряд вообще, решает [DetailsViewModel].
 */
class DetailRelatedViewModel @Inject constructor(
    argExtra: DetailExtra,
    private val releaseInteractor: ReleaseInteractor,
    private val collectionRepository: CollectionRepository,
    private val router: Router,
) : LifecycleViewModel() {

    private val releaseId = argExtra.id

    val franchiseData = MutableStateFlow<FranchiseRowData?>(null)

    init {
        // Как в DetailsViewModel: ошибка запроса не кэшируется, повтор — при обновлении релиза.
        val franchiseFlow = releaseInteractor
            .observeFull(releaseId)
            .onStart { releaseInteractor.getItem(releaseId)?.also { emit(it) } }
            .map { releaseInteractor.loadFranchises(releaseId).firstOrNull() }
            .distinctUntilChanged()

        combine(franchiseFlow, collectionRepository.observeCollectionIds()) { franchise, collectionIds ->
            franchise to collectionIds.orEmpty()
        }
            .onEach { (franchise, collectionIds) ->
                if (franchise != null) {
                    franchiseData.value = toRowData(franchise, collectionIds)
                }
            }
            .launchIn(viewModelScope)
    }

    fun onFranchiseCardClick(card: FranchiseCard) {
        if (card.isCurrent) return
        router.navigateTo(DetailsScreen(card.releaseId))
    }

    private fun toRowData(
        franchise: ReleaseFranchise,
        collectionIds: Map<ReleaseId, CollectionType>,
    ): FranchiseRowData {
        // «Просмотрено» открытого релиза — повод подсветить части франшизы, которых нет
        // ни в одной коллекции пользователя (вероятно, пропущены).
        val currentWatched = collectionIds[releaseId] == CollectionType.WATCHED
        val cards = franchise.parts.map { part ->
            toCard(part.sortOrder, part.release, collectionIds, currentWatched)
        }
        val unseenCount = cards.count { it.isUnseen }
        return FranchiseRowData(
            title = franchiseTitle(franchise, unseenCount),
            cards = cards,
            currentIndex = cards.indexOfFirst { it.isCurrent },
            unseenCount = unseenCount,
        )
    }

    private fun toCard(
        sortOrder: Int,
        release: Release,
        collectionIds: Map<ReleaseId, CollectionType>,
        currentWatched: Boolean,
    ): FranchiseCard {
        val type = release.types.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val isFilm = release.types.any { isFilmType(it) }
        val total = release.series?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val year = release.year?.trim()?.takeIf { it.isNotEmpty() }
        val meta = listOfNotNull(
            year,
            total?.takeIf { !isFilm }?.let { "$it эп." }
        ).joinToString(" · ")
        val isCurrent = release.id == releaseId
        val collectionType = collectionIds[release.id]
        return FranchiseCard(
            releaseId = release.id,
            orderLabel = listOfNotNull("%02d".format(sortOrder), type).joinToString(" · "),
            title = release.title.orEmpty(),
            image = release.poster.orEmpty(),
            meta = meta,
            episodesTotal = total,
            episodesAvailable = release.episodesAvailable,
            isFilm = isFilm,
            isCurrent = isCurrent,
            collectionType = collectionType,
            isUnseen = currentWatched && !isCurrent && collectionType == null,
        )
    }

    /**
     * «Франшиза «Имя» · 2020–2026 · 4 релиза · 60 эп.» — неизвестные части опускаются;
     * если открытый релиз просмотрен, а часть франшизы не отмечена в коллекциях — подсказка в конце.
     */
    private fun franchiseTitle(franchise: ReleaseFranchise, unseenCount: Int): String {
        val first = franchise.firstYear
        val last = franchise.lastYear
        val years = when {
            first != null && last != null && first != last -> "$first–$last"
            else -> (first ?: last)?.toString()
        }
        val releases = (franchise.totalReleases ?: franchise.parts.size.takeIf { it > 0 })
            ?.let { "$it ${pluralReleases(it)}" }
        val episodes = franchise.totalEpisodes?.takeIf { it > 0 }?.let { "$it эп." }
        val unseenHint = unseenCount.takeIf { it > 0 }
            ?.let { "$it ${pluralParts(it)} франшизы не отмечены" }
        return listOfNotNull("Франшиза «${franchise.name}»", years, releases, episodes, unseenHint)
            .joinToString(" · ")
    }

    private fun pluralParts(count: Int): String {
        val mod100 = count % 100
        val mod10 = count % 10
        return when {
            mod100 in 11..14 -> "частей"
            mod10 == 1 -> "часть"
            mod10 in 2..4 -> "части"
            else -> "частей"
        }
    }

    private fun pluralReleases(count: Int): String {
        val mod100 = count % 100
        val mod10 = count % 10
        return when {
            mod100 in 11..14 -> "релизов"
            mod10 == 1 -> "релиз"
            mod10 in 2..4 -> "релиза"
            else -> "релизов"
        }
    }

    private fun isFilmType(type: String): Boolean =
        type.trim().equals("Фильм", ignoreCase = true) || type.trim().equals("MOVIE", ignoreCase = true)
}
