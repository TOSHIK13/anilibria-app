package ru.radiationx.anilibria.screen.services

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.external.DesiredStatus
import ru.radiationx.data.external.FirstSyncAction
import ru.radiationx.data.external.FirstSyncCompare
import ru.radiationx.data.external.FirstSyncFlow
import ru.radiationx.data.external.FirstSyncPolicy
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo
import javax.inject.Inject

enum class DiffFilter { ALL, DIFFERING, REMOTE, LOCAL, MISSING }

data class DiffRow(
    val action: FirstSyncAction,
    val title: String,
    val policy: FirstSyncPolicy,
    /** Явный выбор статуса пользователем. */
    val choice: DesiredStatus?,
) {
    val malId: Int get() = action.item.malId

    /** Статусы расходятся и политика «Объединить»: строку можно раскрыть и выбрать статус. */
    val canChoose: Boolean
        get() = policy == FirstSyncPolicy.MERGE && action.group == FirstSyncCompare.Group.DIFFERING &&
            action.item.local?.status != action.item.remote?.status
}

data class DiffUi(
    val filter: DiffFilter,
    val counts: Map<DiffFilter, Int>,
    val rows: List<DiffRow>,
    val policy: FirstSyncPolicy,
)

class FirstSyncDiffViewModel @Inject constructor(
    private val session: FirstSyncSession,
) : LifecycleViewModel() {

    private val filter = MutableStateFlow(DiffFilter.ALL)

    val ui: Flow<DiffUi> = combine(session.plan, filter, session.titles, session.choices, session.policy) { plan, f, titles, choices, policy ->
        val actions = plan?.actions.orEmpty().filter { it.group != FirstSyncCompare.Group.MATCHING }
        fun matches(a: FirstSyncAction, f: DiffFilter) = when (f) {
            DiffFilter.ALL -> true
            DiffFilter.DIFFERING -> a.group == FirstSyncCompare.Group.DIFFERING
            DiffFilter.REMOTE -> a.group == FirstSyncCompare.Group.ONLY_REMOTE
            DiffFilter.LOCAL -> a.group == FirstSyncCompare.Group.ONLY_LOCAL
            DiffFilter.MISSING -> a.flow == FirstSyncFlow.SKIP
        }
        DiffUi(
            f,
            DiffFilter.values().associateWith { c -> actions.count { matches(it, c) } },
            actions.filter { matches(it, f) }.map { a ->
                val rid = a.item.releaseId
                DiffRow(a, (rid?.let { titles[it] }) ?: a.item.title, policy, choices[a.item.malId])
            },
            policy,
        )
    }

    override fun onColdCreate() {
        session.loadTitles()
    }

    fun setFilter(value: DiffFilter) {
        filter.value = value
    }

    fun choose(malId: Int, status: DesiredStatus) = session.choose(malId, status)
}

/** Расхождения AniLiberty и AniList: чипы-фильтры, таблица с итогом по политике, выбор статуса в спорных строках. */
class FirstSyncDiffFragment : Fragment(R.layout.fragment_first_sync_diff) {

    private val viewModel by viewModel<FirstSyncDiffViewModel>()

    private var chips: Map<DiffFilter, TextView> = emptyMap()
    private var list: RecyclerView? = null
    private var initialFocusDone = false

