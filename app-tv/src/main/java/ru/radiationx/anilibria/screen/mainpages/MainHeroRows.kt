package ru.radiationx.anilibria.screen.mainpages

import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.VerticalGridView
import androidx.recyclerview.widget.RecyclerView

private const val ROW_FADE_MS = 150L

/**
 * Ряды страницы с hero-блоком. Leanback ставит выбранный ряд всегда на одну высоту
 * (browseRowsMarginTop, WINDOW_ALIGN_NO_EDGE) — под hero; ряды выше выбранного заехали бы
 * на тексты hero, поэтому они скрываются (как в Google TV).
 * Ряды, которые догружаются и вставляются над выбранным (например, «Продолжить просмотр»),
 * появляются без анимации: иначе анимация вставки проявляет их поверх hero.
 */
fun RowsSupportFragment.hideRowsAboveSelected() {
    val grid = verticalGridView ?: return
    grid.itemAnimator = null
    grid.addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
        override fun onChildViewHolderSelected(
            parent: RecyclerView,
            child: RecyclerView.ViewHolder?,
            position: Int,
            subposition: Int,
        ) {
            grid.updateRowsAlpha(animate = true)
        }
    })
    grid.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            view.animate().cancel()
            view.alpha = grid.rowAlpha(view)
        }

        override fun onChildViewDetachedFromWindow(view: View) {
            view.animate().cancel()
            view.alpha = 1f
        }
    })
}

private fun VerticalGridView.rowAlpha(child: View): Float {
    val selected = selectedPosition
    val position = getChildAdapterPosition(child)
    return if (selected > 0 && position in 0 until selected) 0f else 1f
}

private fun VerticalGridView.updateRowsAlpha(animate: Boolean) {
    for (i in 0 until childCount) {
        val child = getChildAt(i)
        val target = rowAlpha(child)
        if (child.alpha == target) continue
        child.animate().cancel()
        if (animate) {
            child.animate().alpha(target).setDuration(ROW_FADE_MS).start()
        } else {
            child.alpha = target
        }
    }
}
