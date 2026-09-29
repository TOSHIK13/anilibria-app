package ru.radiationx.anilibria.screen.services

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.radiationx.anilibria.R
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo
import ru.radiationx.data.external.JournalDirection
import ru.radiationx.data.external.JournalEntry
import ru.radiationx.data.external.JournalResult

/** Журнал синхронизации AniList: фильтры-чипы, записи по дням, «Повторить сейчас»/«Пропустить» у ошибок. */
class AniListJournalFragment : Fragment(R.layout.fragment_anilist_journal) {

    private val viewModel by viewModel<AniListJournalViewModel>()

    private var chips: Map<JournalFilter, TextView> = emptyMap()
    private var list: RecyclerView? = null
    private var empty: View? = null
    private var initialFocusDone = false

    private val adapter = JournalAdapter(
        onRetry = { viewModel.retryNow(it) },
        onSkip = { viewModel.skip(it) },
    )

    private val focusListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        val recycler = list ?: return@OnGlobalFocusChangeListener
        for (i in 0 until recycler.childCount) {
            (recycler.getChildViewHolder(recycler.getChildAt(i)) as? RowHolder)?.updateExpanded()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycle.addObserver(viewModel)

        val recycler = view.findViewById<RecyclerView>(R.id.journalList)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        recycler.itemAnimator = null
        list = recycler
        empty = view.findViewById(R.id.journalEmpty)

        val density = resources.displayMetrics.density
        val chipsRoot = view.findViewById<LinearLayout>(R.id.journalChips)
        chips = JournalFilter.values().associateWith { filter ->
            TextView(requireContext()).apply {
                setBackgroundResource(R.drawable.bg_catalog_chip)
                setTextColor(
                    android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                        intArrayOf(0xFF111111.toInt(), 0xFFFFFFFF.toInt())
                    )
                )
                textSize = 13f
                gravity = android.view.Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                setOnClickListener { viewModel.setFilter(filter) }
                chipsRoot.addView(
                    this,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply { marginStart = (8 * density).toInt() }
                )
            }
        }

        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        subscribeTo(viewModel.ui) { render(it) }
    }

    override fun onDestroyView() {
        view?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(focusListener)
        list?.adapter = null
        list = null
        empty = null
        chips = emptyMap()
        initialFocusDone = false
        super.onDestroyView()
    }

    private fun render(ui: JournalUi) {
        chips.forEach { (filter, chip) ->
            chip.isActivated = filter == ui.filter
            chip.text = when (filter) {
                JournalFilter.ALL -> "Все"
                JournalFilter.OUT -> "Отправлено (OUT)"
                JournalFilter.IN -> "Получено (IN)"
                JournalFilter.ERRORS -> if (ui.errorCount > 0) "Ошибки ${ui.errorCount}" else "Ошибки"
            }
        }
        val hadFocus = list?.hasFocus() == true
        val items = ArrayList<JournalItem>()
        var lastDay: String? = null
        ui.entries.forEach { entry ->
            val day = dayHeaderText(entry.time)
            if (day != lastDay) {
                items.add(JournalItem.Day(day))
                lastDay = day
            }
            items.add(JournalItem.Row(entry))
        }
        adapter.submitList(items) {
            empty?.isVisible = items.isEmpty()
            val recycler = list ?: return@submitList
            val needFocus = if (!initialFocusDone) {
                initialFocusDone = true
                true
            } else {
                // сфокусированная запись исчезла (пропущена / ушла из фильтра)
                hadFocus && recycler.findFocus() == null
            }
            if (needFocus) {
                recycler.post {
                    val first = (0 until recycler.childCount).map { recycler.getChildAt(it) }
                        .firstOrNull { recycler.getChildViewHolder(it) is RowHolder }
                    (first ?: chips[JournalFilter.ALL])?.requestFocus()
                }
            }
        }
    }
}

private sealed class JournalItem {
    data class Day(val title: String) : JournalItem()
    data class Row(val entry: JournalEntry) : JournalItem()
}

private class JournalAdapter(
    private val onRetry: (JournalEntry) -> Unit,
    private val onSkip: (JournalEntry) -> Unit,
) : ListAdapter<JournalItem, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<JournalItem>() {
    override fun areItemsTheSame(a: JournalItem, b: JournalItem) = when {
        a is JournalItem.Day && b is JournalItem.Day -> a.title == b.title
        a is JournalItem.Row && b is JournalItem.Row -> a.entry.id == b.entry.id
        else -> false
    }

    override fun areContentsTheSame(a: JournalItem, b: JournalItem) = a == b
}) {

    override fun getItemViewType(position: Int) = if (getItem(position) is JournalItem.Day) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_journal_day, parent, false)) {}
        } else {
            RowHolder(inflater.inflate(R.layout.item_journal_row, parent, false), onRetry, onSkip)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is JournalItem.Day -> (holder.itemView as TextView).text = item.title
            is JournalItem.Row -> (holder as RowHolder).bind(item.entry)
        }
    }
}

