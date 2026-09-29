package ru.radiationx.anilibria.screen.services

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.flow.Flow
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.serviceBadge
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.external.FirstSyncPhase
import ru.radiationx.data.external.FirstSyncProgress
import ru.radiationx.quill.viewModel
import ru.radiationx.shared.ktx.android.subscribeTo
import javax.inject.Inject

class FirstSyncProgressViewModel @Inject constructor(
    private val session: FirstSyncSession,
    private val router: Router,
) : LifecycleViewModel() {

    val progress: Flow<FirstSyncProgress> = session.progress

    /** «Продолжить в фоне»: экран закрывается, работа идёт, по завершении — уведомление. */
    fun background() {
        session.setNotifyOnFinish()
        router.exit()
    }

    fun done() {
        session.finish()
        router.exit()
    }

    fun retry() = session.start()

    /** Экран закрыт кнопкой «Назад», пока идёт работа: она продолжается в фоне. */
    fun onScreenClosed() {
        if (session.progress.value.running) session.setNotifyOnFinish()
    }
}

/** Процесс первой синхронизации: полоса выполнения, этапы, «Продолжить в фоне» / итог и «Готово». */
class FirstSyncProgressFragment : Fragment(R.layout.fragment_first_sync_progress) {

    private val viewModel by viewModel<FirstSyncProgressViewModel>()

    private enum class StepState { PENDING, ACTIVE, DONE }

    private class Step(val root: LinearLayout, val icon: TextView, val label: TextView, val detail: TextView)

    private var steps: List<Step> = emptyList()
    private var lastKey: Any? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycle.addObserver(viewModel)
        val density = resources.displayMetrics.density
        val ctx = requireContext()

