package dev.rime.ui.backdrop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Tint-only backdrop, for a window that cannot blur what is behind it. It never claims to blur. */
class TintBackdropRenderer(private val forceTranslucentFallback: Boolean = false) : BackdropSurfaceRenderer {
    @Composable
    override fun Render(
        style: BackdropStyle,
        modifier: Modifier,
        content: @Composable BoxScope.() -> Unit,
    ) {
        val shape = style.customShape ?: when (style.shape) {
            BackdropShape.RoundedRectangle -> RoundedCornerShape(style.cornerRadius)
            BackdropShape.Capsule -> RoundedCornerShape(50)
            BackdropShape.Circle -> CircleShape
        }
        val opacity = if (forceTranslucentFallback) {
            (style.opacity + 0.12f).coerceIn(0f, 0.95f)
        } else {
            style.opacity.coerceIn(0f, 1f)
        }
        Box(
            modifier = modifier
                .clip(shape)
                .background(style.tint.copy(alpha = opacity))
                .then(if (style.customShape == null) Modifier.border(0.8.dp, Color.White.copy(alpha = 0.10f), shape) else Modifier),
            content = content,
        )
    }
}
