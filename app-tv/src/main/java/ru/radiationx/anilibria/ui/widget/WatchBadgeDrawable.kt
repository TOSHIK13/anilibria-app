package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import ru.radiationx.data.repository.ReleaseWatchProgress

/**
 * Индикатор просмотра в правом верхнем углу постера в формате `W/A(T)`:
 * W — просмотрено серий, A — вышло серий, T — всего запланировано.
 * Пример: `9/9(12)`. Если вышли все серии (A >= T) — без скобок: `12/12`.
 * T неизвестно — `W/A`; A ещё неизвестно — `W/T` (или просто `W`).
 * Нет прогресса — ничего не рисуется.
 */
class WatchBadgeDrawable(context: Context) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val margin = 6 * density
    private val padH = 6 * density
    private val padV = 3 * density
    private val corner = 4 * density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(184, 0, 0, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12 * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val rect = RectF()

    private var text: String? = null

    fun setProgress(progress: ReleaseWatchProgress?, available: Int?, fallbackTotal: Int?) {
        val newText = progress?.let { formatProgress(it.watched, available, it.total ?: fallbackTotal) }
        if (newText != text) {
            text = newText
            invalidateSelf()
        }
    }

    private fun formatProgress(watched: Int, available: Int?, total: Int?): String = when {
        available != null && total != null && available < total -> "$watched/$available($total)"
        available != null -> "$watched/$available"
        total != null -> "$watched/$total"
        else -> watched.toString()
    }

    fun clear() = setProgress(null, null, null)

    override fun draw(canvas: Canvas) {
        text?.let { drawCounter(canvas, it) }
    }

    private fun drawCounter(canvas: Canvas, text: String) {
        val textWidth = textPaint.measureText(text)
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        rect.set(
            bounds.right - margin - textWidth - padH * 2,
            bounds.top + margin,
            bounds.right - margin,
            bounds.top + margin + textHeight + padV * 2,
        )
        canvas.drawRoundRect(rect, corner, corner, backgroundPaint)
        canvas.drawText(text, rect.left + padH, rect.top + padV - metrics.ascent, textPaint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
