package ru.radiationx.anilibria.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.TextViewCompat
import ru.radiationx.anilibria.R
import ru.radiationx.anilibria.screen.mainpages.MainHeroState
import ru.radiationx.anilibria.ui.util.clearBlur
import ru.radiationx.anilibria.ui.util.showBlurred
import ru.radiationx.shared.ktx.android.getCompatColor
import ru.radiationx.shared_app.imageloader.showImageUrl
import kotlin.math.roundToInt

/**
 * Hero-блок главных страниц: фон релиза в фокусе (широкий фон справа или размытый постер
 * на весь экран) со скримом и тексты слева сверху. Фон меняется кроссфейдом, когда новая
 * картинка загрузилась, поэтому пустой кадр между релизами не мелькает.
 */
class MainHeroView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private companion object {
        const val CROSSFADE_MS = 200L
        const val TEXT_FADE_MS = 100L
        const val VISIBILITY_FADE_MS = 150L

        /** Сколько тексты ждут загрузки нового фона. */
        const val TEXT_WAIT_MS = 600L

        const val COVER_BRIGHTNESS = 0.8f
        const val BLUR_RADIUS_DP = 20f
        const val BLUR_BRIGHTNESS = 0.5f
        const val BLUR_SATURATION = 1.15f
        const val BLUR_SCALE = 1.15f

