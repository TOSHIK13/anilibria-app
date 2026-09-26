package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * Оверлей постера: сегментный прогресс просмотра внизу, бейдж в правом верхнем углу
 * и белая рамка фокуса. Рисуется в overlay картинки, без дополнительных вью.
 *
 * Сегменты: красный — просмотрено, светлый — вышло, контур — ещё не вышло.
 * Если сегменты слишком узкие (длинные сериалы) — сплошная полоса в тех же пропорциях.
 * Бейдж (по приоритету): «НОВАЯ» > «✓» > «ФИЛЬМ».
 */
class SegmentedProgressDrawable @JvmOverloads constructor(
    context: Context,
    /** Только полоса во всю ширину [getBounds] (без затенения, отступов и бейджа) — карточки франшизы. */
    private val barOnly: Boolean = false,
) : Drawable() {

    enum class Badge { NEW, COMPLETED, FILM }

    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity

    private val shadeHeight = 36 * density
    private val barInset = if (barOnly) 0f else 6 * density
    private val barHeight = 4 * density
    private val segmentGap = 1.5f * density
    private val segmentCorner = 1 * density
    private val minSegmentWidth = 2 * density
    private val outlineWidth = 1 * density

    private val badgeInset = 6 * density
    private val badgeHeight = 20 * density
    private val badgePadH = 6 * density
    private val badgeCorner = 4 * density

    private val focusStroke = 3 * density
    private val focusCorner = 8 * density

    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadeShaderHeight = -1f
    private val watchedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_RED }
    private val releasedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(204, 255, 255, 255)
    }
    private val pendingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = outlineWidth
        color = Color.argb(128, 255, 255, 255)
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12 * scaledDensity
    }
    private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = focusStroke
        color = Color.WHITE
    }
    private val rect = RectF()

    private var watched = 0
    private var released = 0
    private var total = 0
    private var showProgress = false
    private var badge: Badge? = null
    private var focused = false

    /**
     * @param watched просмотрено серий
     * @param released вышло серий, null — неизвестно
     * @param total всего серий (episodes_total), null — неизвестно
     */
    fun setState(
        watched: Int,
        released: Int?,
        total: Int?,
        isFilm: Boolean,
        badge: Badge?,
    ) {
        val count = (total ?: released ?: watched).coerceAtLeast(watched)
        val newShow = !isFilm && watched > 0 && count > 0
        val newReleased = (released ?: count).coerceIn(watched, count)
        if (newShow == showProgress && watched == this.watched && newReleased == this.released &&
            count == this.total && badge == this.badge
        ) return
        showProgress = newShow
        this.watched = watched
        this.released = newReleased
        this.total = count
        this.badge = badge
        invalidateSelf()
    }

    fun setFocused(focused: Boolean) {
        if (this.focused == focused) return
        this.focused = focused
        invalidateSelf()
    }

    fun clear() = setState(0, null, null, false, null)

    override fun draw(canvas: Canvas) {
        if (showProgress) {
            if (!barOnly) drawShade(canvas)
            drawBar(canvas)
        }
        badge?.let { drawBadge(canvas, it) }
        if (focused) {
            val half = focusStroke / 2
            rect.set(
                bounds.left + half,
                bounds.top + half,
                bounds.right - half,
                bounds.bottom - half
            )
            canvas.drawRoundRect(rect, focusCorner - half, focusCorner - half, focusPaint)
        }
    }

    private fun drawShade(canvas: Canvas) {
        if (shadeShaderHeight != shadeHeight + bounds.bottom) {
            shadeShaderHeight = shadeHeight + bounds.bottom
            shadePaint.shader = LinearGradient(
                0f, bounds.bottom.toFloat(), 0f, bounds.bottom - shadeHeight,
                Color.argb(191, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(
            bounds.left.toFloat(),
            bounds.bottom - shadeHeight,
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            shadePaint
        )
    }

    private fun drawBar(canvas: Canvas) {
        val left = bounds.left + barInset
        val right = bounds.right - barInset
        val bottom = bounds.bottom - barInset
        val top = bottom - barHeight
        val width = right - left
        val n = total
        val segmentWidth = (width - segmentGap * (n - 1)) / n
        if (segmentWidth >= minSegmentWidth) {
            for (i in 1..n) {
                val segLeft = left + (i - 1) * (segmentWidth + segmentGap)
                rect.set(segLeft, top, segLeft + segmentWidth, bottom)
                drawPart(canvas, i <= watched, i <= released)
            }
        } else {
            val watchedEnd = left + width * watched / n
            val releasedEnd = left + width * released / n
            if (releasedEnd < right) {
                rect.set(releasedEnd, top, right, bottom)
                drawPart(canvas, watched = false, released = false)
            }
            if (releasedEnd > watchedEnd) {
                rect.set(watchedEnd, top, releasedEnd, bottom)
                drawPart(canvas, watched = false, released = true)
            }
            rect.set(left, top, watchedEnd, bottom)
            drawPart(canvas, watched = true, released = true)
        }
    }

    private fun drawPart(canvas: Canvas, watched: Boolean, released: Boolean) {
        when {
            watched -> canvas.drawRoundRect(rect, segmentCorner, segmentCorner, watchedPaint)
            released -> canvas.drawRoundRect(rect, segmentCorner, segmentCorner, releasedPaint)
            else -> {
                rect.inset(outlineWidth / 2, outlineWidth / 2)
                canvas.drawRoundRect(rect, segmentCorner, segmentCorner, pendingPaint)
            }
        }
    }

    private fun drawBadge(canvas: Canvas, badge: Badge) {
        val right = bounds.right - badgeInset
        val top = bounds.top + badgeInset
        if (badge == Badge.COMPLETED) {
            badgeBgPaint.color = COLOR_GREEN
            val radius = badgeHeight / 2
            canvas.drawCircle(right - radius, top + radius, radius, badgeBgPaint)
            drawCentered(canvas, "✓", right - radius, top + radius, boldTypeface)
            return
        }
        val text: String
        val typeface: Typeface
        if (badge == Badge.NEW) {
            text = "НОВАЯ"
            typeface = boldTypeface
            badgeBgPaint.color = COLOR_RED
        } else {
            text = "ФИЛЬМ"
            typeface = mediumTypeface
            badgeBgPaint.color = Color.argb(184, 0, 0, 0)
        }
        badgeTextPaint.typeface = typeface
        val textWidth = badgeTextPaint.measureText(text)
        rect.set(right - textWidth - badgePadH * 2, top, right, top + badgeHeight)
        canvas.drawRoundRect(rect, badgeCorner, badgeCorner, badgeBgPaint)
        drawCentered(canvas, text, rect.centerX(), rect.centerY(), typeface)
    }

    private fun drawCentered(canvas: Canvas, text: String, cx: Float, cy: Float, typeface: Typeface) {
        badgeTextPaint.typeface = typeface
        val metrics = badgeTextPaint.fontMetrics
        val baseline = cy - (metrics.ascent + metrics.descent) / 2
        canvas.drawText(text, cx - badgeTextPaint.measureText(text) / 2, baseline, badgeTextPaint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        val COLOR_RED = Color.rgb(0xE5, 0x39, 0x35)
        val COLOR_GREEN = Color.rgb(0x2E, 0x7D, 0x32)
    }
}
