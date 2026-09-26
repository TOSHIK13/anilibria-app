package ru.radiationx.anilibria.ui.presenter

import ru.radiationx.data.system.LoadTiming
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import androidx.leanback.widget.RowPresenter
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.DetailChip
import ru.radiationx.anilibria.common.DetailProgress
import ru.radiationx.anilibria.common.DetailsState
import ru.radiationx.anilibria.common.LibriaDetails
import ru.radiationx.anilibria.common.LibriaDetailsRow
import ru.radiationx.anilibria.databinding.RowDetailReleaseBinding
import ru.radiationx.shared_app.imageloader.showImageUrl

class ReleaseDetailsPresenter(
    private val continueClickListener: () -> Unit,
    private val playClickListener: () -> Unit,
    private val favoriteClickListener: () -> Unit,
    private val descriptionClickListener: () -> Unit,
    private val collectionClickListener: () -> Unit,
    private val otherClickListener: () -> Unit,
) : RowPresenter() {

    init {
        headerPresenter = null
    }

    override fun isUsingDefaultSelectEffect(): Boolean {
        return false
    }

    override fun createRowViewHolder(parent: ViewGroup): ViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.row_detail_release, parent, false)
        return LibriaReleaseViewHolder(
            view,
            continueClickListener,
            playClickListener,
            favoriteClickListener,
            descriptionClickListener,
            collectionClickListener,
            otherClickListener
        )
    }

    override fun onBindRowViewHolder(vh: ViewHolder, item: Any) {
        super.onBindRowViewHolder(vh, item)
        vh as LibriaReleaseViewHolder
        item as LibriaDetailsRow
        vh.bind(item)
    }

}

