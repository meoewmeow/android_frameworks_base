package dev.rime.ui

import org.junit.Assert.*
import org.junit.Test
import dev.rime.ui.customization.WallpaperCopy
import dev.rime.ui.lockscreen.WallpaperGeometry
import dev.rime.ui.models.LockscreenPreset
import dev.rime.ui.models.NormalizedRect
import dev.rime.ui.models.PresetCollection
import dev.rime.ui.models.PresetWallpaper
import dev.rime.ui.settings.ClockAppearance
import dev.rime.ui.settings.ClockFace
import dev.rime.ui.settings.PresetCodec
import dev.rime.ui.settings.StoredPresets
import dev.rime.ui.settings.SurfaceAppearance

class PresetTest {
    private fun preset(id: String) = LockscreenPreset(id, "Name $id",
        PresetWallpaper("asset_$id", 1080, 2400, NormalizedRect(0.1f, 0.2f, 0.9f, 0.8f), true, 0xFF102030.toInt()),
        ClockAppearance(sizeScale = 0.9f, heightScale = 1.2f, face = ClockFace.SystemSans, horizontalOffsetDp = 12f,
            topPaddingDp = -40f, dateBelow = true, showDate = false, dualTone = false, colorArgb = 0xFF445566.toInt(),
            translucentDigits = false, digitOpacity = 0.5f, blurRadiusDp = 40f),
        SurfaceAppearance(cornerRadiusDp = 20f, blurRadiusDp = 12f, opacity = 0.6f), depthEnabled = true)

