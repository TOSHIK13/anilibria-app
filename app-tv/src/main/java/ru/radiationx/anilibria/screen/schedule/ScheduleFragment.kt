package ru.radiationx.anilibria.screen.schedule

import android.os.Bundle
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.CardDiffCallback
import ru.radiationx.anilibria.common.GradientBackgroundManager
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LinkCard
import ru.radiationx.anilibria.common.LoadingCard
import ru.radiationx.anilibria.common.RowDiffCallback
import ru.radiationx.anilibria.screen.mainpages.hideRowsAboveSelected
import ru.radiationx.anilibria.ui.presenter.CardPresenterSelector
import ru.radiationx.anilibria.ui.presenter.cust.CustomListRowPresenter
import ru.radiationx.anilibria.ui.presenter.cust.CustomListRowViewHolder
import ru.radiationx.quill.inject
import ru.radiationx.shared.ktx.android.subscribeTo
import ru.radiationx.shared_app.di.quillParentViewModel

/**
 * «Расписание» — страница главного экрана (вкладка): ряд на каждый день недели.
 * ViewModel живёт в [ru.radiationx.anilibria.screen.mainpages.MainPagesFragment].
 */
class ScheduleFragment : RowsSupportFragment() {

    private val rowsPresenter by lazy { CustomListRowPresenter() }
    private val rowsAdapter by lazy { ArrayObjectAdapter(rowsPresenter) }

    private val viewModel by quillParentViewModel<ScheduleViewModel>()

    private val backgroundManager by inject<GradientBackgroundManager>()

    /**
     * Главный экран выравнивает ряды под hero-блок; у расписания hero нет,
     * поэтому выбранный ряд стоит сразу под вкладками.
     */
    override fun setAlignment(windowAlignOffsetFromTop: Int) {
        val top = context?.resources?.getDimensionPixelSize(R.dimen.schedule_rows_top_offset)
        super.setAlignment(top ?: windowAlignOffsetFromTop)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycle.addObserver(viewModel)

        backgroundManager.clearGradient()

        setOnItemViewSelectedListener { _, item, rowViewHolder, _ ->
            if (rowViewHolder is CustomListRowViewHolder) {
                when (item) {
                    is LibriaCard -> rowViewHolder.setDescription(item.title, item.description)
                    is LinkCard -> rowViewHolder.setDescription(item.title, "")
                    is LoadingCard -> rowViewHolder.setDescription(item.title, item.description)
                    else -> rowViewHolder.setDescription("", "")
                }
            }
        }

        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is LibriaCard) {
                viewModel.onCardClick(item)
            }
        }

        adapter = rowsAdapter
        // Ряды выше выбранного заехали бы под вкладки.
        hideRowsAboveSelected()

        subscribeTo(viewModel.scheduleRows) {
            val rows = it.mapIndexed { index, day ->
                val cardsPresenter = CardPresenterSelector(null)
                val cardsAdapter = ArrayObjectAdapter(cardsPresenter)
                cardsAdapter.setItems(day.second, CardDiffCallback)
                ListRow(index.toLong(), HeaderItem(day.first), cardsAdapter)
            }
            rowsAdapter.setItems(rows, RowDiffCallback)
        }
    }
}
