package ru.radiationx.anilibria.screen.details

import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import ru.radiationx.anilibria.common.FranchiseCard
import ru.radiationx.anilibria.common.FranchiseRowData
import ru.radiationx.anilibria.screen.DetailsScreen
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.release.ReleaseFranchise
import ru.radiationx.data.interactors.ReleaseInteractor
import javax.inject.Inject

/**
 * Ряд франшизы (V1 franchises/release/{id}): все части по порядку, включая открытый релиз.
 * Есть ли ряд вообще, решает [DetailsViewModel].
 */
class DetailRelatedViewModel @Inject constructor(
    argExtra: DetailExtra,
    private val releaseInteractor: ReleaseInteractor,
    private val router: Router,
) : LifecycleViewModel() {

    private val releaseId = argExtra.id

    val franchiseData = MutableStateFlow<FranchiseRowData?>(null)

    init {
        // Как в DetailsViewModel: ошибка запроса не кэшируется, повтор — при обновлении релиза.
        releaseInteractor
            .observeFull(releaseId)
            .onStart { releaseInteractor.getItem(releaseId)?.also { emit(it) } }
            .map { releaseInteractor.loadFranchises(releaseId).firstOrNull() }
            .distinctUntilChanged()
            .onEach { franchise ->
                if (franchise != null) {
                    franchiseData.value = toRowData(franchise)
                }
            }
            .launchIn(viewModelScope)
    }

    fun onFranchiseCardClick(card: FranchiseCard) {
        if (card.isCurrent) return
        router.navigateTo(DetailsScreen(card.releaseId))
    }

    private fun toRowData(franchise: ReleaseFranchise): FranchiseRowData {
        val cards = franchise.parts.map { part -> toCard(part.sortOrder, part.release) }
        return FranchiseRowData(
            title = franchiseTitle(franchise),
            cards = cards,
            currentIndex = cards.indexOfFirst { it.isCurrent }
        )
    }

    private fun toCard(sortOrder: Int, release: Release): FranchiseCard {
        val type = release.types.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val isFilm = release.types.any { isFilmType(it) }
        val total = release.series?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val year = release.year?.trim()?.takeIf { it.isNotEmpty() }
        val meta = listOfNotNull(
            year,
            total?.takeIf { !isFilm }?.let { "$it эп." }
        ).joinToString(" · ")
        return FranchiseCard(
            releaseId = release.id,
            orderLabel = listOfNotNull("%02d".format(sortOrder), type).joinToString(" · "),
            title = release.title.orEmpty(),
            image = release.poster.orEmpty(),
            meta = meta,
            episodesTotal = total,
            episodesAvailable = release.episodesAvailable,
            isFilm = isFilm,
            isCurrent = release.id == releaseId,
        )
    }

    /** «Франшиза «Имя» · 2020–2026 · 4 релиза · 60 эп.» — неизвестные части опускаются. */
    private fun franchiseTitle(franchise: ReleaseFranchise): String {
        val first = franchise.firstYear
        val last = franchise.lastYear
        val years = when {
            first != null && last != null && first != last -> "$first–$last"
            else -> (first ?: last)?.toString()
        }
        val releases = (franchise.totalReleases ?: franchise.parts.size.takeIf { it > 0 })
            ?.let { "$it ${pluralReleases(it)}" }
        val episodes = franchise.totalEpisodes?.takeIf { it > 0 }?.let { "$it эп." }
        return listOfNotNull("Франшиза «${franchise.name}»", years, releases, episodes)
            .joinToString(" · ")
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
