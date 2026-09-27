package ru.radiationx.anilibria.screen.details

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.Row
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.RecyclerView
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.CardDiffCallback
import ru.radiationx.anilibria.common.FranchiseCard
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LibriaDetailsRow
import ru.radiationx.anilibria.common.LinkCard
import ru.radiationx.anilibria.common.LoadingCard
import ru.radiationx.anilibria.common.RowDiffCallback
import ru.radiationx.anilibria.extension.createCardsRowBy
import ru.radiationx.anilibria.screen.mainpages.hideRowsAboveSelected
import ru.radiationx.anilibria.ui.presenter.FranchiseCardPresenter
import ru.radiationx.anilibria.ui.presenter.ReleaseDetailsPresenter
import ru.radiationx.anilibria.ui.presenter.cust.CustomListRowPresenter
import ru.radiationx.anilibria.ui.presenter.cust.CustomListRowViewHolder
import ru.radiationx.anilibria.ui.presenter.cust.FranchiseListRow
import ru.radiationx.anilibria.ui.presenter.cust.FranchiseListRowPresenter
import ru.radiationx.anilibria.ui.presenter.cust.MainRowHeaderPresenter
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.quill.QuillExtra
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.getExtraNotNull
import ru.radiationx.shared.ktx.android.putExtra
import ru.radiationx.shared.ktx.android.subscribeTo

data class DetailExtra(
    val id: ReleaseId,
) : QuillExtra

class DetailFragment : RowsSupportFragment() {

    companion object {
        private const val ARG_ID = "id"

        fun newInstance(releaseId: ReleaseId) = DetailFragment().putExtra {
            putParcelable(ARG_ID, releaseId)
        }
    }

    private var background: DetailBackgroundView? = null
    private var compactHeader: DetailCompactHeaderView? = null

    private val argExtra by lazy {
        DetailExtra(id = getExtraNotNull(ARG_ID))
    }

    private val rowsPresenter by lazy {
        ClassPresenterSelector().apply {
            addClassPresenter(
                ListRow::class.java,
                CustomListRowPresenter().apply { headerPresenter = MainRowHeaderPresenter(dimUnselected = false) }
            )
            addClassPresenter(
                FranchiseListRow::class.java,
                FranchiseListRowPresenter().apply { headerPresenter = MainRowHeaderPresenter(dimUnselected = false) }
            )
            addClassPresenter(
                LibriaDetailsRow::class.java, ReleaseDetailsPresenter(
                    continueClickListener = headerViewModel::onContinueClick,
                    playClickListener = headerViewModel::onPlayClick,
                    favoriteClickListener = headerViewModel::onFavoriteClick,
                    descriptionClickListener = headerViewModel::onDescriptionClick,
                    collectionClickListener = headerViewModel::onCollectionClick,
                    ratingsClickListener = headerViewModel::onRatingsClick,
                    otherClickListener = headerViewModel::onOtherClick
                )
            )
        }
    }
    private val rowsAdapter by lazy { ArrayObjectAdapter(rowsPresenter) }

    private val detailsViewModel by viewModel<DetailsViewModel> { argExtra }

    private val headerViewModel by viewModel<DetailHeaderViewModel> { argExtra }

    private val relatedViewModel by viewModel<DetailRelatedViewModel> { argExtra }

    private val recommendsViewModel by viewModel<DetailRecommendsViewModel> { argExtra }

    private val similarAniListViewModel by viewModel<DetailSimilarAniListViewModel> { argExtra }

    private val similarShikimoriViewModel by viewModel<DetailSimilarShikimoriViewModel> { argExtra }

    private val similarMalViewModel by viewModel<DetailSimilarMalViewModel> { argExtra }

