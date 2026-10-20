package dev.rime.ui

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.unit.dp
import dev.rime.ui.backdrop.BackdropStyle
import dev.rime.ui.backdrop.requestsBlur
import dev.rime.ui.customization.CalibrationFixtureData
import dev.rime.ui.notifications.NotificationDeckGeometry as Deck
import dev.rime.ui.settings.Calibration
import dev.rime.ui.settings.CalibrationCodec

class CalibrationTest {
    @Test fun savedValuesRoundTripThroughTheVersionedCodec() {
        val tuned = Calibration(stackLipDp = 21f, rearCardScaleStep = 0.03f, stackOpenStiffness = 90f, shadowOpacity = 0.4f, shadowBlurDp = 24f, shadowOffsetDp = 9f,
            galleryScale = 0.75f, galleryPageGapDp = 20f, galleryCornerRadiusDp = 28f)
        assertEquals(tuned, CalibrationCodec.decode(CalibrationCodec.encode(tuned)))
    }
    @Test fun damagedMissingAndForeignDataFallBackToDefaults() {
        assertEquals(Calibration(), CalibrationCodec.decode(emptyMap()))
        assertEquals(Calibration(), CalibrationCodec.decode(mapOf("version" to 99f, "shadowOpacity" to 0.5f)))
        val partial = CalibrationCodec.decode(mapOf("version" to 2f, "shadowOpacity" to Float.NaN, "shadowBlurDp" to 9000f))
        assertEquals(Calibration().shadowOpacity, partial.shadowOpacity, 0f)
        assertEquals(40f, partial.shadowBlurDp, 0f)
        assertEquals(Calibration().stackLipDp, partial.stackLipDp, 0f)
    }
    @Test fun exportedTextImportsBackAndForeignTextIsRefused() {
        val tuned = Calibration(stackLipDp = 21f, shadowOpacity = 0.4f, galleryScale = 0.75f)
        assertEquals(tuned, CalibrationCodec.fromJson(CalibrationCodec.toJson(tuned)))
        assertNull(CalibrationCodec.fromJson("not json"))
        assertNull(CalibrationCodec.fromJson("{}"))
        assertNull(CalibrationCodec.fromJson("{\"version\": 3, \"shadowOpacity\": 0.4}"))
        // Version 1 kept an 8 dp gap where version 2 has a lip; its other values carry over.
        val older = CalibrationCodec.fromJson("{\"version\": 1, \"stackPeekGapDp\": 8, \"shadowOpacity\": 0.4}")!!
        assertEquals(Calibration().stackLipDp, older.stackLipDp, 0f)
        assertEquals(0.4f, older.shadowOpacity, 0f)
        // Readable data with a damaged value keeps the rest and defaults that one.
        val partial = CalibrationCodec.fromJson("{\"version\": 2, \"shadowOpacity\": \"dark\", \"stackLipDp\": 12}")!!
        assertEquals(12f, partial.stackLipDp, 0f)
        assertEquals(Calibration().shadowOpacity, partial.shadowOpacity, 0f)
    }
    @Test fun fixtureNotificationsAreStableAndCountedExactly() {
        for (count in listOf(0, 1, 5, 20)) {
            val items = CalibrationFixtureData.notifications(count, longText = false, grouped = true)
            assertEquals(count, items.size)
            assertEquals(count, items.map { it.key }.toSet().size)
        }
        assertEquals(CalibrationFixtureData.notifications(5, true, false), CalibrationFixtureData.notifications(5, true, false))
        assertTrue(CalibrationFixtureData.notifications(3, false, false).all { it.groupKey == null })
    }
    @Test fun validationNeverAllowsAnInvertedOrNegativeStack() {
        val value = Calibration(stackLipDp = -4f, rearCardScaleStep = 3f, shadowOpacity = Float.NEGATIVE_INFINITY).validated()
        assertEquals(8f, value.stackLipDp, 0f)
        assertEquals(0.12f, value.rearCardScaleStep, 0f)
        assertEquals(Calibration().shadowOpacity, value.shadowOpacity, 0f)
    }
    @Test fun rearCardScaleFollowsTheCalibratedStepAndReachesFullSizeWhenExpanded() {
        val heights = List(3) { 100f }
        assertEquals(0.94f, Deck.peel(heights, 800f, 0f, 30f, 8f, 0.03f)[2].scale, 0.0001f)
        assertEquals(1f, Deck.peel(heights, 800f, Deck.restTop(heights, 800f, 30f), 30f, 8f, 0.03f)[2].scale, 0f)
    }
    @Test fun hiddenDeckCardsHoldNoBlurRequest() {
        val surface = BackdropStyle(blurRadius = 30.dp)
        Deck.peel(List(20) { 100f }, 800f, 0f, 30f, 8f).forEachIndexed { depth, pose ->
            assertEquals(depth < Deck.VISIBLE_CARDS, surface.copy(alpha = pose.alpha, scale = pose.scale).requestsBlur)
        }
        assertFalse(surface.copy(blurRadius = 0.dp).requestsBlur)
    }
}
