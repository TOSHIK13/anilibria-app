package ru.radiationx.anilibria.ui.presenter

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import androidx.leanback.widget.Presenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.FranchiseCard
import ru.radiationx.anilibria.ui.widget.FranchiseCardView
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.ReleaseWatchProgress
import ru.radiationx.data.repository.WatchProgressRepository
import ru.radiationx.quill.Quill
import ru.radiationx.shared_app.imageloader.showImageUrl

/**
 * Карточка части франшизы ([FranchiseCard]): постер слева, «03 · ТВ», название, год и серии,
 * и прогресс «8/9(12)» / «12/12 ✓» (как плашка на постере) / «Просмотрен ✓» / «Не начато».
 * Открытый релиз — плашка «ВЫ ЗДЕСЬ»; досмотренные части — приглушённый постер.
 */
class FranchiseCardPresenter : Presenter() {

    private val watchProgressRepository by lazy {
        Quill.getRootScope().get(WatchProgressRepository::class)
    }

    private val releaseInteractor by lazy {
        Quill.getRootScope().get(ReleaseInteractor::class)
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.card_franchise, parent, false) as FranchiseCardView
        return FranchiseCardViewHolder(view, watchProgressRepository, releaseInteractor)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        item ?: return
        (viewHolder as FranchiseCardViewHolder).bind(item as FranchiseCard)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder as FranchiseCardViewHolder).unbind()
    }
}

class FranchiseCardViewHolder(
    cardView: FranchiseCardView,
    private val watchProgressRepository: WatchProgressRepository,
    private val releaseInteractor: ReleaseInteractor,
) : Presenter.ViewHolder(cardView) {

    private val density = cardView.resources.displayMetrics.density
    private val poster: ImageView = cardView.findViewById(R.id.franchisePoster)
    private val order: TextView = cardView.findViewById(R.id.franchiseOrder)
    private val title: TextView = cardView.findViewById(R.id.franchiseTitle)
    private val meta: TextView = cardView.findViewById(R.id.franchiseMeta)
    private val progressText: TextView = cardView.findViewById(R.id.franchiseProgress)
    private val chip: View = cardView.findViewById(R.id.franchiseChip)

    private val scope = MainScope()
    private var progressJob: Job? = null
    private var boundItem: FranchiseCard? = null

    init {
        TextViewCompat.setLineHeight(title, (17 * density + 0.5f).toInt())
        // Подписка на прогресс живёт только пока карточка на экране — иначе утечка вью.
        cardView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = observeProgress()
            override fun onViewDetachedFromWindow(v: View) = stopObserveProgress()
        })
    }

    fun bind(item: FranchiseCard) {
        boundItem = item
        order.text = item.orderLabel
        title.text = item.title
        title.updateLayoutParams { width = dp(if (item.isCurrent) 105 else 168) }
        meta.text = item.meta
        meta.isVisible = item.meta.isNotEmpty()
        chip.isVisible = item.isCurrent
        poster.showImageUrl(item.image)
        applyState(item, null, item.episodesAvailable)
        if (view.isAttachedToWindow) {
            observeProgress()
        }
    }

    fun unbind() {
        boundItem = null
        stopObserveProgress()
    }

    private fun observeProgress() {
        stopObserveProgress()
        val item = boundItem ?: return
        watchProgressRepository.requestRefresh()
        val available = MutableStateFlow(item.episodesAvailable)
        var availableRequested = false
        progressJob = scope.launch {
            combine(watchProgressRepository.observe(item.releaseId), available) { progress, count ->
                progress to count
            }.collect { (progress, count) ->
                applyState(item, progress, count)
                // Число вышедших серий догружаем только для частей с прогрессом.
                if (progress != null && count == null && !item.isFilm && !availableRequested) {
                    availableRequested = true
                    launch { available.value = loadAvailable(item.releaseId) }
                }
            }
        }
    }

    private fun applyState(item: FranchiseCard, progress: ReleaseWatchProgress?, available: Int?) {
        val watched = progress?.watched ?: 0
        val total = progress?.total ?: item.episodesTotal
        val completed = if (item.isFilm) {
            watched > 0
        } else {
            total != null && watched > 0 && watched >= total
        }
        progressText.text = when {
            item.isFilm -> if (watched > 0) "Просмотрен ✓" else "Не смотрел"
            watched > 0 && total != null && watched >= total -> "$watched/$total ✓"
            watched > 0 && total != null && available != null && available < total ->
                "$watched/$available($total)"

            watched > 0 && total != null -> "$watched/$total"
            watched > 0 -> "$watched эп."
            else -> "Не начато"
        }
        poster.colorFilter = if (completed && !item.isCurrent) watchedFilter else null
    }

    private suspend fun loadAvailable(releaseId: ReleaseId): Int? = try {
        releaseInteractor.getFull(releaseId)?.let { release ->
            release.episodesAvailable ?: release.episodes.size.takeIf { it > 0 }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    private fun stopObserveProgress() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private companion object {
        /** Досмотренная часть: яркость 0.7, насыщенность 0.6. */
        val watchedFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(0.6f)
            postConcat(ColorMatrix().apply { setScale(0.7f, 0.7f, 0.7f, 1f) })
        })
    }
}
