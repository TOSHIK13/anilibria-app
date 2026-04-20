package ru.radiationx.anilibria.screen.collections

import android.os.Bundle
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ListRow
import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.GradientBackgroundManager
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LinkCard
import ru.radiationx.anilibria.common.LoadingCard
import ru.radiationx.anilibria.common.RowDiffCallback
import ru.radiationx.anilibria.extension.applyCard
import ru.radiationx.anilibria.extension.createCardsRowBy
import ru.radiationx.anilibria.ui.presenter.cust.CustomListRowPresenter
import ru.radiationx.anilibria.ui.presenter.cust.CustomListRowViewHolder
import ru.radiationx.quill.inject
import ru.radiationx.shared.ktx.android.subscribeTo
import ru.radiationx.shared_app.di.quillParentViewModel

class CollectionsFragment : RowsSupportFragment() {

    private val rowsPresenter by lazy { CustomListRowPresenter() }
    private val rowsAdapter by lazy { ArrayObjectAdapter(rowsPresenter) }

    private val backgroundManager by inject<GradientBackgroundManager>()

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

        setOnItemViewSelectedListener { _, item, rowViewHolder, _ ->
            if (rowViewHolder is CustomListRowViewHolder) {
                backgroundManager.applyCard(item)
                when (item) {
                    is LibriaCard -> rowViewHolder.setDescription(item.title, item.description)
                    is LinkCard -> rowViewHolder.setDescription(item.title, "")
                    is LoadingCard -> rowViewHolder.setDescription(item.title, item.description)
                    else -> rowViewHolder.setDescription("", "")
                }
            }
        }

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
