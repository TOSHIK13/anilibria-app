package ru.radiationx.anilibria.ui.presenter

import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import coil3.dispose
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.ui.util.showBlurred
import ru.radiationx.anilibria.ui.widget.ContinueCardView
import ru.radiationx.shared_app.imageloader.showImageUrl
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Карточка ряда «Продолжить просмотр» ([LibriaCard.continueInfo] != null): кадр серии 16:9
 * с полосой таймкода, под ним название и «Серия N · ещё M мин».
 * Без кадра — размытый постер, резкий постер слева и «Серия N / mm:ss из mm:ss».
 */
class ContinueCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.card_continue, parent, false) as ContinueCardView
        return ContinueCardViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        item ?: return
        (viewHolder as ContinueCardViewHolder).bind(item as LibriaCard)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder as ContinueCardViewHolder).unbind()
    }
}

class ContinueCardViewHolder(
    private val cardView: ContinueCardView,
) : Presenter.ViewHolder(cardView) {

    private val preview: ImageView = cardView.findViewById(R.id.continuePreview)
    private val fallback: View = cardView.findViewById(R.id.continueFallback)
    private val blur: ImageView = cardView.findViewById(R.id.continueBlur)
    private val poster: ImageView = cardView.findViewById(R.id.continuePoster)
    private val episodeText: TextView = cardView.findViewById(R.id.continueEpisode)
    private val timeText: TextView = cardView.findViewById(R.id.continueTime)
    private val title: TextView = cardView.findViewById(R.id.continueTitle)
    private val subtitle: TextView = cardView.findViewById(R.id.continueSubtitle)

    private var boundItem: LibriaCard? = null

    init {
        val posterCorner = 4 * cardView.resources.displayMetrics.density
        poster.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, posterCorner)
            }
        }
        poster.clipToOutline = true
    }

    fun bind(item: LibriaCard) {
        boundItem = item
        val info = item.continueInfo
        val position = info?.positionSec ?: 0
        val duration = info?.durationSec
        val episodeLabel = info?.ordinalLabel?.let { "Серия $it" }

        title.text = item.title
        subtitle.text = listOfNotNull(
            episodeLabel,
            if (duration != null) {
                "ещё ${max(1, ((duration - position) / 60f).roundToInt())} мин"
            } else {
                formatTime(position)
            }
        ).joinToString(" · ")
        cardView.overlayDrawable.setProgress(duration?.let { position.toFloat() / it })

        episodeText.text = episodeLabel ?: "Продолжить"
        timeText.text = if (duration != null) {
            "${formatTime(position)} из ${formatTime(duration)}"
        } else {
            formatTime(position)
        }

        val previewUrl = info?.previewUrl
        if (previewUrl != null) {
            showPreview(item, previewUrl)
        } else {
            showFallback(item)
        }
    }

    fun unbind() {
        boundItem = null
    }

    private fun showPreview(item: LibriaCard, url: String) {
        fallback.visibility = View.GONE
        preview.visibility = View.VISIBLE
        preview.showImageUrl(url) {
            // Кадр не загрузился — показываем постер, как без превью.
            onError { if (boundItem === item) showFallback(item) }
        }
    }

    private fun showFallback(item: LibriaCard) {
        preview.visibility = View.GONE
        preview.dispose()
        preview.setImageDrawable(null)
        fallback.visibility = View.VISIBLE
        blur.showBlurred(item.image, BLUR_RADIUS_DP, BLUR_BRIGHTNESS)
        poster.showImageUrl(item.image)
    }

    /** m:ss (минуты без ведущего нуля), от часа — h:mm:ss. */
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

    private companion object {
        const val BLUR_RADIUS_DP = 12f
        const val BLUR_BRIGHTNESS = 0.45f
    }
}
