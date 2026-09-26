package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import ru.radiationx.anilibria.R

/**
 * Корень карточки «Продолжить просмотр» (layout `card_continue`).
 *
 * - полоса таймкода — [overlayDrawable] в overlay области кадра;
 * - белое кольцо фокуса 3dp рисуется СНАРУЖИ кадра (в [dispatchDraw] карточки, в её поле 3dp
 *   вокруг кадра — внутри своих границ, ряд его не срезает), кадр и полоса видны целиком;
 *   foreground карточки пустой, поэтому затемнение ряда кольцо не трогает;
 * - затемнение ряда (Leanback ставит его foreground-ом карточки) переносится на область кадра,
 *   а подписи просто становятся прозрачнее;
 * - ряд рисуется ContinueListRowPresenter без тени/скругления Leanback; в фокусе поднимается
 *   вся карточка (рисуется поверх соседей), а её контур — только кадр, поэтому тень только под
 *   кадром, под подписями ничего нет;
 * - масштаб фокуса относительно точки 50% / 40% высоты карточки.
 */
class ContinueCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    val overlayDrawable = ContinueOverlayDrawable(context)

    private val density = resources.displayMetrics.density
    private val corner = 8 * density
    private val ringWidth = 3 * density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = Color.WHITE
    }
    private val ringRect = RectF()

    private var imageContainer: FrameLayout? = null
    private var labels: List<View> = emptyList()
    private var ringVisible = false

    private val focusedZ = 8 * density

    init {
        // Тень кадра и кольцо фокуса выходят за границы кадра/карточки.
        clipChildren = false
        clipToPadding = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val image = imageContainer ?: return outline.setEmpty()
                outline.setRoundRect(image.left, image.top, image.right, image.bottom, corner)
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val container = findViewById<FrameLayout>(R.id.continueImageContainer)
        imageContainer = container
        labels = listOf(findViewById(R.id.continueTitle), findViewById(R.id.continueSubtitle))
        container.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }
        container.clipToOutline = true
        container.overlay.add(overlayDrawable)
        container.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            overlayDrawable.setBounds(0, 0, v.width, v.height)
            invalidateOutline()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w / 2f
        pivotY = h * PIVOT_Y
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
        val image = imageContainer ?: return
        if (!ringVisible) return
        val half = ringWidth / 2
        ringRect.set(
            image.left - half,
            image.top - half,
            image.right + half,
            image.bottom + half
        )
        canvas.drawRoundRect(ringRect, corner + half, corner + half, ringPaint)
    }

    override fun setForeground(foreground: Drawable?) {
        val container = imageContainer
        if (container == null) {
            super.setForeground(foreground)
            return
        }
        super.setForeground(null)
        container.foreground = foreground
        val dim = (foreground as? ColorDrawable)?.let { Color.alpha(it.color) / 255f } ?: 0f
        labels.forEach { it.alpha = 1f - dim }
    }

    private companion object {
        const val PIVOT_Y = 0.4f
    }
}
