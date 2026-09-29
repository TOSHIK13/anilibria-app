package ru.radiationx.anilibria.screen.services

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.external.FirstSyncPlan
import ru.radiationx.data.external.FirstSyncPolicy
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo
import javax.inject.Inject

data class FirstSyncUi(val state: ReportState, val plan: FirstSyncPlan?, val policy: FirstSyncPolicy)

class FirstSyncViewModel @Inject constructor(
    private val session: FirstSyncSession,
    private val router: Router,
) : LifecycleViewModel() {

    val ui: Flow<FirstSyncUi> = combine(session.report, session.plan, session.policy) { state, plan, policy ->
        FirstSyncUi(state, plan, policy)
    }

    fun setPolicy(policy: FirstSyncPolicy) {
        session.policy.value = policy
    }

    fun retry() = session.load()

    fun openDiff() = router.navigateTo(FirstSyncDiffScreen())

    fun start() {
        session.start()
        router.replaceScreen(FirstSyncProgressScreen())
    }

    fun later() = router.exit()
}

/** Кнопка мастера: красная (основная) или с рамкой; в фокусе — светлая. */
internal fun TextView.styleFsButton(primary: Boolean) {
    val density = resources.displayMetrics.density
    fun apply() {
        val focused = isFocused
        background = GradientDrawable().apply {
            cornerRadius = 10 * density
            when {
                focused -> setColor(0xFFEEEEEE.toInt())
                primary -> setColor(0xFFFE3635.toInt())
                else -> {
                    setColor(0x00000000)
                    setStroke((1 * density).toInt(), 0xFF414141.toInt())
                }
            }
        }
        setTextColor(if (focused) 0xFF141414.toInt() else 0xFFEEEEEE.toInt())
    }
    apply()
    setOnFocusChangeListener { _, _ -> apply() }
}

/** Первая синхронизация: плитки сравнения, выбор политики, «Начать» / «Посмотреть расхождения» / «Позже». */
class FirstSyncFragment : Fragment(R.layout.fragment_first_sync) {

    private val viewModel by viewModel<FirstSyncViewModel>()

    private class PolicyCard(
        val policy: FirstSyncPolicy,
        val root: LinearLayout,
        val radio: View,
        val title: TextView,
        val desc: TextView,
    )

    private var tileValues: List<TextView> = emptyList()
    private var cards: List<PolicyCard> = emptyList()
    private var selected = FirstSyncPolicy.MERGE
    private var focusDone = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycle.addObserver(viewModel)
        val density = resources.displayMetrics.density
        val inflater = LayoutInflater.from(requireContext())

        val tiles = view.findViewById<LinearLayout>(R.id.fsTiles)
        val defs = listOf(
            R.string.first_sync_tile_matching to 0xFFEEEEEE.toInt(),
            R.string.first_sync_tile_differing to 0xFFF2C14E.toInt(),
            R.string.first_sync_tile_remote to 0xFF6CC6FF.toInt(),
            R.string.first_sync_tile_local to 0xFFFF8A80.toInt(),
            R.string.first_sync_tile_missing to 0xFF909090.toInt(),
        )
        tileValues = defs.mapIndexed { i, (label, color) ->
            val tile = inflater.inflate(R.layout.item_first_sync_tile, tiles, false)
            if (i > 0) (tile.layoutParams as LinearLayout.LayoutParams).marginStart = (10 * density).toInt()
            tile.findViewById<TextView>(R.id.fsTileLabel).setText(label)
            val value = tile.findViewById<TextView>(R.id.fsTileValue)
            value.setTextColor(color)
            tiles.addView(tile)
            value
        }

        val policies = view.findViewById<LinearLayout>(R.id.fsPolicies)
        cards = listOf(
            Triple(FirstSyncPolicy.MERGE, R.string.first_sync_policy_merge, R.string.first_sync_policy_merge_desc),
            Triple(FirstSyncPolicy.ANILIBERTY, R.string.first_sync_policy_aniliberty, R.string.first_sync_policy_aniliberty_desc),
            Triple(FirstSyncPolicy.ANILIST, R.string.first_sync_policy_anilist, R.string.first_sync_policy_anilist_desc),
        ).map { (policy, title, desc) -> policyCard(policies, policy, title, desc, density) }