    private val adapter = DiffAdapter { malId, status -> viewModel.choose(malId, status) }

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        val recycler = list ?: return@OnGlobalFocusChangeListener
        for (i in 0 until recycler.childCount) {
            (recycler.getChildViewHolder(recycler.getChildAt(i)) as? DiffHolder)?.updateExpanded()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycle.addObserver(viewModel)

        val recycler = view.findViewById<RecyclerView>(R.id.fdList)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        recycler.itemAnimator = null
        list = recycler

        val density = resources.displayMetrics.density
        val chipsRoot = view.findViewById<LinearLayout>(R.id.fdChips)
        chips = DiffFilter.values().associateWith { filter ->
            TextView(requireContext()).apply {
                setBackgroundResource(R.drawable.bg_catalog_chip)
                setTextColor(
                    ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                        intArrayOf(0xFF111111.toInt(), 0xFFFFFFFF.toInt()),
                    )
                )
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                setOnClickListener { viewModel.setFilter(filter) }
                chipsRoot.addView(
                    this,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        .apply { if (chipsRoot.childCount > 0) marginStart = (8 * density).toInt() },
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
        chips = emptyMap()
        initialFocusDone = false
        super.onDestroyView()
    }

    private fun render(ui: DiffUi) {
        val v = view ?: return
        chips.forEach { (filter, chip) ->
            chip.isActivated = filter == ui.filter
            val n = ui.counts[filter] ?: 0
            chip.text = when (filter) {
                DiffFilter.ALL -> "Все $n"
                DiffFilter.DIFFERING -> "Расходится $n"
                DiffFilter.REMOTE -> "Только AniList $n"
                DiffFilter.LOCAL -> "Только AniLiberty $n"
                DiffFilter.MISSING -> "Не найдено $n"
            }
        }
        v.findViewById<TextView>(R.id.fdResultHeader).text = when (ui.policy) {
            FirstSyncPolicy.MERGE -> "Итог при объединении"
            FirstSyncPolicy.ANILIBERTY -> "Итог: AniLiberty главнее"
            FirstSyncPolicy.ANILIST -> "Итог: AniList главнее"
        }
        val hadFocus = list?.hasFocus() == true
        adapter.submitList(ui.rows) {
            v.findViewById<View>(R.id.fdEmpty).isVisible = ui.rows.isEmpty()
            val recycler = list ?: return@submitList
            val needFocus = if (!initialFocusDone) {
                initialFocusDone = true
                true
            } else {
                hadFocus && recycler.findFocus() == null
            }
            if (needFocus) {
                recycler.post {
                    val first = (0 until recycler.childCount).map { recycler.getChildAt(it) }.firstOrNull()
                    (first ?: chips[ui.filter])?.requestFocus()
                }
            }
        }
    }
}

private class DiffAdapter(
    private val onChoose: (Int, DesiredStatus) -> Unit,
) : ListAdapter<DiffRow, DiffHolder>(object : DiffUtil.ItemCallback<DiffRow>() {
    override fun areItemsTheSame(a: DiffRow, b: DiffRow) = a.malId == b.malId
    override fun areContentsTheSame(a: DiffRow, b: DiffRow) = a == b
}) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        DiffHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_first_sync_row, parent, false), onChoose)

    override fun onBindViewHolder(holder: DiffHolder, position: Int) = holder.bind(getItem(position))
}

