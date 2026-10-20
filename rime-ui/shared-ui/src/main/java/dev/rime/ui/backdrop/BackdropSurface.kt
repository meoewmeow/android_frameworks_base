package dev.rime.ui.backdrop

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class BackdropShape {
    RoundedRectangle,
    Capsule,
    Circle,
}

/** Describes the desired appearance without choosing how blur is rendered. */
data class BackdropStyle(
    val shape: BackdropShape = BackdropShape.RoundedRectangle,
    val cornerRadius: Dp = 28.dp,
    val tint: Color = Color.White,
    val opacity: Float = 0.18f,
    val blurRadius: Dp = 24.dp,
    /** Optional geometric mask, including glyphs. Contains no renderer-specific parameters. */
    val customShape: Shape? = null,
    /**
     * Opacity and uniform scale the parent layout applies to this surface through a layer.
     * Content drawn inside the composition already follows that layer. A renderer applies
     * these only to effects outside it, such as a native blur region.
     */
    val alpha: Float = 1f,
    val scale: Float = 1f,
)

/** False while hidden or fully faded: a host must then hold no blur request for the surface. */
val BackdropStyle.requestsBlur: Boolean get() = alpha > 0.01f && blurRadius.value > 0f

/** Host-owned implementation for one translucent surface. */
interface BackdropSurfaceRenderer {
    @Composable
    fun Render(
        style: BackdropStyle,
        modifier: Modifier,
        content: @Composable BoxScope.() -> Unit,
    )
}

val LocalBackdropSurfaceRenderer = staticCompositionLocalOf<BackdropSurfaceRenderer> {
    error("RimeUiRoot requires a host BackdropSurfaceRenderer")
}

/** The only shared UI entry point for a translucent or blurred surface. */
@Composable
fun BackdropSurface(
    style: BackdropStyle,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    LocalBackdropSurfaceRenderer.current.Render(style, modifier, content)
}

@Composable
fun ProvideBackdropSurfaceRenderer(
    renderer: BackdropSurfaceRenderer,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBackdropSurfaceRenderer provides renderer, content = content)
}
