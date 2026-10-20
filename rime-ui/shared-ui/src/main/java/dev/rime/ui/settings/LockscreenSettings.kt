package dev.rime.ui.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * Glass clock faces. Each is drawn from a font the ROM already ships, as glyph outlines, so every
 * face gets the glass blur, dual tone and depth. [variation] holds the font's variable axes; a
 * face whose axes include 'wght' follows [ClockAppearance.fontWeight]. [stretch] fills the clock
 * box like Axion's wide face; other faces keep their proportions.
 */
enum class ClockFace(val label: String, val asset: String?, val variation: String = "", val defaultWeight: Int = 400,
    val stretch: Boolean = false, val colon: Boolean = true) {
    AxionWide("Axion wide", "rime/fonts/axion_wide.ttf", "'opsz' 1010, 'HGHT' 460", 1000, stretch = true, colon = false),
    SystemSans("System", null, defaultWeight = 400),
    AxionTall("Axion tall", "rime/fonts/axion_bold.ttf", "'opsz' 1010, 'HGHT' 800", 440, colon = false),
    Serif("Serif", "rime/fonts/serif.ttf"),
    Rounded("Rounded", "rime/fonts/rounded.ttf"),
    Stripes("Stripes", "rime/fonts/stripes.ttf"),
    Soft("Soft", "rime/fonts/soft.ttf"),
    Thin("Thin", "rime/fonts/thin.ttf"),
    Italic("Italic", "rime/fonts/italic.ttf"),
    Bubble("Bubble", "rime/fonts/bubble.ttf"),
    Brush("Brush", "rime/fonts/brush.ttf"),
    Playfair("Playfair", "rime/fonts/playfair.ttf"),
    Bebas("Bebas", "rime/fonts/bebas.ttf"),
    Outline("Outline", "rime/fonts/outline.otf"),
    Pixel("Pixel", "rime/fonts/pixel.ttf"),
    Heavy("Heavy", "rime/fonts/heavy.otf");

    /** Faces whose weight can be tuned. */
    val variableWeight get() = this == AxionWide || this == AxionTall || this == SystemSans

    companion object {
        fun parse(name: String?) = entries.firstOrNull { it.name == name }
    }
}

/** The top date row. */
data class DateStyle(
    val sizeSp: Float = 16f,
    val weight: Weight = Weight.Regular,
    val format: Format = Format.Short,
    val extra: Extra = Extra.None,
    val color: Tint = Tint.Clock,
    val uppercase: Boolean = false,
) {
    enum class Weight(val label: String, val value: Int) { Light("Light", 300), Regular("Regular", 400), Medium("Medium", 500), Bold("Bold", 700) }
    /** [skeleton] is a locale-independent pattern skeleton; the locale decides order and punctuation. */
    enum class Format(val label: String, val skeleton: String) {
        Short("Sat, 10 Oct", "EEEdMMM"), Full("Saturday, 10 October", "EEEEdMMMM"), DayMonth("10 October", "dMMMM"),
        Weekday("Saturday", "EEEE"), Numeric("10/10", "dM")
    }
    enum class Extra(val label: String) { None("None"), Weather("Weather"), Alarm("Next alarm"), Battery("Battery") }
    enum class Tint(val label: String) { Clock("Clock"), White("White"), Accent("Accent") }

    fun validated() = copy(sizeSp = finite(sizeSp, 16f).coerceIn(12f, 40f))

    /** One Secure value: "1;size;weight;format;extra;tint;upper". */
    fun encode() = listOf("1", sizeSp.toString(), weight.name, format.name, extra.name, color.name, if (uppercase) "1" else "0").joinToString(";")

    companion object {
        fun decode(encoded: String?): DateStyle? {
            val f = encoded?.split(';')?.takeIf { it.size >= 7 && it[0] == "1" } ?: return null
            val d = DateStyle()
            return DateStyle(
                sizeSp = f[1].toFloatOrNull() ?: d.sizeSp,
                weight = Weight.entries.firstOrNull { it.name == f[2] } ?: d.weight,
                format = Format.entries.firstOrNull { it.name == f[3] } ?: d.format,
                extra = Extra.entries.firstOrNull { it.name == f[4] } ?: d.extra,
                color = Tint.entries.firstOrNull { it.name == f[5] } ?: d.color,
                uppercase = f[6] == "1",
            ).validated()
        }
    }
}

/** Host-independent Axion presentation settings. Units and defaults are shared by both hosts. */
data class ClockAppearance(
    val sizeScale: Float = 1.35f,
    val heightScale: Float = 1f,
    val face: ClockFace = ClockFace.AxionWide,
    val horizontalOffsetDp: Float = 0f,
    val topPaddingDp: Float = 0f,
    val dateBelow: Boolean = false,
    val showDate: Boolean = true,
    val dualTone: Boolean = true,
    val colorArgb: Int? = null,
    val translucentDigits: Boolean = true,
    val digitOpacity: Float = 0.74f,
    /** The glass digits' own blur; null follows the shared surface blur. */
    val blurRadiusDp: Float? = null,
    /** Hours above minutes, iOS style. */
    val stacked: Boolean = false,
    /** Null uses the face's own weight; only [ClockFace.variableWeight] faces follow it. */
    val fontWeight: Int? = null,
    val date: DateStyle = DateStyle(),
) {
    fun validated() = copy(
        heightScale = finite(heightScale, 1f).coerceIn(0.5f, 1.5f),
        sizeScale = finite(sizeScale, 1f).coerceIn(0.5f, 1.35f),
        horizontalOffsetDp = finite(horizontalOffsetDp, 0f).coerceIn(-160f, 160f),
        topPaddingDp = finite(topPaddingDp, 0f).coerceIn(-170f, 240f),
        digitOpacity = finite(digitOpacity, 0.74f).coerceIn(0.1f, 1f),
        blurRadiusDp = blurRadiusDp?.takeIf { it.isFinite() }?.coerceIn(0f, 64f),
        fontWeight = fontWeight?.coerceIn(100, 1000),
        date = date.validated(),
    )
}

/** One surface design for notification backgrounds, media, and QS controls. */
data class SurfaceAppearance(
    val cornerRadiusDp: Float = 28f,
    val blurRadiusDp: Float = 30f,
    val opacity: Float = 0.72f,
) {
    fun validated() = copy(
        cornerRadiusDp = finite(cornerRadiusDp, 28f).coerceIn(8f, 48f),
        blurRadiusDp = finite(blurRadiusDp, 30f).coerceIn(0f, 64f),
        opacity = finite(opacity, 0.72f).coerceIn(0.3f, 0.95f),
    )
}

data class LockscreenSettings(
    val clock: ClockAppearance = ClockAppearance(),
    val surfaces: SurfaceAppearance = SurfaceAppearance(),
    val controls: List<dev.rime.ui.controlcenter.ControlLayoutItem> = dev.rime.ui.controlcenter.ControlCenterLayout.defaults,
) {
    fun validated() = copy(clock = clock.validated(), surfaces = surfaces.validated(),
        controls = dev.rime.ui.controlcenter.ControlCenterLayout.validate(controls))
}

internal fun finite(value: Float, fallback: Float) = if (value.isFinite()) value else fallback

interface LockscreenSettingsSource {
    val lockscreenSettings: StateFlow<LockscreenSettings>
    fun updateLockscreenSettings(settings: LockscreenSettings)
}
