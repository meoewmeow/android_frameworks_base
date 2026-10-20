package dev.rime.ui.customization

/**
 * Softens a small copy of a wallpaper once, when a preset is saved. Three box passes approach a
 * Gaussian. Pure arithmetic on ARGB pixels: the host decodes, scales down and stores the result.
 */
object WallpaperCopy {
    /** Long edge of the copy. It is drawn stretched under surfaces, so detail would be wasted. */
    const val LONG_EDGE_PX = 256
    /** Box radius at [LONG_EDGE_PX]; about a tenth of a phone's width once stretched. */
    const val RADIUS_PX = 7

    fun soften(pixels: IntArray, width: Int, height: Int, radius: Int = RADIUS_PX): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        if (radius <= 0) return pixels.copyOf()
        var source = pixels.copyOf(); var target = IntArray(pixels.size)
        repeat(3) {
            boxPass(source, target, width, height, radius, horizontal = true)
            boxPass(target, source, width, height, radius, horizontal = false)
        }
        return source
    }

    private fun boxPass(from: IntArray, to: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val step = if (horizontal) 1 else width
        val window = 2 * radius + 1
        for (line in 0 until lines) {
            val start = if (horizontal) line * width else line
            var a = 0; var r = 0; var g = 0; var b = 0
            // Edge pixels repeat, so borders keep their brightness instead of fading to black.
            for (i in -radius..radius) {
                val p = from[start + i.coerceIn(0, length - 1) * step]
                a += p ushr 24; r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF
            }
            for (i in 0 until length) {
                to[start + i * step] = (a / window shl 24) or (r / window shl 16) or (g / window shl 8) or (b / window)
                val leaving = from[start + (i - radius).coerceIn(0, length - 1) * step]
                val entering = from[start + (i + radius + 1).coerceIn(0, length - 1) * step]
                a += (entering ushr 24) - (leaving ushr 24)
                r += (entering shr 16 and 0xFF) - (leaving shr 16 and 0xFF)
                g += (entering shr 8 and 0xFF) - (leaving shr 8 and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }
}
