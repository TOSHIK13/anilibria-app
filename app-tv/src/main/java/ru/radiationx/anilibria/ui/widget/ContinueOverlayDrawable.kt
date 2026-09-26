package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Оверлей кадра карточки «Продолжить просмотр»: полоса таймкода внизу.
 * Кольцо фокуса рисует [ContinueCardView] снаружи кадра, чтобы не закрывать полосу.
 */
class ContinueOverlayDrawable(context: Context) : Drawable() {

    private val density = context.resources.displayMetrics.density

    private val barHeight = 3 * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(71, 255, 255, 255)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xE5, 0x39, 0x35)
    }
    private val rect = RectF()

    /** Доля просмотренного 0..1, null — полосы нет (длительность неизвестна). */
    private var progress: Float? = null

    fun setProgress(progress: Float?) {
        val value = progress?.coerceIn(0f, 1f)
        if (value == this.progress) return
        this.progress = value
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        progress?.let { value ->
            val top = bounds.bottom - barHeight
            rect.set(bounds.left.toFloat(), top, bounds.right.toFloat(), bounds.bottom.toFloat())
            canvas.drawRect(rect, trackPaint)
            rect.right = bounds.left + bounds.width() * value
            canvas.drawRect(rect, fillPaint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
