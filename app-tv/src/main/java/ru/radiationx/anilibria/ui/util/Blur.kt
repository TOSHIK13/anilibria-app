package ru.radiationx.anilibria.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.widget.ImageView
import androidx.annotation.RequiresApi
import coil3.size.Size
import coil3.transform.Transformation
import ru.radiationx.anilibria.R
import ru.radiationx.shared_app.imageloader.showImageUrl
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Размытый и затемнённый фон из картинки (карточки «Продолжить просмотр», позже — герой главной
 * и фон деталей).
 *
 * - API ≥ 31: картинка грузится как обычно (общий кэш с резким постером), размытие и затемнение —
 *   [RenderEffect] на вью при отрисовке;
 * - API < 31 (или `R.bool.force_legacy_blur` для проверки): [BlurTransformation] в Coil —
 *   уменьшение в [BlurTransformation.DOWNSCALE] раз, stack blur и затемнение в Kotlin.
 */
object Blur {

    fun isLegacy(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.resources.getBoolean(R.bool.force_legacy_blur)

    @RequiresApi(Build.VERSION_CODES.S)
    internal fun renderEffect(radiusPx: Float, brightness: Float): RenderEffect {
        val blur = RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
        val darken = RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(ColorMatrix().apply { setScale(brightness, brightness, brightness, 1f) })
        )
        // Сначала размытие (inner), затем затемнение (outer).
        return RenderEffect.createChainEffect(darken, blur)
    }
}

/**
 * Показывает [url] размытым на [radiusDp] и затемнённым до [brightness] (0..1).
 * Вью должна быть отдельной (не переиспользоваться для резких картинок) либо сбрасываться [clearBlur].
 */
fun ImageView.showBlurred(url: String?, radiusDp: Float, brightness: Float) {
    val radiusPx = radiusDp * resources.displayMetrics.density
    if (Blur.isLegacy(context)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
        showImageUrl(url) {
            transformations(BlurTransformation(radiusPx, brightness))
        }
    } else {
        setRenderEffect(Blur.renderEffect(radiusPx, brightness))
        showImageUrl(url)
    }
}

fun ImageView.clearBlur() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRenderEffect(null)
}

/** Размытие и затемнение битмапа для API < 31. Результат меньше исходника — вью растянет его. */
class BlurTransformation(
    private val radiusPx: Float,
    private val brightness: Float,
) : Transformation() {

    override val cacheKey: String = "blur-${radiusPx.roundToInt()}-${(brightness * 100).roundToInt()}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val source = if (input.config == Bitmap.Config.ARGB_8888) {
            input
        } else {
            input.copy(Bitmap.Config.ARGB_8888, false)
        }
        val width = max(1, source.width / DOWNSCALE)
        val height = max(1, source.height / DOWNSCALE)
        val small = Bitmap.createScaledBitmap(source, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        val radius = max(1, (radiusPx / DOWNSCALE).roundToInt())
        stackBlur(pixels, width, height, radius)
        darken(pixels, brightness)
        val output = if (small.isMutable && small != source) {
            small
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    companion object {
        const val DOWNSCALE = 8

        /**
         * Stack blur: треугольное ядро радиуса [radius] = два прохода скользящего среднего
         * шириной radius + 1 (со сдвигом в разные стороны) по строкам и по столбцам.
         */
        internal fun stackBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
            val tmp = IntArray(pixels.size)
            val before = radius / 2
            val after = radius - before
            boxPass(pixels, tmp, width, height, horizontal = true, before, after)
            boxPass(tmp, pixels, width, height, horizontal = true, after, before)
            boxPass(pixels, tmp, width, height, horizontal = false, before, after)
            boxPass(tmp, pixels, width, height, horizontal = false, after, before)
        }

        private fun boxPass(
            src: IntArray,
            dst: IntArray,
            width: Int,
            height: Int,
            horizontal: Boolean,
            before: Int,
            after: Int,
        ) {
            val length = if (horizontal) width else height
            val lines = if (horizontal) height else width
            val step = if (horizontal) 1 else width
            val div = before + after + 1
            val last = length - 1
            for (line in 0 until lines) {
                val base = if (horizontal) line * width else line
                var r = 0
                var g = 0
                var b = 0
                for (k in -before..after) {
                    val p = src[base + k.coerceIn(0, last) * step]
                    r += p shr 16 and 0xFF
                    g += p shr 8 and 0xFF
                    b += p and 0xFF
                }
                for (i in 0 until length) {
                    dst[base + i * step] =
                        (0xFF shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
                    val out = src[base + (i - before).coerceIn(0, last) * step]
                    val inp = src[base + (i + after + 1).coerceIn(0, last) * step]
                    r += (inp shr 16 and 0xFF) - (out shr 16 and 0xFF)
                    g += (inp shr 8 and 0xFF) - (out shr 8 and 0xFF)
                    b += (inp and 0xFF) - (out and 0xFF)
                }
            }
        }

        private fun darken(pixels: IntArray, brightness: Float) {
            if (brightness >= 1f) return
            val k = (brightness.coerceIn(0f, 1f) * 256).toInt()
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = ((p shr 16 and 0xFF) * k) shr 8
                val g = ((p shr 8 and 0xFF) * k) shr 8
                val b = ((p and 0xFF) * k) shr 8
                pixels[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
