package ru.radiationx.anilibria.screen.watching

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
import ru.radiationx.anilibria.ui.presenter.cust.ContinueListRowPresenter
import ru.radiationx.shared.ktx.android.subscribeTo
import ru.radiationx.shared_app.di.quillParentViewModel

class WatchingFragment : RowsSupportFragment() {

    private val rowsPresenter by lazy { ContinueListRowPresenter.rowsPresenterSelector(mainPage = true) }
    private val rowsAdapter by lazy { ArrayObjectAdapter(rowsPresenter) }

    private val heroViewModel by quillParentViewModel<MainHeroViewModel>()

    private val watchingViewModel by quillParentViewModel<WatchingViewModel>()

    private val historyViewModel by quillParentViewModel<WatchingHistoryViewModel>()
    private val continueViewModel by quillParentViewModel<WatchingContinueViewModel>()
    private val favoritesViewModel by quillParentViewModel<WatchingFavoritesViewModel>()
    private val recommendsViewModel by quillParentViewModel<WatchingRecommendsViewModel>()

    private fun getViewModel(rowId: Long): BaseCardsViewModel? = when (rowId) {
        WatchingViewModel.HISTORY_ROW_ID -> historyViewModel
        WatchingViewModel.CONTINUE_ROW_ID -> continueViewModel
        WatchingViewModel.FAVORITES_ROW_ID -> favoritesViewModel
        WatchingViewModel.RECOMMENDS_ROW_ID -> recommendsViewModel
        else -> null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycle.addObserver(watchingViewModel)
        viewLifecycleOwner.lifecycle.addObserver(historyViewModel)
        viewLifecycleOwner.lifecycle.addObserver(continueViewModel)
        viewLifecycleOwner.lifecycle.addObserver(favoritesViewModel)
        viewLifecycleOwner.lifecycle.addObserver(recommendsViewModel)
        setOnItemViewClickedListener { _, item, _, row ->
            val viewMode: BaseCardsViewModel? = getViewModel((row as ListRow).id)
            when (item) {
                is LinkCard -> viewMode?.onLinkCardClick()
                is LoadingCard -> viewMode?.onLoadingCardClick()
                is LibriaCard -> viewMode?.onLibriaCardClick(item)
            }
        }

        setOnItemViewSelectedListener { _, item, _, _ ->
            heroViewModel.onItemSelected(item)
        }
        hideRowsAboveSelected()
        adapter = rowsAdapter

        val rowMap = mutableMapOf<Long, ListRow>()
        subscribeTo(watchingViewModel.rowListData) { rowList ->
            val rows = rowList.map { rowId ->
                val row =
                    rowMap[rowId] ?: createCardsRowBy(rowId, rowsAdapter, getViewModel(rowId)!!)
                rowMap[rowId] = row
                row
            }
            rowsAdapter.setItems(rows, RowDiffCallback)
        }
    }
}