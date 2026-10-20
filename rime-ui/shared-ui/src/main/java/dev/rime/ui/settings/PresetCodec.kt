package dev.rime.ui.settings

import dev.rime.ui.models.DepthOptions
import dev.rime.ui.models.LockscreenPreset
import dev.rime.ui.models.NormalizedRect
import dev.rime.ui.models.PresetCollection
import dev.rime.ui.models.PresetWallpaper

/** Storage shape of the collection. Hosts write it as they like; every value is a string. */
data class StoredPresets(val version: Int, val activeId: String?, val presets: List<Map<String, String>>)

/**
 * Versioned, flat mapping of presets. Unknown keys survive a round trip, a preset without a
 * usable id is dropped, and any damaged value falls back to its default.
 */
object PresetCodec {
    const val VERSION = 1

    fun encode(collection: PresetCollection): StoredPresets = collection.validated().let { c ->
        StoredPresets(VERSION, c.activeId, c.presets.map(::encode))
    }

    fun decode(stored: StoredPresets): PresetCollection {
        if (stored.version < 1) return PresetCollection()
        return PresetCollection(stored.presets.mapNotNull(::decode), stored.activeId).validated()
    }

    fun encode(preset: LockscreenPreset): Map<String, String> {
        val w = preset.wallpaper; val c = preset.clock.validated(); val s = preset.surfaces.validated()
        val crop = w.crop.validated()
        // Known keys are written last, so a stale copy among the extras can never win.
        return preset.extras + mapOf(
            "id" to preset.id, "name" to preset.name,
            "wallpaper.asset" to (w.assetId ?: ""),
            "wallpaper.width" to w.sourceWidth.toString(), "wallpaper.height" to w.sourceHeight.toString(),
            "wallpaper.crop" to "${crop.left},${crop.top},${crop.right},${crop.bottom}",
            "wallpaper.darkText" to flag(w.supportsDarkText), "wallpaper.color" to w.primaryColorArgb.toString(),
            "clock.face" to c.face.name, "clock.size" to c.sizeScale.toString(), "clock.height" to c.heightScale.toString(),
            "clock.x" to c.horizontalOffsetDp.toString(), "clock.y" to c.topPaddingDp.toString(),
            "clock.dateBelow" to flag(c.dateBelow), "clock.date" to flag(c.showDate), "clock.dual" to flag(c.dualTone),
            "clock.color" to (c.colorArgb?.toString() ?: "auto"),
            "clock.translucent" to flag(c.translucentDigits), "clock.digits" to c.digitOpacity.toString(),
            "clock.blur" to (c.blurRadiusDp?.toString() ?: "auto"),
            "clock.stacked" to flag(c.stacked), "clock.weight" to (c.fontWeight?.toString() ?: "auto"),
            "clock.dateStyle" to c.date.encode(),
            "surface.radius" to s.cornerRadiusDp.toString(), "surface.blur" to s.blurRadiusDp.toString(),
            "surface.opacity" to s.opacity.toString(),
            "depth" to flag(preset.depthEnabled),
            "depth.subject" to preset.depth.subject.name, "depth.edge" to preset.depth.edge.name,
            "depth.detail" to preset.depth.detail.name, "depth.date" to flag(preset.depth.includeDate),
            "depth.readable" to flag(preset.depth.keepClockReadable), "depth.spatial" to flag(preset.depth.spatial),
            "depth.spatialStrength" to preset.depth.spatialStrength.toString(), "depth.quality" to preset.depth.quality.name,
            "depth.picks" to preset.depth.picks.joinToString(",") { (x, y) -> "$x:$y" },
        )
    }