    private fun getViewModel(rowId: Long): ViewModel? = when (rowId) {
        DetailsViewModel.RELEASE_ROW_ID -> headerViewModel
        DetailsViewModel.RELATED_ROW_ID -> relatedViewModel
        DetailsViewModel.RECOMMENDS_ROW_ID -> recommendsViewModel
        DetailsViewModel.SIMILAR_ANILIST_ROW_ID -> similarAniListViewModel
        DetailsViewModel.SIMILAR_SHIKIMORI_ROW_ID -> similarShikimoriViewModel
        DetailsViewModel.SIMILAR_MAL_ROW_ID -> similarMalViewModel
        else -> null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // Фиксированный фон под рядами: ряды прокручиваются поверх него.
        val rowsView = super.onCreateView(inflater, container, savedInstanceState)
        val backgroundView = DetailBackgroundView(inflater.context)
        background = backgroundView
        backgroundView.addView(
            rowsView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Компактная шапка поверх рядов — пока фокус ниже кнопок.
        val compactView = DetailCompactHeaderView(inflater.context)
        compactHeader = compactView
        backgroundView.addView(
            compactView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return backgroundView
    }

    override fun onDestroyView() {
        super.onDestroyView()
        background = null
        compactHeader = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycle.addObserver(detailsViewModel)
        viewLifecycleOwner.lifecycle.addObserver(headerViewModel)
        viewLifecycleOwner.lifecycle.addObserver(relatedViewModel)
        viewLifecycleOwner.lifecycle.addObserver(recommendsViewModel)
        viewLifecycleOwner.lifecycle.addObserver(similarAniListViewModel)
        viewLifecycleOwner.lifecycle.addObserver(similarShikimoriViewModel)
        viewLifecycleOwner.lifecycle.addObserver(similarMalViewModel)

        adapter = rowsAdapter
        setupCollapsingHeader()

        setOnItemViewClickedListener { _, item, _, row ->
            val viewMode: BaseCardsViewModel? =
                getViewModel((row as ListRow).id) as? BaseCardsViewModel
            when (item) {
                is FranchiseCard -> relatedViewModel.onFranchiseCardClick(item)
                is LinkCard -> viewMode?.onLinkCardClick()
                is LoadingCard -> viewMode?.onLoadingCardClick()
                is LibriaCard -> viewMode?.onLibriaCardClick(item)
            }
        }

        subscribeTo(headerViewModel.releaseData) { details ->
            details ?: return@subscribeTo
            background?.bind(
                cover = details.backgroundCover,
                poster = details.image.takeIf { it.isNotEmpty() },
                isFull = details.isFull
            )
            bindCompactHeader()
        }
        subscribeTo(relatedViewModel.franchiseData) { bindCompactHeader() }
        subscribeTo(headerViewModel.ratingsEvent) { (title, ratings) ->
            if (childFragmentManager.findFragmentByTag(DetailRatingsDialogFragment.TAG) != null) {
                return@subscribeTo
            }
            DetailRatingsDialogFragment
                .newInstance(title, ratings)
                .show(childFragmentManager, DetailRatingsDialogFragment.TAG)
        }
        subscribeTo(headerViewModel.descriptionEvent) { description ->
            if (childFragmentManager.findFragmentByTag(DetailDescriptionDialogFragment.TAG) != null) {
                return@subscribeTo
            }
            DetailDescriptionDialogFragment
                .newInstance(description.title, description.text)
                .show(childFragmentManager, DetailDescriptionDialogFragment.TAG)
        }

        setOnItemViewSelectedListener { _, item, rowViewHolder, _ ->
            if (rowViewHolder is CustomListRowViewHolder) {
                when (item) {
                    is LibriaCard -> {
                        rowViewHolder.setDescription(item.title, item.description)
                    }

                    is LinkCard -> {
                        rowViewHolder.setDescription(item.title, "")
                    }

                    is LoadingCard -> {
                        rowViewHolder.setDescription(item.title, item.description)
                    }

                    else -> {
                        rowViewHolder.setDescription("", "")
                    }
                }
            }
        }

        val rowMap = mutableMapOf<Long, Row>()
        subscribeTo(detailsViewModel.rowListData) { rowList ->
            val rows = rowList.map { rowId ->
                val row = rowMap[rowId] ?: createRowBy(rowId, rowsAdapter, getViewModel(rowId)!!)
                rowMap[rowId] = row
                row
            }
            rowsAdapter.setItems(rows, RowDiffCallback)
        }
    }

    private fun createRowBy(
        rowId: Long,
        rowsAdapter: ArrayObjectAdapter,
        viewModel: ViewModel,
    ): Row = when (rowId) {
        DetailsViewModel.RELEASE_ROW_ID -> createHeaderRowBy(
            rowId,
            rowsAdapter,
            viewModel as DetailHeaderViewModel
        )

        DetailsViewModel.RELATED_ROW_ID -> createFranchiseRowBy(
            rowId,
            rowsAdapter,
            viewModel as DetailRelatedViewModel
        )

        else -> createCardsRowBy(rowId, rowsAdapter, viewModel as BaseCardsViewModel)
    }

    private fun createHeaderRowBy(
        rowId: Long,
        rowsAdapter: ArrayObjectAdapter,
        viewModel: DetailHeaderViewModel,
    ): Row {
        val row = LibriaDetailsRow(rowId)
        subscribeTo(viewModel.releaseData) {
            val position = rowsAdapter.indexOf(row)
            row.details = it
            rowsAdapter.notifyArrayItemRangeChanged(position, 1)
        }
        subscribeTo(viewModel.progressState) {
            val position = rowsAdapter.indexOf(row)
            row.state = it
            rowsAdapter.notifyArrayItemRangeChanged(position, 1)
        }
        return row
    }

    private fun createFranchiseRowBy(
        rowId: Long,
        rowsAdapter: ArrayObjectAdapter,
        viewModel: DetailRelatedViewModel,
    ): Row {
        val cardsAdapter = ArrayObjectAdapter(
            ClassPresenterSelector().addClassPresenter(FranchiseCard::class.java, FranchiseCardPresenter())
        )
        val row = FranchiseListRow(rowId, HeaderItem(""), cardsAdapter)
        var selectionRequested = false
        subscribeTo(viewModel.franchiseData) { data ->
            data ?: return@subscribeTo
            cardsAdapter.setItems(data.cards, CardDiffCallback)
            if (row.headerItem?.name != data.title) {
                row.headerItem = HeaderItem(data.title)
                val position = rowsAdapter.indexOf(row)
                if (position >= 0) rowsAdapter.notifyArrayItemRangeChanged(position, 1)
            }
            // Один раз: ряд открывается на текущей части, DOWN с кнопок попадает на «ВЫ ЗДЕСЬ».
            if (!selectionRequested && data.currentIndex >= 0) {
                selectionRequested = true
                row.pendingSelection = data.currentIndex
                val position = rowsAdapter.indexOf(row)
                val holder = if (position >= 0) getRowViewHolder(position) else null
                if (holder != null) FranchiseListRowPresenter.applyPendingSelection(holder, row)
            }
        }
        return row
    }

    /**
     * Фокус ушёл с кнопок в ряды: выбранный ряд встаёт под компактную шапку,
     * ряды выше него (в т.ч. полная шапка) скрываются, фон темнеет. Вверх на кнопки — обратно.
     */
    private fun setupCollapsingHeader() {
        val grid = verticalGridView ?: return
        // LOW_EDGE: шапка-ряд в фокусе остаётся у верхнего края, остальные ряды — на отступе.
        grid.windowAlignment = BaseGridView.WINDOW_ALIGN_LOW_EDGE
        grid.windowAlignmentOffsetPercent = BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
        grid.windowAlignmentOffset = resources.getDimensionPixelSize(R.dimen.detail_rows_collapsed_top)
        grid.itemAlignmentOffset = 0
        grid.itemAlignmentOffsetPercent = BaseGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED
        hideRowsAboveSelected()
        grid.addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
            override fun onChildViewHolderSelected(
                parent: RecyclerView,
                child: RecyclerView.ViewHolder?,
                position: Int,
                subposition: Int,
            ) {
                val collapsed = position > 0
                compactHeader?.setShown(collapsed)
                background?.setCollapsed(collapsed)
            }
        })
    }

    private fun bindCompactHeader() {
        val details = headerViewModel.releaseData.value ?: return
        val partText = relatedViewModel.franchiseData.value?.partText
        compactHeader?.bind(
            titleText = details.titleRu,
            image = details.image.takeIf { it.isNotEmpty() },
            metaText = listOfNotNull(details.compactMeta.takeIf { it.isNotEmpty() }, partText)
                .joinToString(" · ")
        )
    }

}
