package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import ru.radiationx.data.repository.ReleaseWatchProgress

/**
 * Индикатор просмотра в правом верхнем углу постера:
 * галочка — досмотрено, «8 / 12» — начато, ничего — не начато.
 */
class WatchBadgeDrawable(context: Context) : Drawable() {

    private sealed class State {
        object None : State()
        object Completed : State()
        data class Counter(val text: String) : State()
    }

    private val density = context.resources.displayMetrics.density
    private val margin = 6 * density
    private val padH = 6 * density
    private val padV = 3 * density
    private val corner = 4 * density
    private val checkRadius = 11 * density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(184, 0, 0, 0)
    }
    private val completedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x4C, 0xAF, 0x50)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12 * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val checkPath = Path()

    private var state: State = State.None

    fun setProgress(progress: ReleaseWatchProgress?, fallbackTotal: Int?) {
        val newState = when {
            progress == null -> State.None
            else -> {
                val total = progress.total ?: fallbackTotal
                when {
                    total != null && progress.watched >= total -> State.Completed
                    total != null -> State.Counter("${progress.watched} / $total")
                    else -> State.Counter(progress.watched.toString())
                }
            }
        }
        if (newState != state) {
            state = newState
            invalidateSelf()
        }
    }

    fun clear() = setProgress(null, null)

    override fun draw(canvas: Canvas) {
        when (val current = state) {
            State.None -> Unit
            State.Completed -> drawCompleted(canvas)
            is State.Counter -> drawCounter(canvas, current.text)
        }
    }

    private fun drawCompleted(canvas: Canvas) {
        val cx = bounds.right - margin - checkRadius
        val cy = bounds.top + margin + checkRadius
        canvas.drawCircle(cx, cy, checkRadius, completedPaint)
        val s = checkRadius * 0.5f
        checkPath.reset()
        checkPath.moveTo(cx - s, cy + s * 0.05f)
        checkPath.lineTo(cx - s * 0.25f, cy + s * 0.75f)
        checkPath.lineTo(cx + s, cy - s * 0.6f)
        canvas.drawPath(checkPath, checkPaint)
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
