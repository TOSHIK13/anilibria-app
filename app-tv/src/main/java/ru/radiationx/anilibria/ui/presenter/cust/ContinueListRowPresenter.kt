package ru.radiationx.anilibria.ui.presenter.cust

import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.RowPresenter
import ru.radiationx.anilibria.R

/** Ряд «Продолжить просмотр» (главная, «Я смотрю»): рисуется [ContinueListRowPresenter]. */
class ContinueListRow(id: Long, header: HeaderItem, adapter: ObjectAdapter) : ListRow(id, header, adapter)

/**
 * Ряд карточек «Продолжить просмотр»: без тени и скругления Leanback (они легли бы на всю
 * карточку вместе с подписями — карточка рисует тень и кольцо фокуса вокруг кадра сама)
 * и без обрезки детей по цепочке grid → ListRowView → контейнер ряда. Кольцо фокуса карточка
 * рисует в своём поле 3dp вокруг кадра, поэтому промежуток ряда уменьшен на 2×3dp.
 * Зум и затемнение — как у остальных рядов.
 */
class ContinueListRowPresenter : CustomListRowPresenter() {

    init {
        shadowEnabled = false
        enableChildRoundedCorners(false)
    }

    override fun initializeRowViewHolder(holder: RowPresenter.ViewHolder) {
        super.initializeRowViewHolder(holder)
        val grid = (holder as ListRowPresenter.ViewHolder).gridView
        // Карточка шире кадра на поле под кольцо фокуса — промежуток между кадрами остаётся 8dp.
        grid.setItemSpacing(grid.resources.getDimensionPixelSize(R.dimen.card_continue_row_spacing))
        // Здесь holder.view уже вложен в контейнер ряда (заголовок), но ещё не в список рядов.
        var view: View? = grid
        while (view is ViewGroup) {
            view.clipChildren = false
            view.clipToPadding = false
            view = view.parent as? View
        }
    }

    companion object {
        /** Презентер рядов экрана: [ContinueListRow] — отдельный, остальные [ListRow] — обычный. */
        fun rowsPresenterSelector() = ClassPresenterSelector()
            .addClassPresenter(ListRow::class.java, CustomListRowPresenter())
            .addClassPresenter(ContinueListRow::class.java, ContinueListRowPresenter())
    }
}
