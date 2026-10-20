package dev.rime.ui.lockscreen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.rime.ui.models.NormalizedRect
import kotlin.math.roundToInt

/** One mapping from a source image to the frame, shared by the wallpaper and its blurred copy. */
object WallpaperGeometry {
    /** The part of the source to draw: the crop, trimmed around its centre to the frame's aspect. */
    fun sourceRect(sourceWidth: Int, sourceHeight: Int, crop: NormalizedRect, frameAspect: Float): Rect {
        val c = crop.validated()
        val width = sourceWidth * c.width; val height = sourceHeight * c.height
        if (width <= 0f || height <= 0f || !frameAspect.isFinite() || frameAspect <= 0f) return Rect.Zero
        val centerX = sourceWidth * (c.left + c.right) / 2f; val centerY = sourceHeight * (c.top + c.bottom) / 2f
        val fitWidth = if (width / height > frameAspect) height * frameAspect else width
        val fitHeight = if (width / height > frameAspect) height else width / frameAspect
        return Rect(centerX - fitWidth / 2f, centerY - fitHeight / 2f, centerX + fitWidth / 2f, centerY + fitHeight / 2f)
    }

    /** How far a photo may be magnified past the point where it just covers the frame. */
    const val MAX_ZOOM = 4f

    /**
     * The crop after a drag and pinch in the editor. [panX] and [panY] are the finger's movement
     * as fractions of the frame; the image follows the finger. The result always has the frame's
     * aspect, stays inside the image, and never shows less than a [MAX_ZOOM] close-up.
     */
    fun adjustCrop(sourceWidth: Int, sourceHeight: Int, crop: NormalizedRect, frameAspect: Float,
        panX: Float, panY: Float, zoom: Float): NormalizedRect {
        val shown = sourceRect(sourceWidth, sourceHeight, crop, frameAspect)
        val cover = sourceRect(sourceWidth, sourceHeight, NormalizedRect.Full, frameAspect)
        if (shown.isEmpty || cover.isEmpty || !panX.isFinite() || !panY.isFinite() || !zoom.isFinite() || zoom <= 0f) return crop
        val closest = maxOf(cover.width / MAX_ZOOM, sourceWidth * NormalizedRect.MIN_SIZE * 1.01f).coerceAtMost(cover.width)
        // Rounding can leave the covering rect a hair larger than the image, which would make the
        // ranges below empty. Clamp to the image and never let a range invert.
        val width = (shown.width / zoom).coerceIn(closest, cover.width).coerceAtMost(sourceWidth.toFloat())
        val height = (width / frameAspect).coerceAtMost(sourceHeight.toFloat())
        val centerX = (shown.center.x - panX * shown.width).coerceIn(width / 2f, maxOf(width / 2f, sourceWidth - width / 2f))
        val centerY = (shown.center.y - panY * shown.height).coerceIn(height / 2f, maxOf(height / 2f, sourceHeight - height / 2f))
        return NormalizedRect((centerX - width / 2f) / sourceWidth, (centerY - height / 2f) / sourceHeight,
            (centerX + width / 2f) / sourceWidth, (centerY + height / 2f) / sourceHeight).validated()
    }
}

/** Fills [frame] with the cropped image. The blurred copy uses the same call, so the two stay aligned. */
fun DrawScope.drawWallpaper(image: ImageBitmap, crop: NormalizedRect, frame: Size = size) {
    if (frame.width < 1f || frame.height < 1f) return
    val source = WallpaperGeometry.sourceRect(image.width, image.height, crop, frame.width / frame.height)
    if (source.width < 1f || source.height < 1f) return
    drawImage(image, IntOffset(source.left.roundToInt(), source.top.roundToInt()),
        IntSize(source.width.roundToInt().coerceAtMost(image.width), source.height.roundToInt().coerceAtMost(image.height)),
        dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()), filterQuality = FilterQuality.Medium)
}

/** Where a self-drawn wallpaper sits, so a host can line a blurred copy up under each surface. */
@Stable
class WallpaperFrameState {
    var coordinates by mutableStateOf<LayoutCoordinates?>(null)
        internal set
}

val LocalWallpaperFrame = compositionLocalOf<WallpaperFrameState?> { null }

/**
 * Draws the wallpaper, if the host has its pixels, behind [content] and publishes the frame.
 * [crop] is read while drawing, so repositioning a photo redraws without recomposing the scene.
 */
@Composable
fun WallpaperFrame(image: ImageBitmap?, crop: () -> NormalizedRect, modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit) {
    val frame = remember { WallpaperFrameState() }
    CompositionLocalProvider(LocalWallpaperFrame provides frame) {
        Box(modifier.onGloballyPositioned { frame.coordinates = it }) {
            if (image != null) Canvas(Modifier.matchParentSize()) { drawWallpaper(image, crop()) }
            content()
        }
    }
}