        const val PLAY_CHAR = '▶'
    }

    private val backgroundContainer: FrameLayout
    private val textsContainer: View
    private val titleView: TextView
    private val metaView: TextView
    private val lineView: TextView
    private val descriptionView: TextView
    private val posterView: ImageView
    private var shownPosterUrl: String? = null

    private val baseColor = context.getCompatColor(R.color.main_hero_background)
    private val accentColor = context.getCompatColor(R.color.main_hero_accent)

    private val layers: List<Layer>
    private var frontLayer: Layer
    private var shownBackgroundKey: String? = null
    private var pendingBackgroundKey: String? = null
    private var shownTextKey: String? = null
    private var loadGeneration = 0
    private var pendingTexts: MainHeroState? = null
    private val applyTextsRunnable = Runnable { applyTexts() }

    init {
        setBackgroundColor(baseColor)
        LayoutInflater.from(context).inflate(R.layout.view_main_hero, this, true)
        backgroundContainer = findViewById(R.id.mainHeroBackground)
        textsContainer = findViewById(R.id.mainHeroTexts)
        titleView = findViewById(R.id.mainHeroTitle)
        metaView = findViewById(R.id.mainHeroMeta)
        lineView = findViewById(R.id.mainHeroLine)
        descriptionView = findViewById(R.id.mainHeroDescription)
        posterView = findViewById(R.id.mainHeroPoster)
        val posterRadius = resources.getDimension(R.dimen.main_hero_poster_radius)
        posterView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, posterRadius)
            }
        }
        posterView.clipToOutline = true
        TextViewCompat.setLineHeight(
            descriptionView,
            resources.getDimensionPixelSize(R.dimen.main_hero_description_line_height)
        )
        layers = listOf(Layer(context), Layer(context))
        layers.forEach {
            it.root.alpha = 0f
            backgroundContainer.addView(it.root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        frontLayer = layers[0]
        bindTexts(null)
    }

    private var contentVisible = true

    /** Показ блока (на страницах без hero, например «Настройки», остаётся только цвет фона). */
    fun setContentVisible(visible: Boolean) {
        contentVisible = visible
        val target = if (visible) 1f else 0f
        listOf(backgroundContainer, textsContainer).forEach {
            it.animate().cancel()
            it.animate().alpha(target).setDuration(VISIBILITY_FADE_MS).start()
        }
    }

    fun bind(state: MainHeroState?) {
        pendingTexts = state
        removeCallbacks(applyTextsRunnable)
        val backgroundLoading = bindBackground(state)
        if (backgroundLoading && state?.key != shownTextKey) {
            // Новый релиз: тексты меняем вместе с фоном (или по таймауту, если картинка медленная).
            postDelayed(applyTextsRunnable, TEXT_WAIT_MS)
        } else {
            applyTexts()
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(applyTextsRunnable)
        super.onDetachedFromWindow()
    }

    private fun applyTexts() {
        removeCallbacks(applyTextsRunnable)
        val state = pendingTexts
        if (state == null || state.key != shownTextKey) {
            crossfadeTexts(state)
        } else {
            bindTexts(state)
        }
    }

    private fun crossfadeTexts(state: MainHeroState?) {
        shownTextKey = state?.key
        if (!contentVisible) {
            // Блок скрыт: меняем тексты молча, не прерывая анимацию скрытия.
            bindTexts(state)
            return
        }
        textsContainer.animate().cancel()
        if (textsContainer.alpha == 0f || titleView.text.isNullOrEmpty()) {
            bindTexts(state)
            return
        }
        textsContainer.animate()
            .alpha(0f)
            .setDuration(TEXT_FADE_MS)
            .withEndAction {
                bindTexts(state)
                textsContainer.animate().alpha(1f).setDuration(TEXT_FADE_MS).start()
            }
            .start()
    }

    private fun bindTexts(state: MainHeroState?) {
        titleView.text = state?.title.orEmpty()
        metaView.text = state?.meta.orEmpty()
        metaView.isVisible = !state?.meta.isNullOrEmpty()
        val line = state?.line
        lineView.isVisible = line != null
        lineView.text = line?.let { lineText(it) }
        descriptionView.text = state?.description.orEmpty()
        bindPoster(state)
        descriptionView.updateLayoutParams<LayoutParams> {
            topMargin = resources.getDimensionPixelSize(
                if (line != null) R.dimen.main_hero_description_top else R.dimen.main_hero_description_top_no_line
            )
        }
    }

    /** Постер меняется вместе с текстами; при широком фоне арт уже справа — постер не нужен. */
    private fun bindPoster(state: MainHeroState?) {
        val url = state?.posterUrl?.takeIf { state.coverUrl == null }
        posterView.isVisible = url != null
        if (url == shownPosterUrl) return
        shownPosterUrl = url
        if (url == null) {
            posterView.setImageDrawable(null)
        } else {
            posterView.showImageUrl(url)
        }
    }

    private fun lineText(line: MainHeroState.Line): CharSequence {
        val marker = line.marker ?: return line.text
        // ▶ в шрифтах мелкий — рисуем треугольник на высоту заглавных сами.
        val span = if (marker.startsWith(PLAY_CHAR)) PlayMarkerSpan(accentColor) else ForegroundColorSpan(accentColor)
        return SpannableStringBuilder()
            .append(marker, span, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append(' ')
            .append(line.text)
    }

    /** Треугольник «воспроизведение» высотой с заглавную букву, по базовой линии текста. */
    private class PlayMarkerSpan(@ColorInt private val color: Int) : ReplacementSpan() {

        private val path = Path()

        private fun height(paint: Paint) = paint.textSize * 0.74f

        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int {
            fm?.let { paint.getFontMetricsInt(it) }
            return (height(paint) * 0.92f).roundToInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val h = height(paint)
            val w = h * 0.92f
            val baseline = y.toFloat()
            path.reset()
            path.moveTo(x, baseline - h)
            path.lineTo(x + w, baseline - h / 2f)
            path.lineTo(x, baseline)
            path.close()
            val oldColor = paint.color
            val oldStyle = paint.style
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
            paint.color = oldColor
            paint.style = oldStyle
        }
    }

    /** @return true — новая картинка ещё грузится. */
    private fun bindBackground(state: MainHeroState?): Boolean {
        val key = state?.backgroundKey
        if (key == pendingBackgroundKey) return key != shownBackgroundKey
        pendingBackgroundKey = key
        val generation = ++loadGeneration
        if (key == shownBackgroundKey) {
            // Вернулись к показанной картинке, пока грузилась другая: отменяем загрузку.
            layers.filter { it !== frontLayer }.forEach { it.clear() }
            return false
        }
        val back = layers.first { it !== frontLayer }
        back.root.animate().cancel()
        back.root.alpha = 0f
        if (state == null || key == null) {
            back.clear()
            showLayer(back, key)
            return false
        }
        back.bind(state) {
            if (generation == loadGeneration) {
                showLayer(back, key)
            }
        }
        return key != shownBackgroundKey
    }

    private fun showLayer(layer: Layer, key: String?) {
        val old = frontLayer
        frontLayer = layer
        shownBackgroundKey = key
        applyTexts()
        layer.root.bringToFront()
        layer.root.animate().alpha(1f).setDuration(CROSSFADE_MS).withEndAction {
            if (frontLayer === layer) {
                old.root.alpha = 0f
            }
        }.start()
    }

    /** Слой фона: картинка + скрим под её вид. Слоёв два — для кроссфейда. */
    private inner class Layer(context: Context) {
        /** Непрозрачный: новый слой при кроссфейде целиком закрывает старый. */
        val root = FrameLayout(context).apply { setBackgroundColor(baseColor) }

        private val coverView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setScale(COVER_BRIGHTNESS, COVER_BRIGHTNESS, COVER_BRIGHTNESS, 1f)
            })
        }
        private val blurView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            scaleX = BLUR_SCALE
            scaleY = BLUR_SCALE
        }
        private val scrimHorizontal = View(context)
        private val scrimVertical = View(context)

        init {
            root.addView(blurView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            root.addView(
                coverView,
                LayoutParams(
                    resources.getDimensionPixelSize(R.dimen.main_hero_cover_width),
                    resources.getDimensionPixelSize(R.dimen.main_hero_cover_height),
                    Gravity.END or Gravity.TOP
                )
            )
            root.addView(scrimHorizontal, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            root.addView(scrimVertical, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun bind(state: MainHeroState, onReady: () -> Unit) {
            val cover = state.coverUrl
            if (cover != null) {
                blurView.isVisible = false
                blurView.clearBlur()
                blurView.setImageDrawable(null)
                coverView.isVisible = true
                scrimHorizontal.background = coverScrimHorizontal()
                scrimVertical.background = coverScrimVertical()
                coverView.showImageUrl(cover) { onComplete(onReady) }
            } else {
                coverView.isVisible = false
                coverView.setImageDrawable(null)
                blurView.isVisible = true
                scrimHorizontal.background = blurScrimHorizontal()
                scrimVertical.background = blurScrimVertical()
                blurView.showBlurred(state.posterUrl, BLUR_RADIUS_DP, BLUR_BRIGHTNESS, BLUR_SATURATION) {
                    onComplete(onReady)
                }
            }
        }

        fun clear() {
            coverView.setImageDrawable(null)
            blurView.setImageDrawable(null)
            coverView.isVisible = false
            blurView.isVisible = false
            scrimHorizontal.background = null
            scrimVertical.background = null
        }
    }

    private fun base(alpha: Float): Int = Color.argb(
        (alpha * 255).toInt(),
        Color.red(baseColor),
        Color.green(baseColor),
        Color.blue(baseColor)
    )

    private fun coverScrimHorizontal() = GradientStopsDrawable(
        GradientStopsDrawable.Direction.LEFT_TO_RIGHT,
        intArrayOf(base(1f), base(1f), base(0.55f), base(0f), base(0f)),
        floatArrayOf(0f, 0.22f, 0.48f, 0.72f, 1f)
    )

    private fun coverScrimVertical() = GradientStopsDrawable(
        GradientStopsDrawable.Direction.BOTTOM_TO_TOP,
        intArrayOf(base(1f), base(1f), base(0f), base(0f)),
        floatArrayOf(0f, 0.40f, 0.78f, 1f)
    )

    private fun blurScrimHorizontal() = GradientStopsDrawable(
        GradientStopsDrawable.Direction.LEFT_TO_RIGHT,
        intArrayOf(base(0.75f), base(0.2f), base(0.2f)),
        floatArrayOf(0f, 0.6f, 1f)
    )

    private fun blurScrimVertical() = GradientStopsDrawable(
        GradientStopsDrawable.Direction.BOTTOM_TO_TOP,
        intArrayOf(base(1f), base(1f), base(0.35f), base(0.35f)),
        floatArrayOf(0f, 0.42f, 0.80f, 1f)
    )
}