        val header = view.findViewById<LinearLayout>(R.id.fpHeader)
        val app = TextView(ctx).apply {
            text = "A"
            gravity = Gravity.CENTER
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            includeFontPadding = false
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(0xFFFE3635.toInt())
            }
            layoutParams = LinearLayout.LayoutParams((38 * density).toInt(), (38 * density).toInt())
        }
        val arrows = TextView(ctx).apply {
            text = "⇄"
            textSize = 22f
            setTextColor(0xFFB2B2B2.toInt())
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        }
        header.addView(app, 0)
        header.addView(arrows, 1)
        header.addView(serviceBadge(ctx, "AL", 38, 14f), 2)

        val stepsRoot = view.findViewById<LinearLayout>(R.id.fpSteps)
        val labels = listOf(
            "Получен список AniList",
            "Сопоставлено с каталогом AniLiberty",
            "Обновлены коллекции AniLiberty",
            "Отправка в AniList",
            "Сохранение состояния для следующих синхронизаций",
        )
        steps = labels.mapIndexed { i, text ->
            val icon = TextView(ctx).apply {
                gravity = Gravity.CENTER
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt())
            }
            val label = TextView(ctx).apply {
                this.text = text
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = (12 * density).toInt() }
            }
            val detail = TextView(ctx).apply {
                textSize = 12f
                setTextColor(0xFF909090.toInt())
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = (12 * density).toInt() }
            }
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * density).toInt(), (7 * density).toInt(), (12 * density).toInt(), (7 * density).toInt())
                addView(icon)
                addView(label)
                addView(detail)
            }
            stepsRoot.addView(root, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) topMargin = (4 * density).toInt() })
            Step(root, icon, label, detail)
        }

        view.findViewById<TextView>(R.id.fpPrimary).styleFsButton(false)
        view.findViewById<TextView>(R.id.fpSecondary).styleFsButton(false)
        subscribeTo(viewModel.progress) { render(it) }
    }

    override fun onDestroyView() {
        viewModel.onScreenClosed()
        steps = emptyList()
        lastKey = null
        super.onDestroyView()
    }

    private fun setStep(index: Int, state: StepState, detail: String = "") {
        val s = steps.getOrNull(index) ?: return
        val density = resources.displayMetrics.density
        when (state) {
            StepState.DONE -> {
                s.icon.text = "✓"
                s.icon.setTextColor(0xFF7BD88F.toInt())
                s.label.setTextColor(0xFFEEEEEE.toInt())
            }

            StepState.ACTIVE -> {
                s.icon.text = "↻"
                s.icon.setTextColor(0xFF02A9FF.toInt())
                s.label.setTextColor(0xFFEEEEEE.toInt())
            }

            StepState.PENDING -> {
                s.icon.text = "○"
                s.icon.setTextColor(0xFF909090.toInt())
                s.label.setTextColor(0xFF909090.toInt())
            }
        }
        s.detail.text = detail
        s.root.background = if (state == StepState.ACTIVE) {
            GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(0xFF282828.toInt())
            }
        } else null
    }

    private fun render(p: FirstSyncProgress) {
        val v = view ?: return
        val title = v.findViewById<TextView>(R.id.fpTitle)
        val count = v.findViewById<TextView>(R.id.fpCount)
        val eta = v.findViewById<TextView>(R.id.fpEta)
        val bar = v.findViewById<ProgressBar>(R.id.fpBar)
        val summary = v.findViewById<TextView>(R.id.fpSummary)
        val primary = v.findViewById<TextView>(R.id.fpPrimary)
        val secondary = v.findViewById<TextView>(R.id.fpSecondary)
        val dry = if (p.dryRun) " (тест, без изменений)" else ""

        title.text = when (p.phase) {
            FirstSyncPhase.DONE -> "Синхронизация завершена$dry"
            FirstSyncPhase.FAILED -> "Не удалось выполнить синхронизацию"
            FirstSyncPhase.COMPARING -> "Сравниваем…$dry"
            else -> "Синхронизация…$dry"
        }
        count.text = when (p.phase) {
            FirstSyncPhase.COMPARING, FirstSyncPhase.IDLE -> "Сравниваем списки"
            FirstSyncPhase.FAILED -> "Остановлено"
            else -> "Выполнено изменений: ${p.done} из ${p.total}"
        }
        val remaining = (p.queued - p.sent).coerceAtLeast(0)
        eta.text = if (p.phase == FirstSyncPhase.SENDING && remaining > 0) {
            val minutes = Math.ceil(remaining * 2.2 / 60.0).toInt()
            if (minutes <= 1) "осталось меньше минуты" else "осталось ≈ $minutes мин"
        } else ""
        bar.progress = when {
            p.phase == FirstSyncPhase.DONE -> 1000
            p.total > 0 -> (p.done * 1000L / p.total).toInt().coerceIn(0, 1000)
            else -> 0
        }

        val phase = p.phase
        val applied = phase == FirstSyncPhase.SENDING || phase == FirstSyncPhase.DONE
        val comparing = phase == FirstSyncPhase.COMPARING || phase == FirstSyncPhase.IDLE || phase == FirstSyncPhase.FAILED
        setStep(0, if (comparing) (if (phase == FirstSyncPhase.COMPARING) StepState.ACTIVE else StepState.PENDING) else StepState.DONE,
            if (comparing) "" else "${p.remoteTotal} записей · 1 запрос")
        setStep(1, if (comparing) StepState.PENDING else StepState.DONE,
            if (comparing) "" else "${p.linked} из ${p.remoteTotal} · по MAL id")
        setStep(2, when {
            comparing -> StepState.PENDING
            applied -> StepState.DONE
            else -> StepState.ACTIVE
        }, when {
            comparing -> ""
            p.receivedTotal == 0 -> "изменений нет"
            else -> "${p.received}/${p.receivedTotal} изменений"
        })
        setStep(3, when {
            comparing || phase == FirstSyncPhase.APPLYING -> StepState.PENDING
            phase == FirstSyncPhase.SENDING -> StepState.ACTIVE
            else -> StepState.DONE
        }, when {
            comparing || phase == FirstSyncPhase.APPLYING -> ""
            p.queued == 0 -> "нечего отправлять"
            phase == FirstSyncPhase.SENDING -> "${p.sent}/${p.queued}" + (p.current?.let { " · сейчас: $it" } ?: "")
            else -> "${p.sent}/${p.queued}"
        })
        setStep(4, if (applied) StepState.DONE else StepState.PENDING)

        v.findViewById<View>(R.id.fpNote).isVisible = p.running && p.queued > 10
        summary.isVisible = phase == FirstSyncPhase.DONE || phase == FirstSyncPhase.FAILED
        summary.text = when (phase) {
            FirstSyncPhase.DONE -> "В AniLiberty: ${p.received} · в AniList: ${p.sent} · пропущено: ${p.skipped} · ошибок: ${p.errors}" +
                if (p.errors > 0) "\nНеотправленное можно повторить в журнале синхронизации." else ""

            FirstSyncPhase.FAILED -> p.message.orEmpty()
            else -> ""
        }

        val key = Pair(phase == FirstSyncPhase.DONE, phase == FirstSyncPhase.FAILED)
        when (phase) {
            FirstSyncPhase.DONE -> {
                primary.text = "Готово"
                primary.styleFsButton(true)
                primary.setOnClickListener { viewModel.done() }
                secondary.isVisible = false
            }

            FirstSyncPhase.FAILED -> {
                primary.text = "Повторить"
                primary.styleFsButton(true)
                primary.setOnClickListener { viewModel.retry() }
                secondary.isVisible = true
                secondary.text = "Закрыть"
                secondary.styleFsButton(false)
                secondary.setOnClickListener { viewModel.done() }
            }

            else -> {
                primary.text = "Продолжить в фоне"
                primary.styleFsButton(false)
                primary.setOnClickListener { viewModel.background() }
                secondary.isVisible = false
            }
        }
        if (key != lastKey) {
            lastKey = key
            primary.requestFocus()
        }
    }
}
