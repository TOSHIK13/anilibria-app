package ru.radiationx.anilibria.screen.details

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.common.BaseRowsViewModel
import ru.radiationx.anilibria.similar.SimilarReleasesRepository
import ru.radiationx.anilibria.similar.SimilarSource
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.data.repository.HistoryRepository
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

class DetailsViewModel @Inject constructor(
    argExtra: DetailExtra,
    private val releaseInteractor: ReleaseInteractor,
    private val historyRepository: HistoryRepository,
    authRepository: AuthRepository,
    similarRepository: SimilarReleasesRepository,
) : BaseRowsViewModel() {

    companion object {
        const val RELEASE_ROW_ID = 1L
        const val RELATED_ROW_ID = 2L
        const val RECOMMENDS_ROW_ID = 3L
        const val SIMILAR_ANILIST_ROW_ID = 4L
        const val SIMILAR_SHIKIMORI_ROW_ID = 5L
        const val SIMILAR_MAL_ROW_ID = 6L

        private val similarRows = mapOf(
            SimilarSource.ANILIST to SIMILAR_ANILIST_ROW_ID,
            SimilarSource.SHIKIMORI to SIMILAR_SHIKIMORI_ROW_ID,
            SimilarSource.MAL to SIMILAR_MAL_ROW_ID,
        )
    }

    private val releaseId = argExtra.id

    override val rowIds: List<Long> = listOf(
        RELEASE_ROW_ID,
        RELATED_ROW_ID,
        SIMILAR_ANILIST_ROW_ID,
        SIMILAR_SHIKIMORI_ROW_ID,
        SIMILAR_MAL_ROW_ID,
        RECOMMENDS_ROW_ID
    )

    // Ряд франшизы добавляется, только когда франшиза точно есть (пустого ряда не бывает).
    override val availableRows: MutableSet<Long> =
        mutableSetOf(RELEASE_ROW_ID, RECOMMENDS_ROW_ID)

    init {
        loadRelease()

        authRepository
            .observeAuthState()
            .drop(1)
            .distinctUntilChanged()
            .onEach { loadRelease() }
            .launchIn(viewModelScope)

        releaseInteractor
            .observeFull(releaseId)
            .onStart {
                releaseInteractor.getItem(releaseId)?.also {
                    emit(it)
                }
            }
            .map { release ->
                // Ряд франшизы — только по V1 franchises/release/{id} (в релизе V1 их нет),
                // и только если кроме самого релиза есть другие части.
                releaseInteractor.loadFranchises(release.id)
                    .firstOrNull()
                    ?.releases
                    .orEmpty()
                    .any { it.id != release.id }
            }
            .distinctUntilChanged()
            .onEach {
                updateAvailableRow(RELATED_ROW_ID, it)
            }
            .launchIn(viewModelScope)

        // Ряды «Похожие · <сервис>» — только когда в каталоге нашёлся хотя бы один тайтл.
        similarRepository
            .observe(releaseId)
            .map { data -> similarRows.mapValues { (source, _) -> data.lists[source]?.items?.isNotEmpty() == true } }
            .distinctUntilChanged()
            .onEach { available ->
                available.forEach { (source, isAvailable) ->
                    updateAvailableRow(similarRows.getValue(source), isAvailable)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun loadRelease() {
        viewModelScope.launch {
            coRunCatching {
                releaseInteractor.loadRelease(releaseId)
            }.onSuccess {
                historyRepository.putRelease(it)
            }.onFailure {
                Timber.e(it)
            }
        }
    }
}