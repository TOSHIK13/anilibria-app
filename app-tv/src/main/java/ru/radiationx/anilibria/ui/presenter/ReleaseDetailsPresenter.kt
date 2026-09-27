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
    private val ratingsClickListener: () -> Unit,
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
            ratingsClickListener,
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
    private val ratingsClickListener: () -> Unit,
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
        binding.rowReleaseActionRatings.setOnClickListener { ratingsClickListener.invoke() }
        binding.rowReleaseActionOther.setOnClickListener { otherClickListener.invoke() }
        binding.rowReleaseActionFavorite.setOnClickListener { favoriteClickListener.invoke() }
        // Описание фокусируемое (UP с кнопок): OK открывает полный текст.
        binding.rowReleaseDescriptionBlock.setOnClickListener { descriptionClickListener.invoke() }
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
        // Длинное название иначе обрезается многоточием — прокручиваем его марки как бегущую строку,
        // не трогая остальную вёрстку (позиции элементов ниже фиксированы абсолютно).
        binding.rowReleaseTitleRu.isSelected = true
        binding.rowReleaseTitleEn.text = details.titleEn
        binding.rowReleaseTitleEn.isVisible = details.titleEn.isNotEmpty()
        binding.rowReleaseTitleEn.maxWidth = dp(if (nextOnTop) 620 else 660)

        if (previous?.chips != details.chips) {
            bindChips(details.chips)
        }
        binding.rowReleaseChips.updateLayoutParams { width = dp(if (nextOnTop) 620 else 670) }

        binding.rowReleaseInfo.text = details.infoLine
        binding.rowReleaseInfo.isVisible = details.infoLine.isNotEmpty()

        binding.rowReleaseDescription.text = details.description
        binding.rowReleaseDescriptionBlock.isVisible = details.description.isNotEmpty()

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

    private fun bindActions(details: LibriaDetails) {
        val progress = details.progress
        val continueButton = binding.rowReleaseActionContinue
        val playButton = binding.rowReleaseActionPlay

        continueButton.isVisible = progress != null
        if (progress != null) {
            continueButton.text = if (progress.isRewatch) {
                "↻ Смотреть заново"
            } else {
                "▶ Продолжить · серия ${progress.episodeLabel}"
            }
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
        binding.rowReleaseActionRatings.isVisible = details.ratings != null

        fitPills(progress?.takeIf { !it.isRewatch }?.episodeLabel)

        applyAutoFocus()
    }

    /**
     * Кнопки не должны вылезать за экран: при нехватке места уменьшаем поля кнопок,
     * затем сокращаем «★ Оценки» до «★» и «Продолжить · серия N» до «▶ Серия N».
     * Для «Смотреть заново» сокращать нечего — там нет номера серии.
     */
    private fun fitPills(continueEpisode: String?) {
        val row = binding.rowReleaseActions
        val pills = (0 until row.childCount).map { row.getChildAt(it) as TextView }
        val available = view.resources.displayMetrics.widthPixels - dp(48 + 48)
        val ratings = binding.rowReleaseActionRatings
        val continueButton = binding.rowReleaseActionContinue

        fun setPadding(normal: Int) {
            pills.forEach {
                val horizontal = if (it === binding.rowReleaseActionOther) 19 else normal
                it.setPaddingRelative(dp(horizontal), it.paddingTop, dp(horizontal), it.paddingBottom)
            }
        }

        fun fits(): Boolean {
            row.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            return row.measuredWidth <= available
        }

        ratings.text = "★ Оценки"
        setPadding(26)
        if (fits()) return
        setPadding(18)
        if (fits()) return
        ratings.text = "★"
        if (fits()) return
        if (continueEpisode != null) {
            continueButton.text = "▶ Серия $continueEpisode"
        }
    }

    private fun firstPill(): View = when {
        binding.rowReleaseActionContinue.isVisible -> binding.rowReleaseActionContinue
        binding.rowReleaseActionPlay.isVisible -> binding.rowReleaseActionPlay
        else -> binding.rowReleaseActionFavorite
    }

    private fun applyAutoFocus() {
        // DOWN с описания — всегда на первую кнопку, а не на ближайшую по геометрии.
        binding.rowReleaseDescriptionBlock.nextFocusDownId = firstPill().id
        if (!autoFocusActive || lastDetails == null || !binding.rowReleaseActions.isVisible) return
        val firstPill = firstPill()
        if (firstPill === autoFocusTarget && firstPill.isFocused) return
        val previousTarget = autoFocusTarget
        autoFocusTarget = firstPill
        if (!firstPill.requestFocus()) {
            autoFocusTarget = previousTarget
        }
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
