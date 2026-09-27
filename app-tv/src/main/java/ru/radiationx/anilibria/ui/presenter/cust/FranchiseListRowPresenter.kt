package ru.radiationx.anilibria.ui.presenter.cust

import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.RowPresenter
import ru.radiationx.anilibria.R

/**
 * Ряд франшизы на экране деталей. [pendingSelection] — карточка, которая должна стать
 * выбранной в ряду при первом показе (открытый релиз, чтобы DOWN с кнопок попадал на него).
 */
class FranchiseListRow(id: Long, header: HeaderItem, adapter: ObjectAdapter) : ListRow(id, header, adapter) {

    var pendingSelection: Int = -1
}

/**
 * Ряд карточек франшизы: как [ContinueListRowPresenter] (без тени/скругления Leanback, кольцо
 * фокуса рисует карточка в своём поле 3dp, шаг карточек 260 + 8dp), без блока описания под
 * рядом — всё нужное есть на самой карточке.
 */
class FranchiseListRowPresenter : ContinueListRowPresenter() {

    init {
        descriptionEnabled = false
    }

    override fun initializeRowViewHolder(holder: RowPresenter.ViewHolder) {
        super.initializeRowViewHolder(holder)
        val grid = (holder as ListRowPresenter.ViewHolder).gridView
        // Тело карточки — там же, где было бы без поля 3dp под кольцо: на одной линии
        // с заголовком ряда слева и ближе к заголовку по вертикали.
        val ring = grid.resources.getDimensionPixelSize(R.dimen.card_continue_ring)
        grid.translationY = -ring.toFloat()
        // Не padding: ListRowPresenter восстанавливает его из сохранённых в ViewHolder значений.
        grid.translationX = -ring.toFloat()
        // Ряд короткий: не прокручиваем, пока карточка в фокусе целиком видна
        // (центр выбранной выравнивается на 80% ширины, у краёв — по краям).
        grid.windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        grid.windowAlignmentOffsetPercent = FOCUS_KEYLINE_PERCENT
    }

    override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
        super.onBindRowViewHolder(holder, item)
        applyPendingSelection(holder, item as? FranchiseListRow ?: return)
    }

    companion object {

        private const val FOCUS_KEYLINE_PERCENT = 80f

        /** Выбирает [FranchiseListRow.pendingSelection] в ряду, если карточки уже есть. */
        fun applyPendingSelection(holder: RowPresenter.ViewHolder, row: FranchiseListRow) {
            val position = row.pendingSelection
            val grid = (holder as? ListRowPresenter.ViewHolder)?.gridView ?: return
            if (position < 0 || position >= row.adapter.size()) return
            row.pendingSelection = -1
            grid.selectedPosition = position
        }
    }
}