    @Test fun collectionRoundTripsThroughTheVersionedCodec() {
        val collection = PresetCollection(listOf(preset("a"), preset("b").copy(clock = ClockAppearance())), "b")
        assertEquals(collection, PresetCodec.decode(PresetCodec.encode(collection)))
        assertEquals(PresetCodec.VERSION, PresetCodec.encode(collection).version)
    }
    @Test fun depthOptionsRoundTripAndReachTheEffectsService() {
        val depth = dev.rime.ui.models.DepthOptions(subject = dev.rime.ui.models.DepthOptions.Subject.All,
            edge = dev.rime.ui.models.DepthOptions.Edge.Loose, detail = dev.rime.ui.models.DepthOptions.Detail.Fine,
            includeDate = false, keepClockReadable = false, spatial = true, spatialStrength = 1.5f)
        val preset = preset("d").copy(depth = depth)
        assertEquals(preset, PresetCodec.decode(PresetCodec.encode(preset)))
        assertEquals("subject=all;edge=loose;detail=fine", depth.outlineSpec)
        // A stored strength outside the range is pulled back in, not trusted.
        assertEquals(2f, PresetCodec.decode(PresetCodec.encode(preset) + ("depth.spatialStrength" to "9"))!!.depth.spatialStrength, 0f)
    }
    @Test fun fieldsFromANewerEditorSurviveARoundTrip() {
        val stored = PresetCodec.encode(preset("a")) + mapOf("widgets" to "weather,calendar", "focus" to "work")
        val decoded = PresetCodec.decode(stored)!!
        assertEquals(mapOf("widgets" to "weather,calendar", "focus" to "work"), decoded.extras)
        assertEquals(stored, PresetCodec.encode(decoded))
    }
    @Test fun damagedEntriesFallBackWithoutLosingTheRest() {
        val good = PresetCodec.encode(preset("good"))
        val stored = StoredPresets(1, "missing", listOf(good, mapOf("name" to "no id"), mapOf("id" to "../escape"),
            mapOf("id" to "odd", "clock.size" to "NaN", "wallpaper.crop" to "0.5,0.5,0.5,0.5", "wallpaper.asset" to "a/b")))
        val decoded = PresetCodec.decode(stored)
        assertEquals(listOf("good", "odd"), decoded.presets.map { it.id })
        assertNull(decoded.activeId)
        val odd = decoded.presets[1]
        assertEquals(1f, odd.clock.sizeScale, 0f)
        assertEquals(NormalizedRect.Full, odd.wallpaper.crop)
        assertNull(odd.wallpaper.assetId)
        assertEquals(PresetCollection(), PresetCodec.decode(StoredPresets(0, null, listOf(good))))
    }
    @Test fun deletingTheActivePresetHandsOverToItsNeighbour() {
        val collection = PresetCollection(listOf(preset("a"), preset("b"), preset("c")), "b")
        assertEquals("a", collection.without("b").activeId)
        assertEquals("b", collection.without("c").activeId)
        assertEquals("b", PresetCollection(listOf(preset("a"), preset("b")), "a").without("a").activeId)
        val empty = PresetCollection(listOf(preset("a")), "a").without("a")
        assertTrue(empty.presets.isEmpty())
        assertNull(empty.activeId)
        assertEquals(collection, collection.without("unknown"))
    }
    @Test fun addingReplacesAPresetWithTheSameIdInPlace() {
        val collection = PresetCollection(listOf(preset("a"), preset("b")), "a")
        val renamed = collection.with(preset("a").copy(name = "Renamed"))
        assertEquals(listOf("a", "b"), renamed.presets.map { it.id })
        assertEquals("Renamed", renamed.presets[0].name)
        assertEquals(listOf("a", "b", "c"), collection.with(preset("c")).presets.map { it.id })
        assertEquals(1, PresetCollection(listOf(preset("a"), preset("a")), "a").validated().presets.size)
    }
    @Test fun wallpaperSourceKeepsTheFrameAspectInsideTheCrop() {
        // A wide photo in a tall frame: full height, a centred slice of the width.
        val tall = WallpaperGeometry.sourceRect(4000, 2000, NormalizedRect.Full, 0.5f)
        assertEquals(2000f, tall.height, 0.01f)
        assertEquals(1000f, tall.width, 0.01f)
        assertEquals(2000f, tall.center.x, 0.01f)
        // The crop moves the centre and bounds the slice.
        val cropped = WallpaperGeometry.sourceRect(4000, 2000, NormalizedRect(0.5f, 0f, 1f, 1f), 0.5f)
        assertEquals(3000f, cropped.center.x, 0.01f)
        assertEquals(1000f, cropped.width, 0.01f)
        // The softened copy is smaller but maps to the same fractions of the image.
        val copy = WallpaperGeometry.sourceRect(256, 128, NormalizedRect(0.5f, 0f, 1f, 1f), 0.5f)
        assertEquals(cropped.left / 4000f, copy.left / 256f, 0.0001f)
        assertEquals(cropped.width / 4000f, copy.width / 256f, 0.0001f)
        assertTrue(WallpaperGeometry.sourceRect(0, 0, NormalizedRect.Full, 0.5f).isEmpty)
    }
    @Test fun repositioningAPhotoKeepsTheFrameAspectAndStaysInsideTheImage() {
        val aspect = 0.5f
        // Dragging right moves the image right, so the shown part moves left and stops at the edge.
        val dragged = WallpaperGeometry.adjustCrop(4000, 2000, NormalizedRect.Full, aspect, panX = 0.25f, panY = 0f, zoom = 1f)
        val shown = WallpaperGeometry.sourceRect(4000, 2000, dragged, aspect)
        assertEquals(1750f, shown.center.x, 0.5f)
        assertEquals(aspect, shown.width / shown.height, 0.001f)
        val pinned = WallpaperGeometry.adjustCrop(4000, 2000, NormalizedRect.Full, aspect, panX = 50f, panY = 0f, zoom = 1f)
        assertEquals(0f, WallpaperGeometry.sourceRect(4000, 2000, pinned, aspect).left, 0.5f)
        // A pinch magnifies up to the limit and never zooms out past covering the frame.
        val close = WallpaperGeometry.adjustCrop(4000, 2000, NormalizedRect.Full, aspect, 0f, 0f, zoom = 100f)
        assertEquals(1000f / WallpaperGeometry.MAX_ZOOM, WallpaperGeometry.sourceRect(4000, 2000, close, aspect).width, 0.5f)
        val wide = WallpaperGeometry.adjustCrop(4000, 2000, close, aspect, 0f, 0f, zoom = 0.001f)
        assertEquals(1000f, WallpaperGeometry.sourceRect(4000, 2000, wide, aspect).width, 0.5f)
        // No image size, frame shape or gesture may throw: rounding once left an inverted range here.
        for (width in 700..1500) for (height in listOf(900, 1359, 2400)) for (frame in listOf(0.45f, width / height.toFloat(), 1.7f)) {
            var crop = NormalizedRect.Full
            for (step in 0 until 6) crop = WallpaperGeometry.adjustCrop(width, height, crop, frame, 0.3f - step * 0.1f, step * 0.2f - 0.5f, 0.7f + step * 0.2f)
            val result = WallpaperGeometry.sourceRect(width, height, crop, frame)
            assertTrue(result.left >= -0.5f && result.top >= -0.5f && result.right <= width + 0.5f && result.bottom <= height + 0.5f)
        }
        // Damaged input changes nothing.
        assertEquals(dragged, WallpaperGeometry.adjustCrop(4000, 2000, dragged, aspect, Float.NaN, 0f, 1f))
    }
    @Test fun softenedCopyKeepsBrightnessAtTheEdgesAndSpreadsDetail() {
        val flat = IntArray(40 * 30) { 0xFF336699.toInt() }
        assertArrayEquals(flat, WallpaperCopy.soften(flat, 40, 30))
        val spot = IntArray(41 * 41) { 0xFF000000.toInt() }.also { it[20 * 41 + 20] = 0xFFFFFFFF.toInt() }
        val soft = WallpaperCopy.soften(spot, 41, 41, radius = 3)
        assertTrue((soft[20 * 41 + 20] and 0xFF) < 40)
        assertTrue((soft[20 * 41 + 23] and 0xFF) > 0)
        assertEquals(0xFF, soft[0] ushr 24)
    }
}
