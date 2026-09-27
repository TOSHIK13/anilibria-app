package ru.radiationx.anilibria.ui.presenter.cust

import android.content.Context
import android.view.ViewGroup
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ListRowView
import androidx.leanback.widget.RowPresenter

open class CustomListRowPresenter @JvmOverloads constructor(
    focusZoomFactor: Int = FocusHighlight.ZOOM_FACTOR_MEDIUM,
    useFocusDimmer: Boolean = false,
) : ListRowPresenter(focusZoomFactor, useFocusDimmer) {

    /** Блок с названием и описанием карточки под выбранным рядом. */
    var descriptionEnabled: Boolean = true

    /** Ряд страницы с hero-блоком, см. [applyMainPageStyle]. */
    var isMainPageStyle: Boolean = false
        private set

    /**
     * Стиль главных страниц с hero-блоком: сведения о карточке показывает hero,
     * поэтому блок под рядом отключён, заголовки рядов — [MainRowHeaderPresenter].
     */
    fun applyMainPageStyle(): CustomListRowPresenter = apply {
        isMainPageStyle = true
        descriptionEnabled = false
        headerPresenter = MainRowHeaderPresenter()
    }

    override fun onRowViewExpanded(holder: RowPresenter.ViewHolder, expanded: Boolean) {
        super.onRowViewExpanded(holder, expanded)
        (holder as CustomListRowViewHolder).isExpanded = expanded
    }

    /**
     * Leanback пересоздаёт ItemBridgeAdapter ряда на каждый ребинд (например, когда рядом
     * меняется только заголовок — счётчик «(27 из 50+)» после «Загрузить еще») и сбрасывает
     * выбранную карточку на первую. Карточки при этом не трогаем — только возвращаем фокус
     * туда, где он был, чтобы позиция и скролл ряда не прыгали в начало.
     */
    override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
        val grid = (holder as? ListRowPresenter.ViewHolder)?.gridView
        val previousPosition = grid?.selectedPosition ?: -1
        super.onBindRowViewHolder(holder, item)
        val itemCount = grid?.adapter?.itemCount ?: 0
        if (grid != null && previousPosition in 0 until itemCount) {
            grid.selectedPosition = previousPosition
        }
    }

    override fun onRowViewSelected(holder: RowPresenter.ViewHolder, selected: Boolean) {
        super.onRowViewSelected(holder, selected)
        (holder as CustomListRowViewHolder).isSelected = selected
    }

    override fun createRowViewHolder(parent: ViewGroup): RowPresenter.ViewHolder {
        initStatics(parent.context)
        val rowView = ListRowView(parent.context)
        setupFadingEffect(rowView)
        if (rowHeight != 0) {
            rowView.gridView.setRowHeight(rowHeight)
        }
        return CustomListRowViewHolder(rowView, rowView.gridView, this, descriptionEnabled)
    }

    private fun setupFadingEffect(listRowView: ListRowView) {
        ListRowPresenter::class.java.getDeclaredMethod("setupFadingEffect", ListRowView::class.java)
            .let {
                it.isAccessible = true
                it.invoke(this, listRowView)
            }
    }

    private fun initStatics(context: Context) {
        ListRowPresenter::class.java.getDeclaredMethod("initStatics", Context::class.java)
            .let {
                it.isAccessible = true
                it.invoke(this, context)
            }
    }
}