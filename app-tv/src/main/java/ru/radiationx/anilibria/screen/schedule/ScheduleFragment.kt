package ru.radiationx.anilibria.screen.schedule

import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.GradientBackgroundManager
import ru.radiationx.anilibria.databinding.FragmentScheduleBinding
import ru.radiationx.anilibria.databinding.ItemScheduleWeekRowBinding
import ru.radiationx.anilibria.screen.schedule.ScheduleViewModel.Cell
import ru.radiationx.anilibria.screen.schedule.ScheduleViewModel.State
import ru.radiationx.anilibria.screen.schedule.ScheduleViewModel.WeekDay
import ru.radiationx.anilibria.screen.schedule.ScheduleViewModel.WeekItem
import ru.radiationx.quill.inject
import ru.radiationx.shared.ktx.android.subscribeTo
import ru.radiationx.shared_app.di.quillParentViewModel
import ru.radiationx.shared_app.imageloader.showImageUrl
import kotlin.math.max

/**
 * «Расписание» — страница главного экрана (вкладка), неделя по макету f-wk: 7 колонок от сегодняшнего
 * дня (по МСК), в колонке до 6 строк и «ещё N», которое разворачивает колонку целиком.
 * Фокус ходит стрелками по сетке: LEFT/RIGHT — в соседний непустой день на строку с тем же индексом,
 * UP из первой строки — на вкладки. ViewModel живёт в [ru.radiationx.anilibria.screen.mainpages.MainPagesFragment].
 */
