package ru.radiationx.anilibria.screen.profile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Переключатель строки настроек 40×22 dp: красная дорожка (вкл) / белая 25 % (выкл), белый кружок.
 * Сам фокус не берёт — переключается по OK на строке. На белой строке в фокусе (duplicateParentState)
 * выключенная дорожка тёмная, иначе белый кружок на белом фоне не виден.
 */
class SettingsSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    var isOn: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((40 * density).toInt(), widthMeasureSpec),
            resolveSize((22 * density).toInt(), heightMeasureSpec),
        )
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f
        val focused = android.R.attr.state_focused in drawableState
        trackPaint.color = when {
            isOn -> COLOR_ON
            focused -> COLOR_OFF_FOCUSED
            else -> COLOR_OFF
        }
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        val inset = 2 * density
        val knobRadius = radius - inset
        val cx = if (isOn) w - inset - knobRadius else inset + knobRadius
        canvas.drawCircle(cx, radius, knobRadius, knobPaint)
    }

    private companion object {
        const val COLOR_ON = 0xFFE53935.toInt()
        const val COLOR_OFF = 0x40FFFFFF
        const val COLOR_OFF_FOCUSED = 0x40000000
    }
}
