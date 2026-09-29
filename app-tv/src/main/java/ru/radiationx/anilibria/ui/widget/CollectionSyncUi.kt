package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import ru.radiationx.anilibria.common.DetailCollectionSync
import ru.radiationx.anilibria.common.SyncKind
import ru.radiationx.anilibria.common.serviceBadge
import ru.radiationx.anilibria.screen.services.timeText

/**
 * Значок «AL + ✓ / часы / !» справа на кнопке коллекции. Состояние «в фокусе» берётся из состояния
 * drawable (TextView прокидывает его в compound-drawables): на светлой кнопке значок светлый,
 * на тёмной — приглушённый.
 */
class SyncBadgeDrawable(context: Context, private val kind: SyncKind) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.8f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = 10f * density
    }
    private val textWidth = label.measureText("AL")
    private val padding = 6f * density
    private val gap = 4f * density
    private val glyph = 10f * density
    private var focused = false

    private val w = (padding * 2 + textWidth + gap + glyph).toInt()
    private val h = (18 * density).toInt()

    override fun getIntrinsicWidth() = w
    override fun getIntrinsicHeight() = h
    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val f = state.contains(android.R.attr.state_focused)
        if (f == focused) return false
        focused = f
        invalidateSelf()
        return true
    }

    private fun colors(): Pair<Int, Int> = when (kind) {
        SyncKind.SYNCED -> if (focused) 0xFFD9F2FF.toInt() to 0xFF075985.toInt() else 0x3302A9FF to 0xFF6CC6FF.toInt()
        SyncKind.PENDING -> if (focused) 0xFFFDF0CC.toInt() to 0xFF7A4D00.toInt() else 0x33F2C14E to 0xFFF2C14E.toInt()
        SyncKind.ERROR -> if (focused) 0xFFFFDAD6.toInt() to 0xFF93000A.toInt() else 0x33FF8A80 to 0xFFFF8A80.toInt()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val (bg, fg) = colors()
        box.color = bg
        canvas.drawRoundRect(RectF(b), 6 * density, 6 * density, box)
        label.color = fg
        ink.color = fg
        val baseline = b.exactCenterY() - (label.ascent() + label.descent()) / 2
        canvas.drawText("AL", b.left + padding, baseline, label)
        val cx = b.right - padding - glyph / 2
        val cy = b.exactCenterY()
        val r = glyph / 2 - 0.5f * density
        when (kind) {
            SyncKind.SYNCED -> {
                val p = android.graphics.Path().apply {
                    moveTo(cx - r * 0.8f, cy + r * 0.05f)
                    lineTo(cx - r * 0.2f, cy + r * 0.65f)
                    lineTo(cx + r * 0.85f, cy - r * 0.6f)
                }
                canvas.drawPath(p, ink)
            }

            SyncKind.PENDING -> {
                canvas.drawCircle(cx, cy, r, ink)
                canvas.drawLine(cx, cy, cx, cy - r * 0.55f, ink)
                canvas.drawLine(cx, cy, cx + r * 0.45f, cy + r * 0.2f, ink)
            }

            SyncKind.ERROR -> {
                canvas.drawLine(cx, cy - r * 0.85f, cx, cy + r * 0.15f, ink)
                canvas.drawPoint(cx, cy + r * 0.8f, ink)
            }
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun onBoundsChange(bounds: Rect) = Unit
}

/**
 * Всплывающая подсказка под кнопкой коллекции: «AL · Смотрю · 5/11 · синхр. 14:02».
 * Показывается после [DELAY_MS] удержания фокуса, скрывается при уходе фокуса и через [SHOW_MS].
 */
class CollectionSyncTooltip(private val anchor: View) {

    private companion object {
        const val DELAY_MS = 1_000L
        const val SHOW_MS = 5_000L
    }

    private var popup: PopupWindow? = null
    private var info: DetailCollectionSync? = null
    private val showRunnable = Runnable { show() }
    private val hideRunnable = Runnable { dismiss() }

    fun setInfo(value: DetailCollectionSync?) {
        info = value
        if (value == null) {
            cancel()
        } else if (popup?.isShowing == true) {
            dismiss()
            show()
        } else if (anchor.isFocused) {
            schedule()
        }
    }

    /** Вызывать при смене фокуса кнопки. */
    fun onFocusChanged(focused: Boolean) {
        if (focused) schedule() else cancel()
    }

    fun cancel() {
        anchor.removeCallbacks(showRunnable)
        anchor.removeCallbacks(hideRunnable)
        dismiss()
    }

    private fun schedule() {
        anchor.removeCallbacks(showRunnable)
        if (info != null) anchor.postDelayed(showRunnable, DELAY_MS)
    }

    private fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun show() {
        val data = info ?: return
        if (!anchor.isFocused || !anchor.isAttachedToWindow) return
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(0xFF2C2E30.toInt())
                setStroke((1 * density).toInt(), 0x33FFFFFF)
            }
        }
        content.addView(serviceBadge(context, "AL", 24, 10f).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = (10 * density).toInt()
        })
        content.addView(TextView(context).apply {
            text = "${data.statusText} · ${data.progressText}"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        content.addView(TextView(context).apply {
            when (data.kind) {
                SyncKind.SYNCED -> {
                    text = "синхр. " + timeText(data.syncedAt)
                    setTextColor(0xFF7BD88F.toInt())
                }

                SyncKind.PENDING -> {
                    text = "в очереди"
                    setTextColor(0xFFF2C14E.toInt())
                }

                SyncKind.ERROR -> {
                    text = "ошибка"
                    setTextColor(0xFFFF8A80.toInt())
                }
            }
            textSize = 12f
            setPadding((16 * density).toInt(), 0, 0, 0)
        })
        content.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val window = PopupWindow(content, content.measuredWidth, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = false
            isFocusable = false
            elevation = 12 * density
        }
        // ширина по содержимому; не влезает вправо — прижимаем к правому краю экрана с отступом
        val loc = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val margin = (24 * density).toInt()
        val screenW = context.resources.displayMetrics.widthPixels
        val x = loc[0].coerceAtMost(screenW - margin - content.measuredWidth).coerceAtLeast(margin) - loc[0]
        runCatching { window.showAsDropDown(anchor, x, (6 * density).toInt()) }.onSuccess {
            popup = window
            anchor.removeCallbacks(hideRunnable)
            anchor.postDelayed(hideRunnable, SHOW_MS)
        }
    }
}
