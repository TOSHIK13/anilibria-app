package ru.radiationx.anilibria.ui.presenter

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.ui.widget.WatchBadgeDrawable
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.ReleaseWatchProgress
import ru.radiationx.data.repository.WatchProgressRepository
import ru.radiationx.quill.Quill
import ru.radiationx.shared_app.imageloader.showImageUrl

class LibriaCardPresenter : Presenter() {

    private val watchProgressRepository by lazy {
        Quill.getRootScope().get(WatchProgressRepository::class)
    }

    private val releaseInteractor by lazy {
        Quill.getRootScope().get(ReleaseInteractor::class)
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = LibriaImageCardView(parent.context)
        return LibriaCardViewHolder(cardView, watchProgressRepository, releaseInteractor)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        item ?: return
        item as LibriaCard
        viewHolder as LibriaCardViewHolder
        viewHolder.bind(item)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder as LibriaCardViewHolder
        viewHolder.unbind()
    }
}

/** Карточка, сообщающая о смене фокуса (для рамки в оверлее постера). */
class LibriaImageCardView(context: Context) : ImageCardView(context) {

    var onFocusChanged: ((Boolean) -> Unit)? = null

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        onFocusChanged?.invoke(gainFocus)
    }
}

class LibriaCardViewHolder(
    private val containerView: LibriaImageCardView,
    private val watchProgressRepository: WatchProgressRepository,
    private val releaseInteractor: ReleaseInteractor,
) : Presenter.ViewHolder(containerView) {

    private val cardHeight by lazy {
        containerView.context.resources.getDimension(R.dimen.card_height).toInt()
    }
    private val cardReleaseWidth by lazy {
        containerView.context.resources.getDimension(R.dimen.card_release_width).toInt()
    }
    private val cardYoutubeWidth by lazy {
        containerView.context.resources.getDimension(R.dimen.card_youtube_width).toInt()
    }

    private val cardCorner by lazy {
        containerView.context.resources.getDimension(R.dimen.card_corner_radius)
    }

    private val watchBadge = WatchBadgeDrawable(containerView.context)
    private val scope = MainScope()
    private var progressJob: Job? = null
    private var boundItem: LibriaCard? = null

    init {
        containerView.mainImageView?.apply {
            setBackgroundColor(Color.parseColor("#222222"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cardCorner)
                }
            }
            clipToOutline = true
            overlay.add(watchBadge)
        }
        watchBadge.setFocused(containerView.hasFocus())
        containerView.onFocusChanged = { watchBadge.setFocused(it) }
        // Подписка на прогресс живёт только пока карточка на экране — иначе утечка вью.
        containerView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = observeProgress()
            override fun onViewDetachedFromWindow(v: View) = stopObserveProgress()
        })
    }

    fun bind(item: LibriaCard) {
        boundItem = item
        when (item.type) {
            is LibriaCard.Type.Release -> containerView.setMainImageDimensions(
                cardReleaseWidth,
                cardHeight
            )

            is LibriaCard.Type.Youtube -> containerView.setMainImageDimensions(
                cardYoutubeWidth,
                cardHeight
            )
        }
        val width = if (item.type is LibriaCard.Type.Release) cardReleaseWidth else cardYoutubeWidth
        watchBadge.setBounds(0, 0, width, cardHeight)
        watchBadge.clear()
        applyState(item, null, item.episodesAvailable)
        containerView.mainImageView?.showImageUrl(item.image)
        if (containerView.isAttachedToWindow) {
            observeProgress()
        }
    }

    fun unbind() {
        boundItem = null
        stopObserveProgress()
        watchBadge.clear()
    }

    private fun observeProgress() {
        stopObserveProgress()
        val item = boundItem ?: return
        val type = item.type as? LibriaCard.Type.Release ?: return
        watchProgressRepository.requestRefresh()
        val available = MutableStateFlow(item.episodesAvailable)
        var availableRequested = false
        progressJob = scope.launch {
            combine(watchProgressRepository.observe(type.releaseId), available) { progress, count ->
                progress to count
            }.collect { (progress, count) ->
                applyState(item, progress, count)
                // Число вышедших серий догружаем только для карточек с прогрессом.
                if (progress != null && count == null && !availableRequested) {
                    availableRequested = true
                    launch { available.value = loadAvailable(type.releaseId) }
                }
            }
        }
    }

    private fun applyState(item: LibriaCard, progress: ReleaseWatchProgress?, available: Int?) {
        val watched = progress?.watched ?: 0
        val total = progress?.total ?: item.episodesTotal
        val badge = when {
            watched > 0 && available != null && available > watched && isFresh(item.freshAt) ->
                WatchBadgeDrawable.Badge.NEW

            item.isFilm -> WatchBadgeDrawable.Badge.FILM
            else -> null
        }
        val watchedFully = if (item.isFilm) {
            watched > 0
        } else {
            total != null && watched > 0 && watched >= total
        }
        watchBadge.setProgress(progress, available, item.episodesTotal)
        watchBadge.setBadge(badge)
        watchBadge.setWatchedFully(watchedFully)
    }

    private fun isFresh(freshAtSec: Long?): Boolean {
        freshAtSec ?: return false
        val age = System.currentTimeMillis() - freshAtSec * 1000L
        return age <= FRESH_PERIOD_MS
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

    private companion object {
        const val FRESH_PERIOD_MS = 7L * 24 * 60 * 60 * 1000
    }
}