private class DiffHolder(
    view: View,
    private val onChoose: (Int, DesiredStatus) -> Unit,
) : RecyclerView.ViewHolder(view) {

    private val density = view.resources.displayMetrics.density
    private val title = view.findViewById<TextView>(R.id.frTitle)
    private val local = view.findViewById<TextView>(R.id.frLocal)
    private val remote = view.findViewById<TextView>(R.id.frRemote)
    private val result = view.findViewById<TextView>(R.id.frResult)
    private val choice = view.findViewById<View>(R.id.frChoice)
    private val choiceLabel = view.findViewById<TextView>(R.id.frChoiceLabel)
    private val choiceLocal = view.findViewById<TextView>(R.id.frChoiceLocal)
    private val choiceRemote = view.findViewById<TextView>(R.id.frChoiceRemote)
    private var row: DiffRow? = null

    init {
        choiceLocal.setOnClickListener { pick(local = true) }
        choiceRemote.setOnClickListener { pick(local = false) }
        choiceLocal.setOnFocusChangeListener { _, _ -> styleChoices() }
        choiceRemote.setOnFocusChangeListener { _, _ -> styleChoices() }
        // строка целиком фокусируется, поэтому в кнопки выбора ведём вручную: OK или вправо; вверх — назад на строку
        itemView.setOnClickListener { if (choice.isVisible) choiceLocal.requestFocus() }
        itemView.setOnKeyListener { _, code, event ->
            if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT && choice.isVisible) {
                choiceLocal.requestFocus()
                true
            } else false
        }
        listOf(choiceLocal, choiceRemote).forEach { button ->
            button.setOnKeyListener { _, code, event ->
                if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP) {
                    itemView.requestFocus()
                    true
                } else false
            }
        }
    }

    private fun pick(local: Boolean) {
        val r = row ?: return
        val status = (if (local) r.action.item.local?.status else r.action.item.remote?.status) ?: return
        onChoose(r.malId, status)
    }

    fun bind(r: DiffRow) {
        row = r
        val item = r.action.item
        title.text = r.title
        local.text = FirstSyncTexts.snapshotText(item.local, item)
        remote.text = FirstSyncTexts.snapshotText(item.remote, item)
        choiceLocal.text = item.local?.status?.ru.orEmpty()
        choiceRemote.text = item.remote?.status?.ru.orEmpty()
        updateExpanded()
    }

    /** Раскрыта (светлая), пока фокус на строке или её кнопках; у спорных строк показывается выбор статуса. */
    fun updateExpanded() {
        val r = row ?: return
        val expanded = itemView.hasFocus()
        val conflict = r.action.flow == FirstSyncFlow.CHOOSE
        choice.isVisible = expanded && r.canChoose
        choiceLabel.text = if (conflict) {
            itemView.context.getString(R.string.first_sync_choice_label)
        } else {
            "Оставить статус:"
        }
        (itemView.background as? GradientDrawable ?: GradientDrawable().also { itemView.background = it }).apply {
            cornerRadius = 10 * density
            setColor(if (expanded) 0xFFEEEEEE.toInt() else 0xFF282828.toInt())
        }
        title.setTextColor(if (expanded) 0xFF141414.toInt() else 0xFFFFFFFF.toInt())
        local.setTextColor(
            if (r.action.item.local == null) 0xFF909090.toInt() else if (expanded) 0xFF141414.toInt() else 0xFFEEEEEE.toInt()
        )
        remote.setTextColor(
            if (r.action.item.remote == null) 0xFF909090.toInt() else if (expanded) 0xFF444444.toInt() else 0xFFB2B2B2.toInt()
        )
        result.text = resultText(r, expanded)
        styleChoices()
    }

    private fun styleChoices() {
        val r = row ?: return
        fun style(button: TextView, status: DesiredStatus?) {
            val focused = button.isFocused
            val chosen = status != null && r.choice == status
            button.background = GradientDrawable().apply {
                cornerRadius = 8 * density
                when {
                    focused -> setColor(0xFFFE3635.toInt())
                    chosen -> setColor(0xFF141414.toInt())
                    else -> {
                        setColor(0x00000000)
                        setStroke((1 * density).toInt(), 0xFF999999.toInt())
                    }
                }
            }
            button.setTextColor(if (focused || chosen) 0xFFFFFFFF.toInt() else 0xFF141414.toInt())
        }
        style(choiceLocal, r.action.item.local?.status)
        style(choiceRemote, r.action.item.remote?.status)
    }

    private fun resultText(r: DiffRow, expanded: Boolean): CharSequence {
        val a = r.action
        val item = a.item
        val blue = if (expanded) 0xFF1B5E8F.toInt() else 0xFF6CC6FF.toInt()
        val red = if (expanded) 0xFFB71C1C.toInt() else 0xFFFF8A80.toInt()
        val orange = if (expanded) 0xFF8A5A00.toInt() else 0xFFF2C14E.toInt()
        val grey = if (expanded) 0xFF555555.toInt() else 0xFF909090.toInt()
        val out = SpannableStringBuilder()
        fun add(text: String, color: Int) {
            if (out.isNotEmpty()) out.append('\n')
            val start = out.length
            out.append(text)
            out.setSpan(ForegroundColorSpan(color), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        when (a.flow) {
            FirstSyncFlow.CHOOSE -> add("Нужно выбрать", orange)
            FirstSyncFlow.SKIP -> add("Пропустим: нет в каталоге", grey)
            FirstSyncFlow.NONE -> add(
                when {
                    a.group == FirstSyncCompare.Group.ONLY_REMOTE -> "Остаётся только в AniList"
                    a.group == FirstSyncCompare.Group.ONLY_LOCAL -> "Остаётся только в AniLiberty"
                    a.progressKept -> "Без изменений"
                    else -> "Без изменений"
                },
                grey,
            )
            else -> {
                a.toRemote?.let { add("→ AniList: " + FirstSyncTexts.changeText(it, item.remote, item), blue) }
                a.toLocal?.let { add("← AniLiberty: " + FirstSyncTexts.changeText(it, item.local, item), red) }
            }
        }
        if (a.progressKept) add("прогресс AniLiberty не уменьшаем", grey)
        return out
    }
}
