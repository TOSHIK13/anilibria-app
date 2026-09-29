package ru.radiationx.anilibria.common

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Значок сервиса «AL» (AniList): скруглённый квадрат #02A9FF с тёмным текстом. */
fun serviceBadge(activityOrView: android.content.Context, label: String, sizeDp: Int, textSp: Float): TextView {
    val density = activityOrView.resources.displayMetrics.density
    return TextView(activityOrView).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = textSp
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(0xFF0B1622.toInt())
        includeFontPadding = false
        background = GradientDrawable().apply {
            cornerRadius = 8 * density
            setColor(0xFF02A9FF.toInt())
        }
        layoutParams = LinearLayout.LayoutParams((sizeDp * density).toInt(), (sizeDp * density).toInt())
    }
}

/**
 * Плашка снизу справа поверх текущего экрана (плеер, карточка, главная): не берёт фокус, скрывается
 * сама через [durationMs]. Новая плашка заменяет предыдущую. [badge] — значок сервиса слева
 * (null — без него), [check] — зелёная галочка справа.
 */
fun Activity.showCornerNotice(text: String, badge: String? = null, check: Boolean = false, durationMs: Long = 4_000) {
    val root = findViewById<ViewGroup>(android.R.id.content) ?: return
    root.findViewWithTag<View>(CORNER_NOTICE_TAG)?.let { root.removeView(it) }
    val density = resources.displayMetrics.density
    val box = LinearLayout(this).apply {
        tag = CORNER_NOTICE_TAG
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        elevation = 12 * density
        setPadding((20 * density).toInt(), (14 * density).toInt(), (20 * density).toInt(), (14 * density).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(0xF02C2E30.toInt())
            setStroke((1 * density).toInt(), 0x33FFFFFF)
        }
    }
    if (badge != null) {
        box.addView(serviceBadge(this, badge, 34, 13f).apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = (14 * density).toInt()
        })
    }
    box.addView(TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.WHITE)
        maxWidth = (560 * density).toInt()
    })
    if (check) {
        box.addView(TextView(this).apply {
            this.text = "✓"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF7BD88F.toInt())
            setPadding((14 * density).toInt(), 0, 0, 0)
        })
    }
    val margin = (40 * density).toInt()
    root.addView(
        box,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply { setMargins(margin, margin, margin, margin) }
    )
    box.postDelayed({ (box.parent as? ViewGroup)?.removeView(box) }, durationMs)
}

private const val CORNER_NOTICE_TAG = "corner_notice"
