package dev.rime.ui.settings

/** Pure compatibility mapping. The ROM adapter supplies Secure settings; preview never accesses them. */
object AxionSettingsCodec {
    fun decode(values: Map<String, String>, defaults: LockscreenSettings = LockscreenSettings()): LockscreenSettings {
        fun number(key: String, fallback: Float) = values[key]?.toFloatOrNull() ?: fallback
        fun flag(key: String, fallback: Boolean) = values[key]?.let { it == "1" } ?: fallback
        val c = defaults.clock
        return defaults.copy(clock = c.copy(
            sizeScale = number("ax_clock_size_scale", if (values["ax_clock_size"] == "large") 1.35f else c.sizeScale),
            horizontalOffsetDp = number("ax_clock_horizontal_offset_dp", c.horizontalOffsetDp),
            topPaddingDp = number("ax_clock_top_padding_dp", c.topPaddingDp),
            colorArgb = values["ax_clock_color"]?.let { raw ->
                if (raw == "auto") null else raw.removePrefix("#").let { hex ->
                    hex.toLongOrNull(16)?.let { color -> if (hex.length == 6) (color or 0xFF000000L).toInt() else if (hex.length == 8) color.toInt() else null }
                }
            } ?: if (values["ax_clock_color"] == "auto") null else c.colorArgb,
            dateBelow = values["ax_clock_compose_date_position"]?.let { it == "below" } ?: c.dateBelow,
            showDate = values["ax_clock_info_display_mode"]?.let { it != "off" } ?: c.showDate,
            dualTone = flag("ax_clock_oplus_big_dual_tone", c.dualTone),
            translucentDigits = flag("ax_clock_background_blur_enabled", c.translucentDigits),
            digitOpacity = number("ax_clock_background_transparency", c.digitOpacity * 100f) / 100f,
            face = ClockFace.parse(values[FACE_KEY]) ?: c.face,
            stacked = flag(STACKED_KEY, c.stacked),
            fontWeight = values[WEIGHT_KEY]?.let { it.toIntOrNull() } ?: if (WEIGHT_KEY in values) null else c.fontWeight,
            date = DateStyle.decode(values[DATE_KEY]) ?: c.date,
        ), surfaces = defaults.surfaces.copy(blurRadiusDp = number("ax_clock_background_blur_radius", defaults.surfaces.blurRadiusDp / 48f * 100f) / 100f * 48f)).validated()
    }
    /** Only these supported keys are written; adapters must preserve unrelated Axion settings. */
    fun encode(settings: LockscreenSettings): Map<String, String> {
        val s = settings.validated(); val c = s.clock
        return mapOf(
            "ax_clock_color" to (c.colorArgb?.let { "#%08X".format(it) } ?: "auto"),
            "ax_clock_size_scale" to c.sizeScale.toString(),
            "ax_clock_horizontal_offset_dp" to c.horizontalOffsetDp.toString(),
            "ax_clock_top_padding_dp" to c.topPaddingDp.toString(),
            "ax_clock_compose_date_position" to if (c.dateBelow) "below" else "above",
            "ax_clock_oplus_big_dual_tone" to if (c.dualTone) "1" else "0",
            "ax_clock_background_blur_enabled" to if (c.translucentDigits) "1" else "0",
            "ax_clock_background_transparency" to (c.digitOpacity * 100f).toInt().toString(),
            "ax_clock_background_blur_radius" to (s.surfaces.blurRadiusDp / 48f * 100f).toInt().coerceIn(0, 100).toString(),
            FACE_KEY to c.face.name, STACKED_KEY to if (c.stacked) "1" else "0",
            WEIGHT_KEY to (c.fontWeight?.toString() ?: "auto"), DATE_KEY to c.date.encode(),
        )
    }

    /** Rime-only keys: both hosts read them back with [decode]. */
    const val FACE_KEY = "rime_clock_face"
    const val STACKED_KEY = "rime_clock_stacked"
    const val WEIGHT_KEY = "rime_clock_weight"
    const val DATE_KEY = "rime_date_style"
    val RIME_KEYS = listOf(FACE_KEY, STACKED_KEY, WEIGHT_KEY, DATE_KEY)
}
