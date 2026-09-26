package ru.radiationx.anilibria.screen.main

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.common.BaseRowsViewModel
import ru.radiationx.anilibria.common.ContinueWatchingLoader
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.repository.AuthRepository
import javax.inject.Inject

class MainViewModel @Inject constructor(
    authRepository: AuthRepository,
    continueWatchingLoader: ContinueWatchingLoader,
) : BaseRowsViewModel() {

    companion object {
        const val FEED_ROW_ID = 1L
        const val SCHEDULE_ROW_ID = 2L
        const val FAVORITE_ROW_ID = 3L
        const val YOUTUBE_ROW_ID = 4L
        const val CONTINUE_ROW_ID = 5L
    }

    override val rowIds: List<Long> =
        listOf(CONTINUE_ROW_ID, FEED_ROW_ID, FAVORITE_ROW_ID, SCHEDULE_ROW_ID, YOUTUBE_ROW_ID)

    override val availableRows: MutableSet<Long> =
        mutableSetOf(FEED_ROW_ID, SCHEDULE_ROW_ID, YOUTUBE_ROW_ID)

    init {
        authRepository
            .observeAuthState()
            .distinctUntilChanged()
            .onEach {
                updateAvailableRow(FAVORITE_ROW_ID, it == AuthState.AUTH)
            }
            .launchIn(viewModelScope)

        // Только с авторизацией и непустой серверной историей (снимок с диска — сразу при старте).
        continueWatchingLoader
            .observeHasItems()
            .onEach { updateAvailableRow(CONTINUE_ROW_ID, it) }
            .launchIn(viewModelScope)
    }
}