class LibriaReleaseViewHolder(
    itemView: View,
    private val continueClickListener: () -> Unit,
    private val playClickListener: () -> Unit,
    private val favoriteClickListener: () -> Unit,
    private val descriptionClickListener: () -> Unit,
    private val collectionClickListener: () -> Unit,
    private val otherClickListener: () -> Unit,
) : RowPresenter.ViewHolder(itemView) {

    private val binding by lazy {
        RowDetailReleaseBinding.bind(view)
    }

    private val density = itemView.resources.displayMetrics.density

    private var lastState: DetailsState? = null
    private var lastDetails: LibriaDetails? = null

    /** URL, уже отданный в постер справа (постер появляется позже остальных данных). */
    private var loadedPoster: String? = null

    /**
     * Фокус ставится на первую кнопку, пока пользователь сам никуда не ушёл:
     * при догрузке релиза первой может стать «Продолжить».
     */
    private var autoFocusActive = true
    private var autoFocusTarget: View? = null

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
        val root = binding.rowReleaseRoot
        val movedInside = newFocus != null && newFocus !== autoFocusTarget &&
                newFocus !== root && root.isAncestorOf(newFocus)
        // Потеря фокуса самой строкой (заглушка загрузки) — не действие пользователя.
        val leftRow = oldFocus != null && oldFocus !== root && root.isAncestorOf(oldFocus) &&
                (newFocus == null || !root.isAncestorOf(newFocus))
        if (movedInside || leftRow) {
            autoFocusActive = false
        }
    }

    private fun View.isAncestorOf(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === this) return true
            current = current.parent as? View
        }
        return false
    }

    init {
        binding.rowReleaseActionContinue.setOnClickListener { continueClickListener.invoke() }
        binding.rowReleaseActionPlay.setOnClickListener { playClickListener.invoke() }
        binding.rowReleaseActionCollection.setOnClickListener { collectionClickListener.invoke() }
        binding.rowReleaseActionOther.setOnClickListener { otherClickListener.invoke() }
        binding.rowReleaseActionFavorite.setOnClickListener { favoriteClickListener.invoke() }
        // Описание, как и раньше, не фокусируемое; обработчик оставлен на случай, если его включат.
        binding.rowReleaseDescription.setOnClickListener { descriptionClickListener.invoke() }
        binding.rowReleaseDescription.isClickable = false
        TextViewCompat.setLineHeight(binding.rowReleaseDescription, dp(22))
        binding.root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
            }
        })
    }

    fun bind(item: LibriaDetailsRow) {
        item.state?.also { bindState(it) }
        item.details?.also { bindDetails(it) }
    }

    private fun bindState(state: DetailsState) {
        if (lastState == state) {
            return
        }

        lastState = state
        binding.rowReleaseActions.isInvisible = state.loadingProgress

        binding.rowReleaseLoadingProgress.isVisible = state.loadingProgress
        binding.rowReleaseUpdateProgress.isVisible = state.updateProgress && !state.loadingProgress
        // Сначала переводим фокус на кнопку, потом снимаем его с заглушки загрузки.
        applyAutoFocus()
        binding.rowReleaseRoot.isFocusable = state.loadingProgress
    }

    private fun bindDetails(details: LibriaDetails) {
        if (lastDetails == details) {
            return
        }
        val previous = lastDetails
        lastDetails = details

        // Постер справа — только когда точно известно, что широкого фона нет.
        val showPoster = details.isFull && details.backgroundCover == null && details.image.isNotEmpty()
        val nextEpisode = details.nextEpisode
        val nextOnTop = nextEpisode != null && !showPoster

        binding.rowReleaseTitleRu.text = details.titleRu
        binding.rowReleaseTitleRu.maxWidth = dp(
            when {
                nextOnTop -> 620
                showPoster -> 660
                else -> 820
            }
        )
        binding.rowReleaseTitleEn.text = details.titleEn
        binding.rowReleaseTitleEn.isVisible = details.titleEn.isNotEmpty()
        binding.rowReleaseTitleEn.maxWidth = dp(if (nextOnTop) 620 else 660)

        if (previous?.chips != details.chips) {
            bindChips(details.chips)
        }
        binding.rowReleaseChips.updateLayoutParams { width = dp(if (nextOnTop) 620 else 670) }

        binding.rowReleaseInfo.text = details.infoLine
        binding.rowReleaseInfo.isVisible = details.infoLine.isNotEmpty()

        bindProgress(details.progress)

        binding.rowReleaseDescription.text = details.description
        binding.rowReleaseDescription.isVisible = details.description.isNotEmpty()

        binding.rowReleaseImageCard.isVisible = showPoster
        if (showPoster && loadedPoster != details.image) {
            loadedPoster = details.image
            binding.rowReleaseImageCard.showImageUrl(details.image) {
                if (LoadTiming.enabled) {
                    onStart { LoadTiming.mark("details", "poster_start", "file=${details.image.substringAfterLast('/')}") }
                    onSuccess { LoadTiming.mark("details", "poster_loaded", "file=${details.image.substringAfterLast('/')}") }
                }
            }
        }

        binding.rowReleaseNextEpisode.isVisible = nextEpisode != null
        if (nextEpisode != null) {
            binding.rowReleaseNextEpisodeTitle.text = nextEpisode.title
            binding.rowReleaseNextEpisodeSubtitle.text = nextEpisode.subtitle
            binding.rowReleaseNextEpisode.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = dp(if (showPoster) 294 else 48)
            }
        }

        bindActions(details)
    }

    private fun bindChips(chips: List<DetailChip>) {
        val container = binding.rowReleaseChips
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        chips.forEach { chip ->
            val view = inflater.inflate(R.layout.item_detail_chip, container, false) as TextView
            view.text = chip.text
            if (chip.isStatus) {
                view.setBackgroundResource(R.drawable.bg_detail_chip_status)
                view.setTextColor(0xFF222222.toInt())
                view.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            container.addView(view)
        }
    }

    private fun bindProgress(progress: DetailProgress?) {
        binding.rowReleaseProgress.isVisible = progress != null
        progress ?: return
        val duration = progress.durationSec
        val timeText = buildString {
            append(formatTime(progress.positionSec))
            if (duration != null) {
                append(" из ")
                append(formatTime(duration))
            }
        }
        binding.rowReleaseProgressTitle.text =
            "Вы остановились на ${progress.episodeLabel} серии · $timeText"
        val watchedText = buildString {
            append("просмотрено ${progress.watchedCount}")
            progress.totalCount?.also { append(" из $it") }
        }
        binding.rowReleaseProgressSubtitle.text = listOfNotNull(
            progress.episodeName?.let { "«$it»" },
            watchedText
        ).joinToString(" · ")
        val fraction = if (duration != null && duration > 0) {
            (progress.positionSec.toFloat() / duration).coerceIn(0f, 1f)
        } else {
            0f
        }
        binding.rowReleaseProgressBar.progress = (fraction * binding.rowReleaseProgressBar.max).toInt()
    }

    private fun bindActions(details: LibriaDetails) {
        val progress = details.progress
        val continueButton = binding.rowReleaseActionContinue
        val playButton = binding.rowReleaseActionPlay

        continueButton.isVisible = progress != null
        if (progress != null) {
            continueButton.text = "▶ Продолжить · серия ${progress.episodeLabel}"
            playButton.text = "Смотреть"
        } else {
            val first = details.firstEpisodeLabel ?: "1"
            playButton.text = "▶ Смотреть · серия $first"
        }
        playButton.isVisible = details.hasEpisodes

        binding.rowReleaseActionFavorite.text = if (details.isFavorite) {
            "★ В избранном"
        } else {
            "☆ В избранное"
        }
        binding.rowReleaseActionCollection.text = "${details.collectionName ?: "В коллекцию"} ▾"

        applyAutoFocus()
    }

    private fun applyAutoFocus() {
        if (!autoFocusActive || lastDetails == null || !binding.rowReleaseActions.isVisible) return
        val firstPill = when {
            binding.rowReleaseActionContinue.isVisible -> binding.rowReleaseActionContinue
            binding.rowReleaseActionPlay.isVisible -> binding.rowReleaseActionPlay
            else -> binding.rowReleaseActionFavorite
        }
        if (firstPill === autoFocusTarget && firstPill.isFocused) return
        val previousTarget = autoFocusTarget
        autoFocusTarget = firstPill
        if (!firstPill.requestFocus()) {
            autoFocusTarget = previousTarget
        }
    }

    private fun formatTime(totalSec: Int): String {
        val sec = totalSec.coerceAtLeast(0)
        val hours = sec / 3600
        val minutes = sec % 3600 / 60
        val seconds = sec % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
