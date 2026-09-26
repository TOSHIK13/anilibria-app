package ru.radiationx.anilibria.screen.details

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import ru.radiationx.shared_app.imageloader.showImageUrl

/**
 * Компактная шапка деталей: видна, пока фокус в рядах под кнопками (сама шапка-ряд
 * при этом уехала вверх и скрыта). Мини-постер 42x60dp, название 22sp и «год · тип · серии ·
 * часть k из m». Не фокусируется, лежит поверх рядов.
 */
class DetailCompactHeaderView(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density

    private val poster = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.parseColor("#222222"))
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 4 * density)
            }
        }
        clipToOutline = true
    }

    private val title = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val meta = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.argb(217, 255, 255, 255))
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private var posterUrl: String? = null
    private var shown = false

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        alpha = 0f
        visibility = INVISIBLE
        addView(poster, LayoutParams(dp(42), dp(60)).apply {
            leftMargin = dp(56)
            topMargin = dp(22)
        })
        addView(title, LayoutParams(dp(750), LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(110)
            topMargin = dp(26)
        })
        addView(meta, LayoutParams(dp(750), LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(110)
            topMargin = dp(58)
        })
    }

    fun bind(titleText: String, image: String?, metaText: String) {
        title.text = titleText
        meta.text = metaText
        if (posterUrl != image) {
            posterUrl = image
            poster.showImageUrl(image)
        }
    }

    fun setShown(show: Boolean) {
        if (shown == show) return
        shown = show
        animate().cancel()
        if (show) {
            visibility = VISIBLE
            animate().alpha(1f).setDuration(FADE_MS).start()
        } else {
            animate().alpha(0f).setDuration(FADE_MS).withEndAction { if (!shown) visibility = INVISIBLE }.start()
        }
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private companion object {
        const val FADE_MS = 150L
    }
}