class ScheduleFragment : Fragment(R.layout.fragment_schedule),
    BrowseSupportFragment.MainFragmentAdapterProvider {

    private companion object {
        const val SCROLL_DURATION_MS = 150L
        const val DOT = "●"
        const val ZERO_WIDTH_SPACE = '\u200B'
        val DOT_COLOR = Color.parseColor("#FF8A80")
        val DOT_FOCUSED_COLOR = Color.parseColor("#C62828")
        val HEADER_TEXT_COLOR = Color.parseColor("#D9FFFFFF")
        val HEADER_SUBTITLE_COLOR = Color.parseColor("#8CFFFFFF")
        val HEADER_LINE_COLOR = Color.parseColor("#1FFFFFFF")
        val HEADER_TODAY_LINE_COLOR = Color.parseColor("#E53935")
        val EMPTY_TEXT_COLOR = Color.parseColor("#8CFFFFFF")
    }

    /** Колонка дня: фокусируемые элементы по порядку — строки, затем «ещё N» (если колонка свёрнута). */
    private class Column(
        val day: WeekDay,
        val body: ScheduleColumnBody,
        val list: LinearLayout,
        val targets: MutableList<View>,
        var more: View?,
        var scrollAnimator: ObjectAnimator? = null,
    )

    private val selfMainFragmentAdapter by lazy { BrowseSupportFragment.MainFragmentAdapter(this) }

    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<*> {
        return selfMainFragmentAdapter
    }

    private val viewModel by quillParentViewModel<ScheduleViewModel>()

    private val backgroundManager by inject<GradientBackgroundManager>()

    private var binding: FragmentScheduleBinding? = null
    private var columns: List<Column> = emptyList()
    private var renderedDays: List<WeekDay>? = null

    /** После «Повторить» — фокус на неделю, как только она загрузится. */
    private var focusWeekOnContent = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = FragmentScheduleBinding.bind(view)
        this.binding = binding

        viewLifecycleOwner.lifecycle.addObserver(viewModel)
        backgroundManager.clearGradient()

        binding.root.onFocusSearch = ::onFocusSearch
        binding.root.onEntryFocus = ::entryFocusTarget

        binding.scheduleRetry.setOnClickListener {
            focusWeekOnContent = true
            viewModel.onRetryClick()
        }

        subscribeTo(viewModel.state) { render(it) }

        selfMainFragmentAdapter.fragmentHost.notifyViewCreated(selfMainFragmentAdapter)
        selfMainFragmentAdapter.fragmentHost.notifyDataReady(selfMainFragmentAdapter)
    }

    override fun onDestroyView() {
        columns.forEach { it.scrollAnimator?.cancel() }
        columns = emptyList()
        renderedDays = null
        binding = null
        super.onDestroyView()
    }

    private fun render(state: State) {
        val binding = binding ?: return
        binding.scheduleProgress.isVisible = state is State.Loading
        binding.scheduleError.isVisible = state is State.Error
        binding.scheduleWeek.isVisible = state is State.Content
        if (state is State.Error && (binding.root.hasFocus() || focusWeekOnContent)) {
            // «Повторить» снова не удалось — фокус обратно на кнопку.
            focusWeekOnContent = false
            if (binding.root.isShown) binding.scheduleRetry.requestFocus()
        }
        if (state !is State.Content) return

        if (state.days != renderedDays) {
            renderedDays = state.days
            buildWeek(binding, state.days)
        }
        if (focusWeekOnContent) {
            focusWeekOnContent = false
            if (binding.root.isShown) {
                entryFocusTarget()?.requestFocus()
            }
        }
    }

    private fun buildWeek(binding: FragmentScheduleBinding, days: List<WeekDay>) {
        val container = binding.scheduleWeek
        container.removeAllViews()
        val res = resources
        val columnWidth = res.getDimensionPixelSize(R.dimen.schedule_week_column_width)
        val columnGap = res.getDimensionPixelSize(R.dimen.schedule_week_column_gap)
        val expanded = viewModel.expandedDays.value
        columns = days.mapIndexed { index, day ->
            val columnView = LinearLayout(container.context).apply {
                orientation = LinearLayout.VERTICAL
            }
            container.addView(
                columnView,
                LinearLayout.LayoutParams(columnWidth, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginStart = if (index == 0) 0 else columnGap
                }
            )
            columnView.addView(
                createHeader(columnView, day),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    res.getDimensionPixelSize(R.dimen.schedule_week_header_height)
                )
            )
            val body = ScheduleColumnBody(columnView.context)
            columnView.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val list = LinearLayout(body.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, res.getDimensionPixelSize(R.dimen.schedule_week_list_top), 0, dp(8f))
            }
            body.addView(
                list,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            val column = Column(day, body, list, mutableListOf(), null)
            fillColumn(column, expanded = day.day in expanded)
            column
        }
    }

    private fun createHeader(parent: ViewGroup, day: WeekDay): View {
        val context = parent.context
        val header = FrameLayout(context)
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // Базовые линии «Сегодня» и приглушённого «Вс» совпадают, как в макете.
            isBaselineAligned = true
        }
        texts.addView(TextView(context).apply {
            text = day.title
            includeFontPadding = false
            maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (day.isToday) Color.WHITE else HEADER_TEXT_COLOR)
        })
        day.subtitle?.also { subtitle ->
            texts.addView(TextView(context).apply {
                text = subtitle
                includeFontPadding = false
                maxLines = 1
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(HEADER_SUBTITLE_COLOR)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4f) })
        }
        header.addView(texts, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(1f) })
        val line = View(context).apply {
            setBackgroundColor(if (day.isToday) HEADER_TODAY_LINE_COLOR else HEADER_LINE_COLOR)
        }
        header.addView(line, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(if (day.isToday) 2f else 1f),
            Gravity.BOTTOM
        ))
        return header
    }

    private fun fillColumn(column: Column, expanded: Boolean) {
        val items = column.day.items
        val list = column.list
        if (items.isEmpty()) {
            list.addView(TextView(list.context).apply {
                text = "нет серий"
                includeFontPadding = false
                gravity = Gravity.CENTER_HORIZONTAL
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(EMPTY_TEXT_COLOR)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.schedule_week_empty_top) })
            return
        }
        val visibleCount = if (expanded) items.size else minOf(items.size, ScheduleViewModel.COLLAPSED_ROWS)
        items.take(visibleCount).forEach { addRow(column, it) }
        if (visibleCount < items.size) {
            addMore(column, items.size - visibleCount)
        }
    }

    private fun addRow(column: Column, item: WeekItem) {
        val list = column.list
        val index = column.targets.size
        val row = ItemScheduleWeekRowBinding.inflate(LayoutInflater.from(list.context), list, false)
        val insertAt = column.more?.let { list.indexOfChild(it) } ?: list.childCount
        list.addView(row.root, insertAt)
        column.targets.add(row.root)

        val radius = dp(4f).toFloat()
        row.scheduleRowPoster.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        row.scheduleRowPoster.clipToOutline = true
        row.scheduleRowPoster.showImageUrl(item.image)
        // Перенос после дефиса, как в макете («Рыцарь-» / «скелет»), а не посреди слова.
        row.scheduleRowTitle.text = item.title.replace("-", "-$ZERO_WIDTH_SPACE")
        row.scheduleRowEpisode.text = episodeText(item, focused = false)

        row.root.setOnClickListener { viewModel.onItemClick(item) }
        row.root.setOnFocusChangeListener { view, hasFocus ->
            row.scheduleRowEpisode.text = episodeText(item, hasFocus)
            if (hasFocus) onTargetFocused(column, index, view)
        }
    }

    private fun addMore(column: Column, count: Int) {
        val list = column.list
        val res = resources
        val more = TextView(list.context).apply {
            text = "ещё $count"
            isFocusable = true
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            val padding = res.getDimensionPixelSize(R.dimen.schedule_week_more_padding_horizontal)
            setPadding(padding, 0, padding, 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(requireContext().getColorStateList(R.color.schedule_week_more))
            setBackgroundResource(R.drawable.bg_schedule_week_more)
        }
        list.addView(more, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            res.getDimensionPixelSize(R.dimen.schedule_week_more_height)
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val index = column.targets.size
        column.targets.add(more)
        column.more = more
        more.setOnClickListener { expandColumn(column) }
        more.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) onTargetFocused(column, index, view)
        }
    }

    /** «ещё N»: остальные строки вместо кнопки, фокус — на первую из них. */
    private fun expandColumn(column: Column) {
        val more = column.more ?: return
        viewModel.onMoreClick(column.day.day)
        val firstNew = column.targets.indexOf(more)
        column.targets.remove(more)
        column.day.items.drop(firstNew).forEach { addRow(column, it) }
        column.targets.getOrNull(firstNew)?.requestFocus()
        column.list.removeView(more)
        column.more = null
    }

    private fun episodeText(item: WeekItem, focused: Boolean): CharSequence {
        val episode = item.nextEpisode
        return when {
            item.seasonReleased -> "сезон вышел"
            episode != null -> SpannableStringBuilder("$DOT Серия $episode").apply {
                val color = if (focused) DOT_FOCUSED_COLOR else DOT_COLOR
                setSpan(ForegroundColorSpan(color), 0, DOT.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            else -> ""
        }
    }

    private fun onTargetFocused(column: Column, index: Int, view: View) {
        viewModel.onCellFocused(Cell(column.day.day, index))
        // Прокрученные колонки без фокуса — к началу: их строки не должны оказаться «над» соседями.
        columns.forEach {
            if (it !== column && it.body.scrollY != 0) {
                it.scrollAnimator?.cancel()
                it.body.scrollTo(0, 0)
            }
        }
        if (view.isLaidOut && column.body.isLaidOut) {
            scrollToTarget(column, view, animate = true)
        } else {
            view.post { if (view.hasFocus()) scrollToTarget(column, view, animate = false) }
        }
    }

    private fun scrollToTarget(column: Column, view: View, animate: Boolean) {
        val body = column.body
        val margin = dp(8f)
        val current = column.scrollAnimator?.takeIf { it.isRunning }?.let {
            (it.getAnimatedValue("scrollY") as? Int)
        } ?: body.scrollY
        var target = current
        if (view.top - margin < target) target = view.top - margin
        if (view.bottom + margin > target + body.height) target = view.bottom + margin - body.height
        target = target.coerceIn(0, max(0, column.list.height - body.height))
        if (target == body.scrollY && column.scrollAnimator?.isRunning != true) return
        column.scrollAnimator?.cancel()
        if (!animate) {
            body.scrollTo(0, target)
            return
        }
        column.scrollAnimator = ObjectAnimator.ofInt(body, "scrollY", body.scrollY, target).apply {
            duration = SCROLL_DURATION_MS
            start()
        }
    }

    /** null — дальше решает главный экран (UP из первой строки — на вкладки). */
    private fun onFocusSearch(focused: View, direction: Int): View? {
        val binding = binding ?: return null
        viewModel.restoreFocus = false
        if (focused === binding.scheduleRetry) {
            return if (direction == View.FOCUS_UP) null else focused
        }
        val columnIndex = columns.indexOfFirst { column -> column.targets.any { it === focused } }
        if (columnIndex < 0) return null
        val column = columns[columnIndex]
        val index = column.targets.indexOfFirst { it === focused }
        return when (direction) {
            View.FOCUS_UP -> if (index == 0) null else column.targets[index - 1]
            View.FOCUS_DOWN -> column.targets.getOrNull(index + 1) ?: focused
            View.FOCUS_LEFT, View.FOCUS_RIGHT -> {
                val step = if (direction == View.FOCUS_LEFT) -1 else 1
                var next = columnIndex + step
                while (next in columns.indices && columns[next].targets.isEmpty()) next += step
                val targets = columns.getOrNull(next)?.targets ?: return focused
                targets[index.coerceAtMost(targets.lastIndex)]
            }

            else -> null
        }
    }

    /** Куда ставить фокус, когда он приходит на страницу: на ту же строку после релиза, иначе — начало «Сегодня». */
    private fun entryFocusTarget(): View? {
        val binding = binding ?: return null
        if (binding.scheduleError.isVisible) return binding.scheduleRetry
        if (!binding.scheduleWeek.isVisible) return null
        // Повторный запрос фокуса, когда он уже на странице (Leanback при возврате просит его дважды).
        binding.root.findFocus()?.takeIf { it !== binding.root }?.also { return it }
        // Флаг снимается при первой навигации стрелками (onFocusSearch).
        if (viewModel.restoreFocus) {
            val cell = viewModel.focusedCell
            val targets = columns.firstOrNull { it.day.day == cell?.day }?.targets.orEmpty()
            if (cell != null && targets.isNotEmpty()) {
                return targets[cell.index.coerceIn(0, targets.lastIndex)]
            }
        }
        return columns.firstNotNullOfOrNull { it.targets.firstOrNull() }
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics
    ).toInt()
}
