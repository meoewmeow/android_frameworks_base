package dev.rime.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.rime.ui.settings.SurfaceAppearance
import dev.rime.ui.backdrop.BackdropShape
import dev.rime.ui.backdrop.BackdropStyle

data class UiPalette(
    val foreground: Color,
    val secondary: Color,
    val accent: Color,
    val surfaceTint: Color,
    val surfaceOpacity: Float,
    val controlSurface: Color,
    val controlOpacity: Float,
    val outline: Color,
    val cornerRadiusDp: Float = 28f,
    val blurRadiusDp: Float = 30f,
    /** Text and icons on surfaces (notifications, media, controls); [foreground] is for text over the wallpaper. */
    val onSurface: Color = foreground,
    val onSurfaceSecondary: Color = secondary,
)

fun uiPalette(supportsDarkText: Boolean, wallpaperPrimary: Color, appearance: SurfaceAppearance = SurfaceAppearance(), accent: Color = Color(0xFF246A9C),
    themeSurface: Color? = null, themeOnSurface: Color? = null, themeOnSurfaceVariant: Color? = null): UiPalette {
    return if (supportsDarkText) {
        UiPalette(
            foreground = Color(0xFF17191D),
            secondary = Color(0xB317191D),
            accent = accent,
            surfaceTint = Color.White,
            surfaceOpacity = 0.50f,
            controlSurface = Color.White,
            controlOpacity = 0.65f,
            outline = Color(0x35FFFFFF),
        )
    } else {
        UiPalette(
            foreground = Color(0xFFF9F9FB),
            secondary = Color(0xCCF9F9FB),
            accent = accent,
            surfaceTint = Color(0xFF16181E),
            surfaceOpacity = 0.38f,
            controlSurface = Color(0xFF242831),
            controlOpacity = 0.62f,
            outline = Color(0x40FFFFFF),
        )
    }.let { palette ->
        // Surfaces match Android's own notifications when the host supplies their colours; the
        // text on them follows, while text over the wallpaper keeps following the wallpaper.
        val tint = themeSurface?.copy(alpha = 1f) ?: palette.surfaceTint
        palette.copy(accent = accent, surfaceTint = tint, surfaceOpacity = appearance.opacity, controlOpacity = appearance.opacity,
            controlSurface = tint, onSurface = themeOnSurface ?: palette.foreground,
            onSurfaceSecondary = themeOnSurfaceVariant ?: palette.secondary, cornerRadiusDp = appearance.cornerRadiusDp,
            blurRadiusDp = appearance.blurRadiusDp)
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Shared styling factory used by notifications and QS; only geometry and active tint differ. */
fun UiPalette.surfaceStyle(shape: BackdropShape = BackdropShape.RoundedRectangle, selected: Boolean = false) =
    BackdropStyle(shape = shape, cornerRadius = cornerRadiusDp.dp,
        tint = if (selected) accent else surfaceTint,
        opacity = if (selected) 0.92f else surfaceOpacity, blurRadius = blurRadiusDp.dp)
