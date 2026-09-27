package ru.radiationx.anilibria.screen.schedule

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Корень страницы «Расписание»: навигация фокуса по сетке недели решается здесь, до поиска
 * соседа главным экраном ([onFocusSearch] вернул null — дальше решает главный экран, например
 * UP из первой строки уходит на вкладки). Фокус, пришедший на страницу снаружи (DOWN с вкладки,
 * возврат с релиза), ставится на [onEntryFocus].
 */
class ScheduleWeekLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var onFocusSearch: ((focused: View, direction: Int) -> View?)? = null

    var onEntryFocus: (() -> View?)? = null

    override fun focusSearch(focused: View?, direction: Int): View? {
        if (focused != null) {
            onFocusSearch?.invoke(focused, direction)?.also { return it }
        }
        return super.focusSearch(focused, direction)
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val target = onEntryFocus?.invoke()
        if (target != null && target.requestFocus()) return true
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }
}

/**
 * Область строк колонки недели: список внутри меряется без ограничения по высоте
 * и прокручивается [View.scrollTo] в пределах этой области (не заезжая под заголовок дня и вкладки).
 */
class ScheduleColumnBody @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    override fun measureChildWithMargins(
        child: View,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int,
    ) {
        val lp = child.layoutParams as MarginLayoutParams
        val widthSpec = getChildMeasureSpec(
            parentWidthMeasureSpec,
            paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin + widthUsed,
            lp.width
        )
        child.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }
}
