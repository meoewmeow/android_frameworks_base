package dev.rime.ui

import org.junit.Assert.*
import org.junit.Test
import dev.rime.ui.notifications.NotificationDeckGeometry as Deck
import dev.rime.ui.settings.*

class PresentationRulesTest {
    private val viewport = 800f; private val peek = 30f; private val gap = 8f
    private fun peel(heights: List<Float>, lift: Float) = Deck.peel(heights, viewport, lift, peek, gap)

    @Test fun closedDeckIsTheFrontCardAndTwoLips() {
        val poses = peel(List(20) { 100f }, 0f)
        assertEquals(viewport - 100f - 2 * peek, poses[0].top, 0f)
        // Each lip shows exactly `peek` below the card in front, and stays inside the viewport.
        assertEquals(peek, poses[1].bottom - poses[0].bottom, 0.001f)
        assertEquals(peek, poses[2].bottom - poses[1].bottom, 0.001f)
        assertEquals(viewport, poses[2].bottom, 0.001f)
        poses.forEachIndexed { depth, pose ->
            assertEquals(if (depth == 0) 1f else 0f, pose.reveal, 0f)
            assertEquals(if (depth < 3) 1f else 0f, pose.alpha, 0f)
            assertTrue(pose.bottom <= viewport + 0.001f)
        }
    }
    @Test fun frontCardFollowsTheFingerWhileTheCardBehindStaysUntilItIsCleared() {
        val heights = listOf(100f, 120f, 90f, 90f)
        val rest = peel(heights, 0f)
        val partly = peel(heights, 40f)
        assertEquals(rest[0].top - 40f, partly[0].top, 0f)
        // Still being uncovered: its bottom has not moved, and neither has anything behind it.
        assertEquals(rest[1].bottom, partly[1].bottom, 0.001f)
        assertTrue(partly[1].reveal > 0f && partly[1].reveal < 1f)
        assertEquals(rest[2], partly[2])
        // Past the point where the front card clears it, the second card follows at the list gap...
        val opening = Deck.autoOpenLift(heights, viewport, peek, gap)
        assertEquals(120f + gap - peek, opening, 0.001f)
        val after = peel(heights, opening + 25f)
        assertEquals(1f, after[1].reveal, 0f)
        assertEquals(after[0].bottom + gap, after[1].top, 0.001f)
        assertEquals(rest[1].bottom - 25f, after[1].bottom, 0.001f)
        // ...and only now does the third one start to be uncovered, still without moving.
        assertTrue(after[2].reveal > 0f)
        assertEquals(rest[2].bottom, after[2].bottom, 0.001f)
    }
    @Test fun fullyLiftedDeckIsAListAndLiftingFurtherScrollsIt() {
        val heights = listOf(100f, 120f, 90f, 90f)
        val open = peel(heights, Deck.restTop(heights, viewport, peek))
        var expected = 0f
        open.forEachIndexed { index, pose ->
            assertEquals(expected, pose.top, 0.001f)
            assertEquals(heights[index], pose.height, 0.001f)
            assertEquals(1f, pose.scale, 0f); assertEquals(1f, pose.alpha, 0f)
            expected += heights[index] + gap
        }
        val scrolled = peel(heights, Deck.restTop(heights, viewport, peek) + 50f)
        assertEquals(-50f, scrolled[0].top, 0.001f)
        assertEquals(open[3].top - 50f, scrolled[3].top, 0.001f)
    }
    @Test fun aTallerCardBehindNeverShowsAboveTheFrontCardWhileStacked() {
        val poses = peel(listOf(80f, 400f, 300f), 0f)
        assertEquals(80f, poses[1].height, 0f)
        assertTrue(poses[1].top >= poses[0].top)
        assertTrue(poses[2].top >= poses[0].top)
    }
    @Test fun aSingleCardOpensFromHalfway() {
        assertEquals((viewport - 100f) / 2f, Deck.autoOpenLift(listOf(100f), viewport, peek, gap), 0.001f)
        assertEquals(1, peel(listOf(100f), 30f).size)
        assertTrue(peel(emptyList(), 0f).isEmpty())
    }
    @Test fun releaseOpensPastTheRevealPointAndClosesAfterADownwardDrag() {
        assertEquals(1f, Deck.settleTarget(0.2f, 0f, 1000f, openAt = 0.15f), 0f)
        assertEquals(0f, Deck.settleTarget(0.1f, 0f, 1000f, openAt = 0.15f), 0f)
        assertEquals(0f, Deck.settleTarget(0.7f, 0f, 1000f, openAt = 0.15f, movedDown = true), 0f)
        assertEquals(1f, Deck.settleTarget(0.7f, -2000f, 1000f, openAt = 0.15f, movedDown = true), 0f)
    }
    @Test fun horizontalDismissDoesNotClaimDeckExpansion() {
        assertFalse(Deck.dominantVertical(100f, 12f, 8f))
        assertTrue(Deck.dominantVertical(12f, -100f, 8f))
    }
    @Test fun flingsAndZeroTravelHaveSafeSettleRules() {
        assertEquals(1f, Deck.settleTarget(0.1f, -2000f), 0f)
        assertEquals(0f, Deck.settleTarget(0.9f, 2000f), 0f)
        assertEquals(0.3f, Deck.expansionForDrag(0.3f, 10f, 500f, 0f), 0f)
    }
    @Test fun shortGestureCountsAsFlickEvenWhenTheTrackerSawTooFewSamples() {
        assertEquals(-1500f, Deck.releaseVelocity(0f, -150f, 100L), 0.01f)
        assertEquals(-4000f, Deck.releaseVelocity(-4000f, -150f, 100L), 0f)
        // A long drag that stopped before release keeps the tracked stop.
        assertEquals(0f, Deck.releaseVelocity(0f, -400f, 900L), 0f)
        assertEquals(0f, Deck.releaseVelocity(0f, -400f, 0L), 0f)
    }
    @Test fun aScrolledListFadesAtTheTopOfTheSceneNotAtTheTopOfItsViewport() {
        val above = 200f
        // Scrolled 120 px past the viewport top: still fully shown, in the room the clock left.
        assertEquals(1f, Deck.edgeFade(-120f + above, 0f + above, 600f + above, 48f), 0f)
        // Leaving the scene itself is what fades it.
        assertEquals(0.5f, Deck.edgeFade(-224f + above, -104f + above, 600f + above, 48f), 0.0001f)
    }
    @Test fun cardsFadeOnlyWhileLeavingTheDeckViewport() {
        assertEquals(1f, Deck.edgeFade(0f, 120f, 600f, 48f), 0f)
        assertEquals(1f, Deck.edgeFade(480f, 600f, 600f, 48f), 0f)
        assertEquals(0.5f, Deck.edgeFade(-24f, 96f, 600f, 48f), 0.0001f)
        assertEquals(0.5f, Deck.edgeFade(504f, 624f, 600f, 48f), 0.0001f)
        assertEquals(0f, Deck.edgeFade(-200f, -80f, 600f, 48f), 0f)
        assertEquals(0f, Deck.edgeFade(700f, 820f, 600f, 48f), 0f)
    }
    @Test fun clockColorAndPositionRoundTripThroughLegacyKeys() {
        val settings = LockscreenSettings(clock = ClockAppearance(sizeScale = 0.8f, horizontalOffsetDp = 12f, topPaddingDp = 20f, dateBelow = true, colorArgb = 0xFF112233.toInt()))
        val restored = AxionSettingsCodec.decode(AxionSettingsCodec.encode(settings))
        assertEquals(settings.clock, restored.clock)
        val auto = AxionSettingsCodec.decode(mapOf("ax_clock_color" to "auto"))
        assertNull(auto.clock.colorArgb)
    }
    @Test fun invalidAndLegacySettingsStayWithinAxionLimits() {
        val value = LockscreenSettings(clock = ClockAppearance(sizeScale = Float.NaN, topPaddingDp = 900f),
            surfaces = SurfaceAppearance(opacity = Float.POSITIVE_INFINITY)).validated()
        assertEquals(1f, value.clock.sizeScale, 0f)
        assertEquals(240f, value.clock.topPaddingDp, 0f)
        assertEquals(0.72f, value.surfaces.opacity, 0f)
        val decoded = AxionSettingsCodec.decode(mapOf("ax_clock_size_scale" to "9", "ax_clock_background_blur_radius" to "50"))
        assertEquals(1.35f, decoded.clock.sizeScale, 0f)
        assertEquals(24f, decoded.surfaces.blurRadiusDp, 0f)
    }
}