    fun decode(values: Map<String, String>): LockscreenPreset? {
        val id = values["id"]?.takeIf(LockscreenPreset::isValidId) ?: return null
        fun number(key: String, fallback: Float) = values[key]?.toFloatOrNull() ?: fallback
        fun flag(key: String, fallback: Boolean) = values[key]?.let { it == "1" } ?: fallback
        val c = ClockAppearance(); val s = SurfaceAppearance(); val w = PresetWallpaper()
        val crop = values["wallpaper.crop"]?.split(',')?.mapNotNull(String::toFloatOrNull)
            ?.takeIf { it.size == 4 }?.let { NormalizedRect(it[0], it[1], it[2], it[3]) } ?: NormalizedRect.Full
        return LockscreenPreset(
            id = id, name = values["name"] ?: "",
            wallpaper = PresetWallpaper(
                assetId = values["wallpaper.asset"]?.takeIf(LockscreenPreset::isValidId),
                sourceWidth = values["wallpaper.width"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                sourceHeight = values["wallpaper.height"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                crop = crop.validated(),
                supportsDarkText = flag("wallpaper.darkText", w.supportsDarkText),
                primaryColorArgb = values["wallpaper.color"]?.toIntOrNull() ?: w.primaryColorArgb,
            ),
            clock = ClockAppearance(
                face = values["clock.face"]?.let { name -> ClockFace.entries.firstOrNull { it.name == name } } ?: c.face,
                sizeScale = number("clock.size", c.sizeScale), heightScale = number("clock.height", c.heightScale),
                horizontalOffsetDp = number("clock.x", c.horizontalOffsetDp), topPaddingDp = number("clock.y", c.topPaddingDp),
                dateBelow = flag("clock.dateBelow", c.dateBelow), showDate = flag("clock.date", c.showDate),
                dualTone = flag("clock.dual", c.dualTone),
                colorArgb = values["clock.color"].let { if (it == null) c.colorArgb else it.toIntOrNull() },
                translucentDigits = flag("clock.translucent", c.translucentDigits),
                digitOpacity = number("clock.digits", c.digitOpacity),
                blurRadiusDp = values["clock.blur"]?.toFloatOrNull(),
                stacked = flag("clock.stacked", c.stacked),
                fontWeight = values["clock.weight"]?.toIntOrNull(),
                date = DateStyle.decode(values["clock.dateStyle"]) ?: c.date,
            ).validated(),
            surfaces = SurfaceAppearance(
                cornerRadiusDp = number("surface.radius", s.cornerRadiusDp), blurRadiusDp = number("surface.blur", s.blurRadiusDp),
                opacity = number("surface.opacity", s.opacity),
            ).validated(),
            depthEnabled = flag("depth", false),
            depth = DepthOptions().let { d -> DepthOptions(
                subject = DepthOptions.Subject.entries.firstOrNull { it.name == values["depth.subject"] } ?: d.subject,
                edge = DepthOptions.Edge.entries.firstOrNull { it.name == values["depth.edge"] } ?: d.edge,
                detail = DepthOptions.Detail.entries.firstOrNull { it.name == values["depth.detail"] } ?: d.detail,
                includeDate = flag("depth.date", d.includeDate), keepClockReadable = flag("depth.readable", d.keepClockReadable),
                spatial = flag("depth.spatial", d.spatial), spatialStrength = number("depth.spatialStrength", d.spatialStrength),
                quality = DepthOptions.Quality.entries.firstOrNull { it.name == values["depth.quality"] } ?: d.quality,
                picks = DepthOptions.parsePicks(values["depth.picks"]),
            ).validated() },
            extras = values - KNOWN_KEYS,
        )
    }

    private fun flag(value: Boolean) = if (value) "1" else "0"
    private val KNOWN_KEYS = setOf("id", "name", "wallpaper.asset", "wallpaper.width", "wallpaper.height", "wallpaper.crop",
        "wallpaper.darkText", "wallpaper.color", "clock.face", "clock.size", "clock.height", "clock.x", "clock.y",
        "clock.dateBelow", "clock.date", "clock.dual", "clock.color", "clock.translucent", "clock.digits", "clock.blur",
        "clock.stacked", "clock.weight", "clock.dateStyle",
        "surface.radius", "surface.blur", "surface.opacity", "depth",
        "depth.subject", "depth.edge", "depth.detail", "depth.date", "depth.readable", "depth.spatial", "depth.spatialStrength", "depth.quality", "depth.picks")
}

/**
 * Axion's subject outline encoding (version 0x01): extract width and height, then contours of
 * points normalised to 0..10000 over the whole photo. Decoded to a path in 0..1 units.
 */
object SubjectOutline {
    fun decode(encoded: String?): androidx.compose.ui.graphics.Path? {
        if (encoded.isNullOrEmpty()) return null
        return runCatching {
            val buf = java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(encoded)).order(java.nio.ByteOrder.BIG_ENDIAN)
            if (buf.remaining() < 7 || buf.get().toInt() and 0xFF != 0x01) return null
            buf.short; buf.short
            val contours = buf.short.toInt() and 0xFFFF
            val path = androidx.compose.ui.graphics.Path()
            repeat(contours) {
                if (buf.remaining() < 2) return@repeat
                val points = buf.short.toInt() and 0xFFFF
                if (points < 3 || buf.remaining() < points * 4) return@repeat
                for (p in 0 until points) {
                    val x = (buf.short.toInt() and 0xFFFF) / 10000f
                    val y = (buf.short.toInt() and 0xFFFF) / 10000f
                    if (p == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            path.takeIf { !it.isEmpty }
        }.getOrNull()
    }
}
