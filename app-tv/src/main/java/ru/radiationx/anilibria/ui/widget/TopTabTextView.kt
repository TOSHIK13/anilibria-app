package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import ru.radiationx.anilibria.R
import ru.radiationx.shared.ktx.android.getCompatColor

/**
 * Вкладка верхней панели: текущая страница — белый medium-текст с красной чертой снизу,
 * фокус — белая «таблетка» (фон из селектора) без черты.
 */
class TopTabTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    private companion object {
        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private var ready = false

    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getCompatColor(R.color.main_top_tab_underline)
    }
    private val underlineRect = RectF()
    private val underlineInset = resources.getDimension(R.dimen.main_top_tab_padding_horizontal)
    private val underlineHeight = resources.getDimension(R.dimen.main_top_tab_underline_height)
    private val underlineRadius = resources.getDimension(R.dimen.main_top_tab_underline_radius)
    private val underlineBottom = resources.getDimension(R.dimen.main_top_tab_underline_bottom)

    init {
        ready = true
        updateTypeface()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (ready) updateTypeface()
    }

    private fun updateTypeface() {
        val target = if (isFocused || isSelected) MEDIUM else REGULAR
        if (typeface != target) {
            typeface = target
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isSelected && !isFocused) {
            val bottom = height - underlineBottom
            underlineRect.set(
                scrollX + underlineInset,
                bottom - underlineHeight,
                scrollX + width - underlineInset,
                bottom
            )
            canvas.drawRoundRect(underlineRect, underlineRadius, underlineRadius, underlinePaint)
        }
    }
}
