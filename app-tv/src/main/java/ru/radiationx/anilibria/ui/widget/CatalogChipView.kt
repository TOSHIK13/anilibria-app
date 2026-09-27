package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.text.inSpans

/**
 * Чип фильтра «Каталога»: «Жанры · все ▾». Подпись приглушённая, значение — medium белым;
 * выбранный (не по умолчанию) фильтр — [isActivated] (красная обводка фона), в фокусе — тёмный текст.
 * Если чипу не хватает места, сокращается значение, а подпись и стрелка остаются.
 */
class CatalogChipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    private companion object {
        const val LABEL_COLOR = 0x99FFFFFF.toInt()
        const val VALUE_COLOR = 0xFFFFFFFF.toInt()
        const val ARROW_COLOR = 0xEBFFFFFF.toInt()
        const val FOCUSED_COLOR = 0xFF111111.toInt()
        const val ARROW_SCALE = 0.6f
        const val SEPARATOR = " · "
        const val ARROW = "  ▼"
    }

    private var label: String = ""
    private var value: String = ""

    /** Значение, сокращённое под ширину чипа. */
    private var shownValue: CharSequence = ""

    private val measurePaint = TextPaint()

    fun bind(label: String, value: String, active: Boolean) {
        this.label = label
        this.value = value
        shownValue = value
        isActivated = active
        render()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val fitted = if (mode == MeasureSpec.UNSPECIFIED) {
            value
        } else {
            fitValue(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        }
        if (fitted.toString() != shownValue.toString()) {
            shownValue = fitted
            render()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        render()
    }

    private fun fitValue(available: Int): CharSequence {
        measurePaint.set(paint)
        val labelWidth = measurePaint.measureText("$label$SEPARATOR")
        measurePaint.textSize = paint.textSize * ARROW_SCALE
        val arrowWidth = measurePaint.measureText(ARROW)
        measurePaint.textSize = paint.textSize
        measurePaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val valueWidth = available - labelWidth - arrowWidth
        if (measurePaint.measureText(value) <= valueWidth) return value
        return TextUtils.ellipsize(value, measurePaint, valueWidth.coerceAtLeast(0f), TextUtils.TruncateAt.END)
    }

    private fun render() {
        val focused = isFocused
        text = buildSpannedString {
            color(if (focused) FOCUSED_COLOR else LABEL_COLOR) { append("$label$SEPARATOR") }
            inSpans(
                ForegroundColorSpan(if (focused) FOCUSED_COLOR else VALUE_COLOR),
                TypefaceSpan("sans-serif-medium"),
            ) {
                append(shownValue)
            }
            inSpans(
                ForegroundColorSpan(if (focused) FOCUSED_COLOR else ARROW_COLOR),
                RelativeSizeSpan(ARROW_SCALE),
            ) {
                append(ARROW)
            }
        }
    }
}
