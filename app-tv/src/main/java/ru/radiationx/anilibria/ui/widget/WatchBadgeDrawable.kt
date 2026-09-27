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
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import ru.radiationx.data.repository.ReleaseWatchProgress

/**
 * Оверлей постера, рисуется в overlay картинки без дополнительных вью.
 *
 * Индикатор просмотра в правом верхнем углу в формате `W/A(T)`:
 * W — просмотрено серий, A — вышло серий, T — всего запланировано.
 * Пример: `9/9(12)`. Если вышли все серии (A >= T) — без скобок: `12/12`.
 * T неизвестно — `W/A`; A ещё неизвестно — `W/T` (или просто `W`).
 * Нет прогресса — индикатор не рисуется.
 *
 * Бейдж «НОВАЯ» / «ФИЛЬМ» — тоже справа сверху, под индикатором (или в углу, если индикатора нет).
 * Левый верхний угол не занимаем — там водяной знак AniLibria на постерах.
 * Иконка коллекции пользователя — кружок в левом нижнем углу.
 * В фокусе — белая рамка по скруглённому контуру карточки.
 * Полностью просмотренный релиз — постер притемняется полупрозрачной заливкой («в тени»);
 * в фокусе затемнение снимается, чтобы карточка оставалась читаемой.
 */
class WatchBadgeDrawable(private val context: Context) : Drawable() {

    enum class Badge { NEW, FILM }

    private val density = context.resources.displayMetrics.density
    private val margin = 6 * density
    private val padH = 6 * density
    private val padV = 3 * density
    private val corner = 4 * density

    private val badgeGap = 4 * density
    private val badgeHeight = 20 * density

    private val collectionIconSize = 20 * density
    private val collectionIconPad = 4 * density

    private val focusStroke = 3 * density
    private val focusCorner = 8 * density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(184, 0, 0, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12 * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12 * context.resources.displayMetrics.scaledDensity
    }
    private val boldTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = focusStroke
        color = Color.WHITE
    }
    private val watchedDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 0, 0, 0)
    }
    private val collectionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(184, 0, 0, 0)
    }
    private val collectionIconCache = mutableMapOf<Int, Drawable>()
    private val rect = RectF()

    private var text: String? = null
    private var badge: Badge? = null
    private var focused = false
    private var watchedFully = false
    private var collectionIconResId: Int? = null
    private var collectionIcon: Drawable? = null

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

    fun setBadge(badge: Badge?) {
        if (this.badge == badge) return
        this.badge = badge
        invalidateSelf()
    }

    fun setFocused(focused: Boolean) {
        if (this.focused == focused) return
        this.focused = focused
        invalidateSelf()
    }

    fun setWatchedFully(watchedFully: Boolean) {
        if (this.watchedFully == watchedFully) return
        this.watchedFully = watchedFully
        invalidateSelf()
    }

    /** Иконка коллекции пользователя ([iconRes]) — кружок в левом нижнем углу; null — не в коллекции. */
    fun setCollectionIcon(@DrawableRes iconRes: Int?) {
        if (collectionIconResId == iconRes) return
        collectionIconResId = iconRes
        collectionIcon = iconRes?.let { id ->
            collectionIconCache.getOrPut(id) {
                ContextCompat.getDrawable(context, id)!!.mutate().apply { setTint(Color.WHITE) }
            }
        }
        invalidateSelf()
    }

    fun clear() {
        setProgress(null, null, null)
        setBadge(null)
        setWatchedFully(false)
        setCollectionIcon(null)
    }

    override fun draw(canvas: Canvas) {
        if (watchedFully && !focused) {
            canvas.drawRect(bounds, watchedDimPaint)
        }
        var nextTop = bounds.top + margin
        text?.let { nextTop = drawCounter(canvas, it) + badgeGap }
        badge?.let { drawBadge(canvas, it, nextTop) }
        collectionIcon?.let { drawCollectionIcon(canvas, it) }
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

    /** @return нижняя граница индикатора */
    private fun drawCounter(canvas: Canvas, text: String): Float {
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
        return rect.bottom
    }

    private fun drawBadge(canvas: Canvas, badge: Badge, top: Float) {
        val right = bounds.right - margin
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
        rect.set(right - textWidth - padH * 2, top, right, top + badgeHeight)
        canvas.drawRoundRect(rect, corner, corner, badgeBgPaint)
        val metrics = badgeTextPaint.fontMetrics
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2
        canvas.drawText(text, rect.centerX() - textWidth / 2, baseline, badgeTextPaint)
    }

    private fun drawCollectionIcon(canvas: Canvas, icon: Drawable) {
        val diameter = collectionIconSize + collectionIconPad * 2
        val left = bounds.left + margin
        val top = bounds.bottom - margin - diameter
        rect.set(left, top, left + diameter, top + diameter)
        canvas.drawRoundRect(rect, diameter / 2, diameter / 2, collectionBgPaint)
        val iconLeft = (rect.left + collectionIconPad).toInt()
        val iconTop = (rect.top + collectionIconPad).toInt()
        icon.setBounds(iconLeft, iconTop, (iconLeft + collectionIconSize).toInt(), (iconTop + collectionIconSize).toInt())
        icon.draw(canvas)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        val COLOR_RED = Color.rgb(0xE5, 0x39, 0x35)
    }
}
