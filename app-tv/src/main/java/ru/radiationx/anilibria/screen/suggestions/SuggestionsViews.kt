package ru.radiationx.anilibria.screen.suggestions

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.leanback.widget.Presenter
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.ui.presenter.LibriaCardPresenter

/**
 * Корень страницы «Поиск»: навигация фокуса между строкой поиска и блоками решается здесь,
 * до поиска соседа главным экраном ([onFocusSearch] вернул null — дальше решает главный экран,
 * например UP со строки поиска уходит на вкладки). Фокус, пришедший на страницу снаружи
 * (DOWN с вкладки, возврат с релиза), ставится на [onEntryFocus].
 */
class SuggestionsPageLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var onFocusSearch: ((focused: View, direction: Int) -> View?)? = null

    var onEntryFocus: (() -> View?)? = null

    override fun focusSearch(focused: View?, direction: Int): View? {
        if (focused != null) {
            onFocusSearch?.invoke(focused, direction)?.also { return it }
        }
        return super.focusSearch(focused, direction)
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val target = onEntryFocus?.invoke()
        if (target != null && target.requestFocus()) return true
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }
}

/**
 * Прокрутка блоков под строкой поиска. Стрелки и прокрутку к фокусу ScrollView не обрабатывает:
 * соседей ищет [SuggestionsPageLayout], прокручивает страница (блок в фокусе целиком на экране).
 */
class SuggestionsScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    override fun executeKeyEvent(event: KeyEvent): Boolean = false

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0
}

/**
 * Чипы с переносом строк («Недавние запросы», «Жанры»): не больше [maxLines] строк,
 * не поместившиеся чипы скрыты (и не получают фокус).
 */
class SuggestionsChipsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    var maxLines = 2
    var horizontalGap = 0
    var verticalGap = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var line = 0
        var lineWidth = 0
        var lineHeight = 0
        var height = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val width = child.measuredWidth
            if (lineWidth > 0 && lineWidth + horizontalGap + width > maxWidth) {
                height += lineHeight + verticalGap
                line++
                lineWidth = 0
                lineHeight = 0
            }
            val fits = line < maxLines
            val newVisibility = if (fits) VISIBLE else INVISIBLE
            if (child.visibility != newVisibility) child.visibility = newVisibility
            if (!fits) continue
            lineWidth += (if (lineWidth > 0) horizontalGap else 0) + width
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
        height += lineHeight
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != VISIBLE) continue
            val width = child.measuredWidth
            if (x > 0 && x + horizontalGap + width > maxWidth) {
                y += lineHeight + verticalGap
                x = 0
                lineHeight = 0
            }
            val left = paddingLeft + x + (if (x > 0) horizontalGap else 0)
            child.layout(left, y, left + width, y + child.measuredHeight)
            x = left - paddingLeft + width
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }
}

/**
 * Строка поиска: [onBackWithKeyboard] — BACK, пока открыта клавиатура (true — обработано страницей).
 * Клавиатура сама по фокусу не открывается — только по OK ([showSoftInputOnFocus] = false).
 */
class SuggestionsSearchInput @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatEditText(context, attrs) {

    /** Клавиатуру открыла страница (по OK) и ещё не закрыла. */
    var keyboardShown = false

    var onBackWithKeyboard: (() -> Unit)? = null

    init {
        showSoftInputOnFocus = false
    }

    /** Клавиатура на экране: открыта по OK или сама (например, при вводе с пульта-клавиатуры). */
    fun isKeyboardVisible(): Boolean = keyboardShown ||
            ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && onBackWithKeyboard != null && isKeyboardVisible()) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                onBackWithKeyboard?.invoke()
            }
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (!focused) keyboardShown = false
    }
}

/**
 * Постер ряда страницы «Поиск»: [LibriaCardPresenter] (кольцо, плашка прогресса), в фокусе — ×1,14.
 * [captions] — подписи под постером (название в 2 строки и «2020 · ТВ»), при фокусе они
 * опускаются под увеличенный постер.
 */
