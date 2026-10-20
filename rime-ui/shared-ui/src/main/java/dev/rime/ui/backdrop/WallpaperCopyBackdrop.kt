package dev.rime.ui.backdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import dev.rime.ui.lockscreen.LocalWallpaperFrame
import dev.rime.ui.lockscreen.drawWallpaper
import dev.rime.ui.models.NormalizedRect

/**
 * Backdrop for a wallpaper the host draws itself. A window blur only reaches what is behind the
 * window, so each surface shows the matching part of a copy that was blurred once, when the
 * preset was saved. It holds no system blur request and needs a [dev.rime.ui.lockscreen.WallpaperFrame].
 * [crop] is read while drawing and must be the frame's own crop.
 */
class WallpaperCopyBackdropRenderer(private val blurred: ImageBitmap, private val crop: () -> NormalizedRect) : BackdropSurfaceRenderer {
    @Composable
    override fun Render(style: BackdropStyle, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
        val frame = LocalWallpaperFrame.current
        val shape = style.customShape ?: when (style.shape) {
            BackdropShape.RoundedRectangle -> RoundedCornerShape(style.cornerRadius)
            BackdropShape.Capsule -> RoundedCornerShape(50)
            BackdropShape.Circle -> CircleShape
        }
        var surface by remember { mutableStateOf<LayoutCoordinates?>(null) }
        // Read while drawing, so a surface that only moved still redraws its part of the copy.
        var position by remember { mutableStateOf(Offset.Zero) }
        val toSurface = remember { Matrix() }
        Box(modifier.onGloballyPositioned { surface = it; position = it.positionInRoot() }.clip(shape).drawBehind {
            position
            val from = frame?.coordinates; val to = surface
            if (style.blurRadius.value > 0f && from != null && to != null && from.isAttached && to.isAttached) {
                toSurface.reset(); to.transformFrom(from, toSurface)
                withTransform({ transform(toSurface) }) { drawWallpaper(blurred, crop(), from.size.toSize()) }
            }
            drawRect(style.tint.copy(alpha = style.opacity.coerceIn(0f, 1f)))
        }, content = content)
    }
}
