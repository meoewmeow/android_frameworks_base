package dev.rime.ui.models

import dev.rime.ui.settings.ClockAppearance
import dev.rime.ui.settings.SurfaceAppearance

/** Fractions of the source image, 0..1 on both axes. */
data class NormalizedRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width get() = right - left
    val height get() = bottom - top
    /** Damaged or degenerate values become the whole image rather than an unusable sliver. */
    fun validated(): NormalizedRect {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return Full
        val l = left.coerceIn(0f, 1f); val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f); val b = bottom.coerceIn(0f, 1f)
        return if (r - l < MIN_SIZE || b - t < MIN_SIZE) Full else NormalizedRect(l, t, r, b)
    }
    companion object {
        const val MIN_SIZE = 0.05f
        val Full = NormalizedRect()
    }
}

/** A null asset follows the device's own wallpaper, whose pixels a host cannot always read. */
data class PresetWallpaper(
    val assetId: String? = null,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val crop: NormalizedRect = NormalizedRect.Full,
    val supportsDarkText: Boolean = false,
    val primaryColorArgb: Int = 0xFF374052.toInt(),
)

data class LockscreenPreset(
    val id: String,
    val name: String,
    val wallpaper: PresetWallpaper = PresetWallpaper(),
    val clock: ClockAppearance = ClockAppearance(),
    val surfaces: SurfaceAppearance = SurfaceAppearance(),
    val depthEnabled: Boolean = false,
    val depth: DepthOptions = DepthOptions(),
    /** Fields from a newer version or another editor, carried through unchanged. */
    val extras: Map<String, String> = emptyMap(),
) {
    companion object {
        /** Ids name asset files, so nothing outside this set is accepted. */
        fun isValidId(id: String) = id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }
    }
}

/**
 * How the photo's subject is found and how it sits in front of the clock. The subject is still
 * found automatically; these only steer it. [outlineSpec] is what Axion's depth service reads.
 */
data class DepthOptions(
    val subject: Subject = Subject.Largest,
    val edge: Edge = Edge.Normal,
    val detail: Detail = Detail.Normal,
    /** The date is cut around the subject too, not only the digits. */
    val includeDate: Boolean = true,
    /** When the subject hides most of the clock, show the clock whole instead. */
    val keepClockReadable: Boolean = true,
    /** iOS-style spatial scene: the background moves with the phone, the subject stays still. */
    val spatial: Boolean = false,
    /** Lowest by default: a calm scene; the Depth sheet slider raises it. */
    val spatialStrength: Float = 0.25f,
    /** Best runs a much larger model (BiRefNet): sharper hair and edges, several seconds per photo. */
    val quality: Quality = Quality.Fast,
    /**
     * Points tapped on the photo (0..1 over the whole photo). When set, the parts of the found
     * subject under them are kept and everything else is dropped; empty keeps [subject]'s choice.
     */
    val picks: List<Pair<Float, Float>> = emptyList(),
) {
    enum class Quality { Fast, Best }
    companion object {
        const val MAX_PICKS = 6
        /** "x:y,x:y" as written by [outlineSpec]. */
        fun parsePicks(encoded: String?): List<Pair<Float, Float>> = encoded.orEmpty().split(',').mapNotNull { pair ->
            val (x, y) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            (x.toFloatOrNull() ?: return@mapNotNull null) to (y.toFloatOrNull() ?: return@mapNotNull null)
        }
    }
    enum class Subject { Largest, All }
    enum class Edge { Tight, Normal, Loose }
    enum class Detail { Smooth, Normal, Fine }
    fun validated() = copy(picks = picks.filter { (x, y) -> x in 0f..1f && y in 0f..1f }.take(MAX_PICKS), spatialStrength = (if (spatialStrength.isFinite()) spatialStrength else 0.25f).coerceIn(0.25f, 2f))
    val outlineSpec: String get() = "subject=${subject.name.lowercase()};edge=${edge.name.lowercase()};detail=${detail.name.lowercase()}" +
        (if (quality == Quality.Best) ";quality=best" else "") +
        (if (picks.isEmpty()) "" else ";pick=" + picks.joinToString(",") { (x, y) -> "%.4f:%.4f".format(java.util.Locale.ROOT, x, y) })
}

/** The stored collection. It may be empty: the lockscreen then keeps the device wallpaper and current appearance. */
data class PresetCollection(val presets: List<LockscreenPreset> = emptyList(), val activeId: String? = null) {
    val active: LockscreenPreset? get() = presets.firstOrNull { it.id == activeId }
    fun validated(): PresetCollection {
        val kept = presets.filter { LockscreenPreset.isValidId(it.id) }.distinctBy { it.id }
        return PresetCollection(kept, activeId.takeIf { id -> kept.any { it.id == id } })
    }
    /** Adds a preset, or replaces the one with the same id in place. */
    fun with(preset: LockscreenPreset): PresetCollection =
        if (presets.any { it.id == preset.id }) copy(presets = presets.map { if (it.id == preset.id) preset else it })
        else copy(presets = presets + preset)
    /** Removing the active preset hands the role to its neighbour, the one before it first. */
    fun without(id: String): PresetCollection {
        val index = presets.indexOfFirst { it.id == id }
        if (index < 0) return this
        val rest = presets.filterNot { it.id == id }
        val nextActive = if (activeId != id) activeId else rest.getOrNull((index - 1).coerceAtLeast(0))?.id
        return PresetCollection(rest, nextActive)
    }
}
