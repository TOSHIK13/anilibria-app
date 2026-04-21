package ru.radiationx.anilibria.screen.details.collection

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.details.DetailExtra
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.repository.CollectionRepository
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

class DetailCollectionViewModel @Inject constructor(
    private val argExtra: DetailExtra,
    private val collectionRepository: CollectionRepository,
    private val guidedRouter: GuidedRouter,
) : LifecycleViewModel() {

    val currentTypeData = MutableStateFlow<CollectionType?>(null)
    val progressState = MutableStateFlow(false)

    init {
        loadCurrentType()
    }

    fun setCollection(type: CollectionType?) {
        if (progressState.value) {
            return
        }
        if (currentTypeData.value == type) {
            guidedRouter.close()
            return
        }
        viewModelScope.launch {
            progressState.value = true
            coRunCatching {
                collectionRepository.setReleaseCollection(argExtra.id, type)
            }.onSuccess {
                currentTypeData.value = type
                guidedRouter.close()
            }.onFailure {
                Timber.e(it)
            }
            progressState.value = false
        }
    }

    private fun loadCurrentType() {
        viewModelScope.launch {
            progressState.value = true
            coRunCatching {
                collectionRepository.getReleaseCollection(argExtra.id)
            }.onSuccess {
                currentTypeData.value = it
            }.onFailure {
                Timber.e(it)
            }
            progressState.value = false
        }
    }
}
