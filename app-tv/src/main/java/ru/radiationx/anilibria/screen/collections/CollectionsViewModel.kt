package ru.radiationx.anilibria.screen.collections

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.common.BaseRowsViewModel
import ru.radiationx.data.repository.AuthRepository
import javax.inject.Inject

class CollectionsViewModel @Inject constructor(
    authRepository: AuthRepository,
) : BaseRowsViewModel() {

    companion object {
        const val AUTH_ROW_ID = 1L
        const val WATCHING_ROW_ID = 2L
        const val PLANNED_ROW_ID = 3L
        const val WATCHED_ROW_ID = 4L
        const val POSTPONED_ROW_ID = 5L
        const val ABANDONED_ROW_ID = 6L
    }

    override val rowIds: List<Long> = listOf(
        AUTH_ROW_ID,
        WATCHING_ROW_ID,
        PLANNED_ROW_ID,
        WATCHED_ROW_ID,
        POSTPONED_ROW_ID,
        ABANDONED_ROW_ID,
    )

    override val availableRows: MutableSet<Long> = mutableSetOf(AUTH_ROW_ID)

    init {
        authRepository
            .observeSessionToken()
            .map { !it.isNullOrBlank() }
            .onEach { hasAuth ->
                updateAvailableRow(AUTH_ROW_ID, !hasAuth)
                updateAvailableRow(WATCHING_ROW_ID, hasAuth)
                updateAvailableRow(PLANNED_ROW_ID, hasAuth)
                updateAvailableRow(WATCHED_ROW_ID, hasAuth)
                updateAvailableRow(POSTPONED_ROW_ID, hasAuth)
                updateAvailableRow(ABANDONED_ROW_ID, hasAuth)
            }
            .launchIn(viewModelScope)
    }
}