private class RowHolder(
    view: View,
    private val onRetry: (JournalEntry) -> Unit,
    private val onSkip: (JournalEntry) -> Unit,
) : RecyclerView.ViewHolder(view) {

    private val density = view.resources.displayMetrics.density
    private val time = view.findViewById<TextView>(R.id.journalTime)
    private val direction = view.findViewById<TextView>(R.id.journalDirection)
    private val badge = view.findViewById<TextView>(R.id.journalBadge)
    private val text = view.findViewById<TextView>(R.id.journalText)
    private val detail = view.findViewById<TextView>(R.id.journalDetail)
    private val result = view.findViewById<TextView>(R.id.journalResult)
    private val actions = view.findViewById<View>(R.id.journalActions)
    private val retry = view.findViewById<TextView>(R.id.journalRetry)
    private val skip = view.findViewById<TextView>(R.id.journalSkip)
    private var entry: JournalEntry? = null

    init {
        badge.background = GradientDrawable().apply {
            cornerRadius = 6 * density
            setColor(0xFF02A9FF.toInt())
        }
        retry.setOnClickListener { entry?.let(onRetry) }
        skip.setOnClickListener { entry?.let(onSkip) }
        retry.setOnFocusChangeListener { _, _ -> styleButtons() }
        skip.setOnFocusChangeListener { _, _ -> styleButtons() }
    }

    private val canAct get() = entry?.let {
        it.itemId != null && (it.result == JournalResult.ERROR || it.result == JournalResult.RETRY_AT)
    } == true

    fun bind(e: JournalEntry) {
        entry = e
        time.text = timeText(e.time)
        direction.text = when (e.direction) {
            JournalDirection.OUT -> "→"
            JournalDirection.IN -> "←"
            JournalDirection.CHECK -> "↻"
        }
        detail.text = e.detail.orEmpty()
        detail.isVisible = !e.detail.isNullOrBlank()
        result.text = when (e.result) {
            JournalResult.DONE -> "✓ готово"
            JournalResult.RETRY_AT -> "повтор " + (e.retryAt?.let(::timeText) ?: "")
            JournalResult.ERROR -> "ошибка"
            JournalResult.SKIPPED -> "пропущено"
            JournalResult.UP_TO_DATE -> "уже актуально"
        }
        updateExpanded()
    }

    /** Строка раскрыта (светлая), пока фокус на ней или на её кнопках; у ошибок показываются кнопки. */
    fun updateExpanded() {
        val e = entry ?: return
        val expanded = itemView.hasFocus()
        actions.isVisible = expanded && canAct
        (itemView.background as? GradientDrawable ?: GradientDrawable().also { itemView.background = it }).apply {
            cornerRadius = 10 * density
            setColor(if (expanded) 0xFFEEEEEE.toInt() else 0xFF282828.toInt())
        }
        val primary = if (expanded) 0xFF141414.toInt() else 0xFFFFFFFF.toInt()
        val secondary = if (expanded) 0xFF444444.toInt() else 0xFFB2B2B2.toInt()
        time.setTextColor(if (expanded) 0xFF555555.toInt() else 0xFF909090.toInt())
        detail.setTextColor(if (expanded) 0xFF444444.toInt() else 0xFF909090.toInt())
        direction.setTextColor(
            when (e.direction) {
                JournalDirection.OUT -> if (expanded) 0xFF1B5E8F.toInt() else 0xFF6CC6FF.toInt()
                JournalDirection.IN -> if (expanded) 0xFFB71C1C.toInt() else 0xFFFF8A80.toInt()
                JournalDirection.CHECK -> secondary
            }
        )
        val body = SpannableStringBuilder(e.title)
        body.setSpan(StyleSpan(Typeface.BOLD), 0, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        body.setSpan(ForegroundColorSpan(primary), 0, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (e.text.isNotBlank()) {
            val start = body.length
            body.append(" · ").append(e.text)
            body.setSpan(ForegroundColorSpan(secondary), start, body.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.text = body
        result.setTextColor(
            when (e.result) {
                JournalResult.DONE -> if (expanded) 0xFF2E7D32.toInt() else 0xFF7BD88F.toInt()
                JournalResult.RETRY_AT -> if (expanded) 0xFF9A3412.toInt() else 0xFFFFB74D.toInt()
                JournalResult.ERROR -> if (expanded) 0xFFB71C1C.toInt() else 0xFFFF8A80.toInt()
                else -> if (expanded) 0xFF555555.toInt() else 0xFF909090.toInt()
            }
        )
        styleButtons()
    }

    private fun styleButtons() {
        fun style(button: TextView, primaryBtn: Boolean) {
            val focused = button.isFocused
            val fill = when {
                focused -> 0xFFFE3635.toInt()
                primaryBtn -> 0xFF141414.toInt()
                else -> 0x00000000
            }
            button.background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(fill)
                if (!primaryBtn && !focused) setStroke((1 * density).toInt(), 0xFF999999.toInt())
            }
            button.setTextColor(
                if (focused || primaryBtn) 0xFFFFFFFF.toInt() else 0xFF141414.toInt()
            )
        }
        style(retry, true)
        style(skip, false)
    }
}
