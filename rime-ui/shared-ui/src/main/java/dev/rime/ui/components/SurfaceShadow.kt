package dev.rime.ui.components

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp

/** Soft shadow drawn only outside the surface, so a translucent card is not darkened from behind. */
fun Modifier.surfaceShadow(cornerRadius: Dp, opacity: Float, blur: Dp, offsetY: Dp): Modifier =
    if (opacity <= 0f) this else drawWithCache {
        val radius = cornerRadius.toPx().coerceAtMost(size.minDimension / 2f)
        val outline = Path().apply { addRoundRect(0f, 0f, size.width, size.height, radius, radius, Path.Direction.CW) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.TRANSPARENT
            setShadowLayer(blur.toPx().coerceAtLeast(0.1f), 0f, offsetY.toPx(),
                Color.Black.copy(alpha = opacity.coerceIn(0f, 1f)).toArgb())
        }
        onDrawBehind {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val save = native.save()
                native.clipOutPath(outline)
                native.drawPath(outline, paint)
                native.restoreToCount(save)
            }
        }
    }
