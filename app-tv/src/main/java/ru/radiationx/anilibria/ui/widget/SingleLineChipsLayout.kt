package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * Плашки в одну строку без переноса: те, что не влезают в ширину, отбрасываются с конца.
 * [gap] — расстояние между плашками.
 */
class SingleLineChipsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    var gap: Int = (6 * resources.displayMetrics.density).toInt()
        set(value) {
            field = value
            requestLayout()
        }

    /** Сколько первых детей влезло при последнем измерении. */
    private var fittedCount = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        }
        val childSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var used = 0
        var height = 0
        fittedCount = 0
        var overflow = false
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(child, childSpec, heightMeasureSpec)
            val add = child.measuredWidth + if (fittedCount > 0) gap else 0
            if (overflow || used + add > maxWidth) {
                overflow = true
                continue
            }
            used += add
            height = max(height, child.measuredHeight)
            fittedCount++
        }
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(widthMeasureSpec)
        } else {
            used + paddingLeft + paddingRight
        }
        setMeasuredDimension(
            width,
            resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = paddingLeft
        var placed = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (placed >= fittedCount) {
                // Не влезла — убираем из отрисовки, не меняя visibility.
                child.layout(0, 0, 0, 0)
                continue
            }
            val top = paddingTop + (measuredHeight - paddingTop - paddingBottom - child.measuredHeight) / 2
            child.layout(x, top, x + child.measuredWidth, top + child.measuredHeight)
            x += child.measuredWidth + gap
            placed++
        }
    }
}
