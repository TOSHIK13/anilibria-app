package ru.radiationx.anilibria.screen.services

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.external.AniListService
import ru.radiationx.data.external.ExternalSyncJournal
import ru.radiationx.data.external.JournalDirection
import ru.radiationx.data.external.JournalEntry
import ru.radiationx.data.external.JournalResult
import javax.inject.Inject

enum class JournalFilter { ALL, OUT, IN, ERRORS }

data class JournalUi(
    val filter: JournalFilter,
    val entries: List<JournalEntry>,
    val errorCount: Int,
)

class AniListJournalViewModel @Inject constructor(
    private val journal: ExternalSyncJournal,
) : LifecycleViewModel() {

    private val filter = MutableStateFlow(JournalFilter.ALL)

    val ui: Flow<JournalUi> = combine(journal.observe(AniListService.ID), filter) { all, f ->
        val shown = when (f) {
            JournalFilter.ALL -> all
            JournalFilter.OUT -> all.filter { it.direction == JournalDirection.OUT }
            JournalFilter.IN -> all.filter { it.direction == JournalDirection.IN }
            JournalFilter.ERRORS -> all.filter { it.result == JournalResult.ERROR }
        }
        JournalUi(f, shown, all.count { it.result == JournalResult.ERROR })
    }

    fun setFilter(value: JournalFilter) {
        filter.value = value
    }

    fun retryNow(entry: JournalEntry) = entry.itemId?.let { journal.retryNow(it) }

    fun skip(entry: JournalEntry) = entry.itemId?.let { journal.skip(it) }
}
