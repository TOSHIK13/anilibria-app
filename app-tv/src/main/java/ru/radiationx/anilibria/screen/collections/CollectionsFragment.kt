package ru.radiationx.anilibria.screen.collections

import android.os.Bundle
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ListRow
import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LinkCard
import ru.radiationx.anilibria.common.LoadingCard
import ru.radiationx.anilibria.common.RowDiffCallback
import ru.radiationx.anilibria.extension.createCardsRowBy
import ru.radiationx.anilibria.screen.mainpages.MainHeroViewModel
import ru.radiationx.anilibria.screen.mainpages.hideRowsAboveSelected
import ru.radiationx.anilibria.ui.presenter.cust.CustomListRowPresenter
import ru.radiationx.shared.ktx.android.subscribeTo
import ru.radiationx.shared_app.di.quillParentViewModel

class CollectionsFragment : RowsSupportFragment() {

    private val rowsPresenter by lazy { CustomListRowPresenter().applyMainPageStyle() }
    private val rowsAdapter by lazy { ArrayObjectAdapter(rowsPresenter) }

    private val heroViewModel by quillParentViewModel<MainHeroViewModel>()

    private val collectionsViewModel by quillParentViewModel<CollectionsViewModel>()
    private val authViewModel by quillParentViewModel<CollectionsAuthViewModel>()
    private val watchingViewModel by quillParentViewModel<CollectionsWatchingViewModel>()
    private val plannedViewModel by quillParentViewModel<CollectionsPlannedViewModel>()
    private val watchedViewModel by quillParentViewModel<CollectionsWatchedViewModel>()
    private val postponedViewModel by quillParentViewModel<CollectionsPostponedViewModel>()
    private val abandonedViewModel by quillParentViewModel<CollectionsAbandonedViewModel>()

    private fun getViewModel(rowId: Long): BaseCardsViewModel? = when (rowId) {
        CollectionsViewModel.AUTH_ROW_ID -> authViewModel
        CollectionsViewModel.WATCHING_ROW_ID -> watchingViewModel
        CollectionsViewModel.PLANNED_ROW_ID -> plannedViewModel
        CollectionsViewModel.WATCHED_ROW_ID -> watchedViewModel
        CollectionsViewModel.POSTPONED_ROW_ID -> postponedViewModel
        CollectionsViewModel.ABANDONED_ROW_ID -> abandonedViewModel
        else -> null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycle.addObserver(collectionsViewModel)
        viewLifecycleOwner.lifecycle.addObserver(authViewModel)
        viewLifecycleOwner.lifecycle.addObserver(watchingViewModel)
        viewLifecycleOwner.lifecycle.addObserver(plannedViewModel)
        viewLifecycleOwner.lifecycle.addObserver(watchedViewModel)
        viewLifecycleOwner.lifecycle.addObserver(postponedViewModel)
        viewLifecycleOwner.lifecycle.addObserver(abandonedViewModel)

        adapter = rowsAdapter

        setOnItemViewClickedListener { _, item, _, row ->
            val viewModel = getViewModel((row as ListRow).id)
            when (item) {
                is LinkCard -> viewModel?.onLinkCardClick()
                is LoadingCard -> viewModel?.onLoadingCardClick()
                is LibriaCard -> viewModel?.onLibriaCardClick(item)
            }
        }

        setOnItemViewSelectedListener { _, item, _, _ ->
            heroViewModel.onItemSelected(item)
        }
        hideRowsAboveSelected()

        val rowMap = mutableMapOf<Long, ListRow>()
        subscribeTo(collectionsViewModel.rowListData) { rowList ->
            val rows = rowList.map { rowId ->
                val viewModel = requireNotNull(getViewModel(rowId)) {
                    "Unknown collections row id: $rowId"
                }
                val row = rowMap[rowId] ?: createCardsRowBy(
                    rowId,
                    rowsAdapter,
                    viewModel
                )
                rowMap[rowId] = row
                row
            }
            rowsAdapter.setItems(rows, RowDiffCallback)
        }
    }
}
