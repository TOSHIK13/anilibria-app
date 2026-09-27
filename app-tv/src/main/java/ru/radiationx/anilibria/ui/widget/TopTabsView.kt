package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.leanback.widget.TitleViewAdapter
import ru.radiationx.anilibria.R

/**
 * Верхняя панель вкладок главного экрана. Подставляется в BrowseSupportFragment вместо
 * стандартного title view (id browse_title_group), поэтому скрывается/показывается
 * штатным TitleHelper'ом при прокрутке рядов.
 */
class TopTabsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), TitleViewAdapter.Provider {

    private lateinit var container: LinearLayout
    lateinit var logoView: ImageView
        private set

    private val tabViews = mutableListOf<TextView>()
    private var alertView: TextView? = null

    var selectedIndex: Int = -1
        private set

    var onTabClickListener: ((index: Int) -> Unit)? = null

    /** Любая кнопка пульта на вкладке (событие не поглощается). */
    var onTabKeyListener: ((event: KeyEvent) -> Unit)? = null

    private val titleAdapter = object : TitleViewAdapter() {
        override fun getSearchAffordanceView(): View? = null
    }

    override fun getTitleViewAdapter(): TitleViewAdapter = titleAdapter

    override fun onFinishInflate() {
        super.onFinishInflate()
        container = findViewById(R.id.top_tabs_container)
        logoView = findViewById(R.id.top_tabs_logo)
    }

    fun setTabs(titles: List<CharSequence>) {
        tabViews.forEach { container.removeView(it) }
        tabViews.clear()
        val inflater = LayoutInflater.from(context)
        val gap = resources.getDimensionPixelSize(R.dimen.main_top_tab_gap)
        titles.forEachIndexed { index, title ->
            val tab = inflater.inflate(R.layout.item_main_top_tab, container, false) as TextView
            tab.text = title
            tab.setOnClickListener { onTabClickListener?.invoke(index) }
            tab.setOnKeyListener { _, _, event ->
                onTabKeyListener?.invoke(event)
                false
            }
            (tab.layoutParams as MarginLayoutParams).marginStart = if (index == 0) 0 else gap
            container.addView(tab, index)
            tabViews.add(tab)
        }
        setSelectedTab(selectedIndex)
    }

    fun setSelectedTab(index: Int) {
        selectedIndex = index
        tabViews.forEachIndexed { i, tab -> tab.isSelected = i == index }
    }

    fun getTabView(index: Int): View? = tabViews.getOrNull(index)

    fun indexOfTab(view: View): Int = tabViews.indexOf(view)

    fun focusTab(index: Int): Boolean = tabViews.getOrNull(index)?.requestFocus() == true

    /** Пилюля после вкладок (например, «Обновление»); null — скрыть. */
    fun setAlert(text: CharSequence?, listener: OnClickListener?) {
        val alert = alertView ?: createAlertView().also { alertView = it }
        alert.text = text
        alert.setOnClickListener(listener)
        alert.isVisible = !text.isNullOrEmpty()
    }

    private fun createAlertView(): TextView {
        val alert = LayoutInflater.from(context)
            .inflate(R.layout.item_main_top_tab, container, false) as TextView
        alert.setBackgroundResource(R.drawable.bg_main_top_tab_alert)
        alert.setTextColor(ContextCompat.getColorStateList(context, R.color.main_top_tab_alert_text))
        (alert.layoutParams as MarginLayoutParams).marginStart =
            resources.getDimensionPixelSize(R.dimen.main_top_tab_alert_margin_start)
        alert.isVisible = false
        container.addView(alert)
        return alert
    }

    /**
     * LEFT/RIGHT по вкладкам без зацикливания; на краях фокус остаётся на месте.
     * Возвращает null, если [focused] не принадлежит панели или направление не горизонтальное.
     */
    fun findNextHorizontal(focused: View, direction: Int): View? {
        val items = buildList {
            addAll(tabViews)
            alertView?.takeIf { it.isVisible }?.also { add(it) }
        }
        val index = items.indexOf(focused)
        if (index < 0) return null
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val step = when (direction) {
            View.FOCUS_LEFT -> if (rtl) 1 else -1
            View.FOCUS_RIGHT -> if (rtl) -1 else 1
            else -> return null
        }
        return items.getOrNull(index + step) ?: focused
    }

    /** Фокус, пришедший в панель извне (например, UP из контента), встаёт на текущую вкладку. */
    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        if (focusTab(selectedIndex)) return true
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        // Снаружи панель доступна только как «текущая вкладка», чтобы FocusFinder не выбирал
        // случайную вкладку по геометрии.
        if (!hasFocus() && descendantFocusability != ViewGroup.FOCUS_BLOCK_DESCENDANTS) {
            val selected = tabViews.getOrNull(selectedIndex)
            if (selected != null && selected.isShown) {
                selected.addFocusables(views, direction, focusableMode)
                return
            }
        }
        super.addFocusables(views, direction, focusableMode)
    }
}
