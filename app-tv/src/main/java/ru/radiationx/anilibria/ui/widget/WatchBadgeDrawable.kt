package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import ru.radiationx.data.repository.ReleaseWatchProgress

/**
 * Индикатор просмотра в правом верхнем углу постера:
 * «✓ 12 / 12» — досмотрено, «8 / 12» — начато, ничего — не начато.
 * Досмотренное рисуется той же плашкой, что и счётчик, с белой галочкой перед текстом.
 */
class WatchBadgeDrawable(context: Context) : Drawable() {

    private sealed class State {
        object None : State()
        data class Completed(val text: String?) : State()
        data class Counter(val text: String) : State()
    }

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
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val checkPath = Path()
    private val textBounds = Rect()

    /** Высота цифр — по ней масштабируется галочка, чтобы совпадать с текстом счётчика. */
    private val capHeight: Float = run {
        textPaint.getTextBounds("0", 0, 1, textBounds)
        textBounds.height().toFloat()
    }
    private val checkWidth = capHeight * 1.25f
    private val checkGap = textPaint.measureText(" ")

    private var state: State = State.None

    fun setProgress(progress: ReleaseWatchProgress?, fallbackTotal: Int?) {
        val newState = when {
            progress == null -> State.None
            else -> {
                val total = progress.total ?: fallbackTotal
                when {
                    total != null && progress.watched >= total -> State.Completed("$total / $total")
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
            is State.Completed -> drawCompleted(canvas, current.text)
            is State.Counter -> drawCounter(canvas, current.text)
        }
    }

    private fun drawCompleted(canvas: Canvas, text: String?) {
        val textWidth = text?.let { textPaint.measureText(it) } ?: 0f
        val contentWidth = checkWidth + if (text != null) checkGap + textWidth else 0f
        val metrics = textPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        rect.set(
            bounds.right - margin - contentWidth - padH * 2,
            bounds.top + margin,
            bounds.right - margin,
            bounds.top + margin + textHeight + padV * 2,
        )
        canvas.drawRoundRect(rect, corner, corner, backgroundPaint)

        // Галочка занимает высоту цифр и стоит на той же базовой линии, что и текст.
        val baseline = rect.top + padV - metrics.ascent
        val inset = checkPaint.strokeWidth / 2f
        val left = rect.left + padH + inset
        val right = rect.left + padH + checkWidth - inset
        val top = baseline - capHeight + inset
        val bottom = baseline - inset
        val w = right - left
        val h = bottom - top
        checkPath.reset()
        checkPath.moveTo(left, top + h * 0.55f)
        checkPath.lineTo(left + w * 0.36f, bottom)
        checkPath.lineTo(right, top)
        canvas.drawPath(checkPath, checkPaint)

        if (text != null) {
            canvas.drawText(text, rect.left + padH + checkWidth + checkGap, baseline, textPaint)
        }
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