        view.findViewById<TextView>(R.id.fsStart).apply {
            styleFsButton(true)
            setOnClickListener { viewModel.start() }
        }
        view.findViewById<TextView>(R.id.fsDiff).apply {
            styleFsButton(false)
            setOnClickListener { viewModel.openDiff() }
        }
        view.findViewById<TextView>(R.id.fsLater).apply {
            styleFsButton(false)
            setOnClickListener { viewModel.later() }
        }
        view.findViewById<TextView>(R.id.fsRetry).apply {
            styleFsButton(true)
            setOnClickListener { viewModel.retry() }
        }
        view.findViewById<TextView>(R.id.fsStatusLater).apply {
            styleFsButton(false)
            setOnClickListener { viewModel.later() }
        }

        subscribeTo(viewModel.ui) { render(it) }
    }

    override fun onDestroyView() {
        tileValues = emptyList()
        cards = emptyList()
        focusDone = false
        super.onDestroyView()
    }

    private fun policyCard(parent: LinearLayout, policy: FirstSyncPolicy, title: Int, desc: Int, density: Float): PolicyCard {
        val ctx = requireContext()
        val radio = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt())
        }
        val titleView = TextView(ctx).apply {
            setText(title)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }
        val descView = TextView(ctx).apply {
            setText(desc)
            textSize = 12f
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (14 * density).toInt()
            }
            addView(titleView)
            addView(descView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (3 * density).toInt() })
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding((16 * density).toInt(), (11 * density).toInt(), (16 * density).toInt(), (11 * density).toInt())
            addView(radio)
            addView(column)
            setOnClickListener { viewModel.setPolicy(policy) }
        }
        parent.addView(
            root,
            LinearLayout.LayoutParams(-1, -2).apply { if (parent.childCount > 0) topMargin = (8 * density).toInt() },
        )
        val card = PolicyCard(policy, root, radio, titleView, descView)
        root.setOnFocusChangeListener { _, _ -> styleCard(card) }
        return card
    }

    private fun styleCard(card: PolicyCard) {
        val density = resources.displayMetrics.density
        val focused = card.root.isFocused
        val on = card.policy == selected
        card.root.background = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(if (focused) 0xFFEEEEEE.toInt() else 0xFF282828.toInt())
        }
        card.radio.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x00000000)
            if (on) setStroke((6 * density).toInt(), 0xFFFE3635.toInt()) else setStroke((2 * density).toInt(), 0xFF909090.toInt())
        }
        card.title.setTextColor(if (focused) 0xFF141414.toInt() else 0xFFEEEEEE.toInt())
        card.desc.setTextColor(if (focused) 0xFF444444.toInt() else 0xFFB2B2B2.toInt())
    }

    private fun render(ui: FirstSyncUi) {
        val v = view ?: return
        val content = v.findViewById<View>(R.id.fsContent)
        val status = v.findViewById<View>(R.id.fsStatus)
        val statusText = v.findViewById<TextView>(R.id.fsStatusText)
        val retry = v.findViewById<View>(R.id.fsRetry)
        val statusLater = v.findViewById<View>(R.id.fsStatusLater)
        when (val state = ui.state) {
            ReportState.Loading -> {
                content.isVisible = false
                status.isVisible = true
                statusText.setText(R.string.first_sync_comparing)
                retry.isVisible = false
                statusLater.isVisible = false
                focusDone = false
            }

            is ReportState.Error -> {
                content.isVisible = false
                status.isVisible = true
                statusText.text = getString(R.string.first_sync_error, state.message)
                retry.isVisible = true
                statusLater.isVisible = true
                if (!retry.hasFocus()) retry.requestFocus()
            }

            is ReportState.Ready -> {
                content.isVisible = true
                status.isVisible = false
                val r = state.report
                listOf(r.matching.size, r.differing.size, r.onlyRemote.size, r.onlyLocal.size, r.notInCatalog.size)
                    .forEachIndexed { i, n -> tileValues.getOrNull(i)?.text = n.toString() }
                selected = ui.policy
                cards.forEach { styleCard(it) }
                val conflicts = ui.plan?.conflicts ?: 0
                v.findViewById<TextView>(R.id.fsHint).apply {
                    isVisible = conflicts > 0
                    text = "Статусы расходятся у $conflicts · без выбора они будут пропущены. Выбор — в «Посмотреть расхождения»."
                }
                if (!focusDone) {
                    focusDone = true
                    v.findViewById<View>(R.id.fsStart).requestFocus()
                }
            }
        }
    }
}