class SuggestionsPosterPresenter(
    private val captions: Boolean,
    private val onClick: (SuggestionsPosterItem) -> Unit,
    private val onFocus: (SuggestionsPosterItem) -> Unit,
) : Presenter() {

    private companion object {
        const val FOCUS_SCALE = 1.14f
        const val ANIMATION_MS = 150L
    }

    private val cardPresenter = LibriaCardPresenter()

    private class Holder(
        view: View,
        val card: ViewHolder,
        val captions: View?,
        val title: TextView?,
        val meta: TextView?,
    ) : ViewHolder(view) {
        var item: SuggestionsPosterItem? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        val res = context.resources
        val cardHolder = cardPresenter.onCreateViewHolder(parent)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        root.addView(cardHolder.view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        var captionsView: LinearLayout? = null
        var title: TextView? = null
        var meta: TextView? = null
        if (captions) {
            val width = res.getDimensionPixelSize(R.dimen.card_release_width)
            captionsView = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            title = TextView(context).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setLineSpacing(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, res.displayMetrics), 1f)
                setTextColor(0xFFFFFFFF.toInt())
                // Две строки всегда: «2020 · ТВ» у всех карточек на одной высоте.
                setLines(2)
            }
            meta = TextView(context).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(0x99FFFFFF.toInt())
            }
            captionsView.addView(title, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
            captionsView.addView(meta, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(res, 3f)
            })
            root.addView(captionsView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(res, 7f) })
        }
        val holder = Holder(root, cardHolder, captionsView, title, meta)
        val cardView = cardHolder.view
        val shift = res.getDimension(R.dimen.card_height) * (FOCUS_SCALE - 1f) / 2f
        cardView.setOnClickListener { holder.item?.also(onClick) }
        cardView.setOnFocusChangeListener { _, hasFocus ->
            val scale = if (hasFocus) FOCUS_SCALE else 1f
            cardView.animate().scaleX(scale).scaleY(scale).setDuration(ANIMATION_MS).start()
            holder.captions?.animate()?.translationY(if (hasFocus) shift else 0f)?.setDuration(ANIMATION_MS)?.start()
            if (hasFocus) holder.item?.also(onFocus)
        }
        return holder
    }

    /**
     * Высота карточки с подписями: у ряда постеров она фиксированная — HorizontalGridView
     * с wrap_content иначе меряется до появления карточек и остаётся низким.
     */
    fun itemHeight(parent: ViewGroup): Int {
        val res = parent.resources
        val cardHeight = res.getDimensionPixelSize(R.dimen.card_height)
        if (!captions) return cardHeight
        val holder = onCreateViewHolder(parent) as Holder
        holder.title?.text = "0"
        holder.meta?.text = "0"
        val captionsView = holder.captions ?: return cardHeight
        captionsView.measure(
            View.MeasureSpec.makeMeasureSpec(res.getDimensionPixelSize(R.dimen.card_release_width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val margin = (captionsView.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        return cardHeight + margin + captionsView.measuredHeight
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        viewHolder as Holder
        item as SuggestionsPosterItem
        viewHolder.item = item
        cardPresenter.onBindViewHolder(viewHolder.card, item.card)
        val focused = viewHolder.card.view.hasFocus()
        val scale = if (focused) FOCUS_SCALE else 1f
        viewHolder.card.view.apply {
            scaleX = scale
            scaleY = scale
        }
        if (!focused) viewHolder.captions?.translationY = 0f
        viewHolder.title?.text = item.title ?: item.card.title
        viewHolder.meta?.apply {
            text = item.meta
            isVisible = !item.meta.isNullOrEmpty()
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder as Holder
        viewHolder.item = null
        cardPresenter.onUnbindViewHolder(viewHolder.card)
    }

    private fun dp(res: android.content.res.Resources, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, res.displayMetrics).toInt()
}
