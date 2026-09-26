package ru.radiationx.anilibria.ui.presenter

import android.view.View
import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.ui.widget.WatchBadgeDrawable
import ru.radiationx.data.repository.WatchProgressRepository
import ru.radiationx.quill.Quill
import ru.radiationx.shared_app.imageloader.showImageUrl

class LibriaCardPresenter : Presenter() {

    private val watchProgressRepository by lazy {
        Quill.getRootScope().get(WatchProgressRepository::class)
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context)
        return LibriaCardViewHolder(cardView, watchProgressRepository)
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

class LibriaCardViewHolder(
    private val containerView: ImageCardView,
    private val watchProgressRepository: WatchProgressRepository,
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

    private val watchBadge = WatchBadgeDrawable(containerView.context)
    private val scope = MainScope()
    private var progressJob: Job? = null
    private var boundItem: LibriaCard? = null

    init {
        containerView.mainImageView?.overlay?.add(watchBadge)
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
        progressJob = watchProgressRepository
            .observe(type.releaseId)
            .onEach { watchBadge.setProgress(it, item.episodesTotal) }
            .launchIn(scope)
    }

    private fun stopObserveProgress() {
        progressJob?.cancel()
        progressJob = null
    }
}
