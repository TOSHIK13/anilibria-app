package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import ru.radiationx.anilibria.R

/**
 * Корень карточки части франшизы (layout `card_franchise`).
 *
 * Белое кольцо фокуса 3dp рисуется СНАРУЖИ тела карточки — в поле 3dp вокруг него, внутри
 * своих границ (ряд его не срезает), как у [ContinueCardView]. В фокусе карточка поднимается
 * по Z, чтобы увеличенная карточка рисовалась поверх соседей; тени нет (контур пустой).
 */
class FranchiseCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val corner = 8 * density
    private val ringWidth = 3 * density
    private val focusedZ = 8 * density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = Color.WHITE
    }
    private val ringRect = RectF()

    private var body: View? = null
    private var ringVisible = false

    init {
        clipChildren = false
        clipToPadding = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setEmpty()
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val bodyView = findViewById<View>(R.id.franchiseBody)
        body = bodyView
        bodyView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }
        bodyView.clipToOutline = true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (ringVisible != gainFocus) {
            ringVisible = gainFocus
            translationZ = if (gainFocus) focusedZ else 0f
            invalidate()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val bodyView = body ?: return
        if (!ringVisible) return
        val half = ringWidth / 2
        ringRect.set(
            bodyView.left - half,
            bodyView.top - half,
            bodyView.right + half,
            bodyView.bottom + half
        )
        canvas.drawRoundRect(ringRect, corner + half, corner + half, ringPaint)
    }
}
