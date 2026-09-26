package ru.radiationx.anilibria.ui.widget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

/**
 * Линейный градиент с произвольными опорными точками (GradientDrawable до API 29 умеет только три).
 * [positions] — доли 0..1 от начала [direction] (слева для [Direction.LEFT_TO_RIGHT],
 * снизу для [Direction.BOTTOM_TO_TOP]).
 */
class GradientStopsDrawable(
    private val direction: Direction,
    @ColorInt private val colors: IntArray,
    private val positions: FloatArray,
) : Drawable() {

    enum class Direction { LEFT_TO_RIGHT, BOTTOM_TO_TOP }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        paint.shader = when (direction) {
            Direction.LEFT_TO_RIGHT -> LinearGradient(
                bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f,
                colors, positions, Shader.TileMode.CLAMP
            )

            Direction.BOTTOM_TO_TOP -> LinearGradient(
                0f, bounds.bottom.toFloat(), 0f, bounds.top.toFloat(),
                colors, positions, Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
