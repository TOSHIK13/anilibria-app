package ru.radiationx.anilibria.screen.details

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import ru.radiationx.anilibria.ui.util.clearBlur
import ru.radiationx.anilibria.ui.util.showBlurred
import ru.radiationx.shared_app.imageloader.showImageUrl

/**
 * Фон экрана деталей: широкая обложка релиза, а без неё — размытый постер;
 * поверх два затемнения (слева направо и снизу вверх) под текст и ряды.
 * Ряды добавляются в эту же вью поверх и прокручиваются, фон остаётся на месте.
 */
class DetailBackgroundView(context: Context) : FrameLayout(context) {

    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    /** Что сейчас показано, чтобы не перезагружать картинку при каждом обновлении релиза. */
    private var shownKey: String? = null

    init {
        setBackgroundColor(BASE_COLOR)
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrimView(horizontal = true), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrimView(horizontal = false), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * [cover] — широкий фон (есть только у полного релиза), [poster] — запасной вариант.
     * Пока полный релиз не загружен ([isFull] = false) и обложки нет, фон не меняется,
     * чтобы размытый постер не мигнул перед обложкой.
     */
    fun bind(cover: String?, poster: String?, isFull: Boolean) {
        when {
            cover != null -> showCover(cover)
            !isFull -> Unit
            poster != null -> showBlurredPoster(poster)
            else -> showPlain()
        }
    }

    private fun showCover(url: String) {
        val key = "cover:$url"
        if (shownKey == key) return
        shownKey = key
        imageView.clearBlur()
        imageView.scaleX = 1f
        imageView.scaleY = 1f
        imageView.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setScale(COVER_BRIGHTNESS, COVER_BRIGHTNESS, COVER_BRIGHTNESS, 1f)
        })
        imageView.animate().cancel()
        imageView.alpha = 0f
        imageView.showImageUrl(url) {
            onComplete { imageView.animate().alpha(1f).setDuration(FADE_MS).start() }
        }
    }

    private fun showBlurredPoster(url: String) {
        val key = "poster:$url"
        if (shownKey == key) return
        shownKey = key
        imageView.animate().cancel()
        imageView.alpha = 1f
        imageView.scaleX = POSTER_SCALE
        imageView.scaleY = POSTER_SCALE
        imageView.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            setSaturation(POSTER_SATURATION)
        })
        imageView.showBlurred(url, POSTER_BLUR_DP, POSTER_BRIGHTNESS)
    }

    private fun showPlain() {
        if (shownKey == KEY_PLAIN) return
        shownKey = KEY_PLAIN
        imageView.animate().cancel()
        imageView.clearBlur()
        imageView.colorFilter = null
        imageView.showImageUrl(null)
        imageView.setImageDrawable(null)
    }

    private fun scrimView(horizontal: Boolean): View = View(context).apply {
        background = PaintDrawable().apply {
            // Без формы ShapeDrawable не применяет shaderFactory и рисует сплошной чёрный.
            shape = RectShape()
            shaderFactory = object : ShapeDrawable.ShaderFactory() {
                override fun resize(width: Int, height: Int): Shader = if (horizontal) {
                    LinearGradient(
                        0f, 0f, width.toFloat(), 0f,
                        intArrayOf(scrim(0.92f), scrim(0.78f), scrim(0.15f), scrim(0f)),
                        floatArrayOf(0f, 0.38f, 0.72f, 1f),
                        Shader.TileMode.CLAMP
                    )
                } else {
                    // Снизу вверх.
                    LinearGradient(
                        0f, height.toFloat(), 0f, 0f,
                        intArrayOf(scrim(0.95f), scrim(0.60f), scrim(0f), scrim(0f)),
                        floatArrayOf(0f, 0.22f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
            }
        }
    }

    private fun scrim(alpha: Float): Int =
        Color.argb((alpha * 255 + 0.5f).toInt(), 10, 14, 18)

    private companion object {
        val BASE_COLOR = Color.parseColor("#15212A")
        const val KEY_PLAIN = "plain"
        const val COVER_BRIGHTNESS = 0.85f
        const val POSTER_BLUR_DP = 20f
        const val POSTER_BRIGHTNESS = 0.5f
        const val POSTER_SATURATION = 1.15f
        const val POSTER_SCALE = 1.15f
        const val FADE_MS = 250L
    }
}
