package dev.rime.ui

import org.junit.Assert.*
import org.junit.Test
import dev.rime.ui.lockscreen.*
import dev.rime.ui.notifications.*
import dev.rime.ui.settings.*
import dev.rime.ui.controlcenter.*
import dev.rime.ui.models.UiNotification

class SceneInteractionTest {
    @Test fun slowDragHasOneSynchronousValueAndCanReverseWithoutJumping() {
        val state = LockscreenSceneState()
        state.beginDrag(200f)
        repeat(10) { i -> state.dragBy(-5f); assertEquals((i + 1) * 0.025f, state.expansion, 0.0001f) }
        state.dragBy(10f)
        assertEquals(0.2f, state.expansion, 0.0001f)
    }
    @Test fun contentHeightChangesDoNotRenormalizeAnActiveDrag() {
        val state = LockscreenSceneState()
        state.beginDrag(200f); state.dragBy(-20f)
        state.recordHeight("incoming", 600f)
        state.dragBy(-20f)
        assertEquals(0.2f, state.expansion, 0.0001f)
    }
    @Test fun scrollingAndExpansionTransferRemainingDeltaWithoutThresholdJump() {
        val state = LockscreenSceneState(0.9f)
        state.maxScrollPx = 500f
        state.beginDrag(200f); state.dragBy(-40f)
        assertEquals(1f, state.expansion, 0.0001f)
        assertEquals(20f, state.scrollOffsetPx, 0.0001f)
        state.dragBy(30f)
        assertEquals(0f, state.scrollOffsetPx, 0.0001f)
        assertEquals(0.95f, state.expansion, 0.0001f)
    }
    @Test fun clockAndNotificationsStaySeparatedAtEveryExpansionFraction() {
        for (height in listOf(640f, 800f, 1000f)) for (step in 0..20) {
            val e = step / 20f
            val frame = LockscreenLayoutGeometry.frame(height, e, 120f, 16f, 0f, 1f)
            val deckTop = frame.collapsedDeckTop + (frame.expandedDeckTop - frame.collapsedDeckTop) * e
            assertTrue(frame.clockTop + frame.clockHeight + 26f + 19f <= deckTop + 0.01f)
        }
    }
    @Test fun theDeckEndsAboveTheFingerprintSensor() {
        // Spacewar: sensor top 2076 px at 2.625 px/dp on a 914 dp tall screen.
        val frame = LockscreenLayoutGeometry.frame(914f, 0f, 100f, 30f, 0f, 1f, fingerprintTop = 2076f / 2.625f)
        assertEquals(2076f / 2.625f - 24f, frame.deckBottom, 0.001f)
        // A sensor below the usual bottom margin changes nothing.
        assertEquals(914f - 118f, LockscreenLayoutGeometry.frame(914f, 0f, 100f, 30f, 0f, 1f, fingerprintTop = 900f).deckBottom, 0f)
    }
    @Test fun aShortListOpensOnlyAsFarAsItNeedsAndLeavesTheClockAlone() {
        val closed = LockscreenLayoutGeometry.frame(900f, 0f, 100f, 30f, 0f, 1f, listHeight = 208f)
        val open = LockscreenLayoutGeometry.frame(900f, 1f, 100f, 30f, 0f, 1f, listHeight = 208f)
        // Two cards fit under the clock: the list top stops there and the clock is untouched.
        assertEquals(open.deckBottom - 208f, open.expandedDeckTop, 0.001f)
        assertEquals(closed.clockHeight, open.clockHeight, 0f)
        assertEquals(closed.clockTop, open.clockTop, 0f)
        // A third card needs a little of the clock's room: the clock gives up only that much.
        val three = LockscreenLayoutGeometry.frame(900f, 1f, 100f, 60f, 0f, 1f, listHeight = 316f)
        assertEquals(three.deckBottom - 316f, three.expandedDeckTop, 0.001f)
        assertTrue(three.clockHeight < closed.clockHeight)
        assertTrue(three.clockHeight > LockscreenLayoutGeometry.frame(900f, 1f, 100f, 60f, 0f, 1f).clockHeight)
        // A list taller than the screen still goes all the way up and contracts the clock.
        val tall = LockscreenLayoutGeometry.frame(900f, 1f, 100f, 60f, 0f, 1f, listHeight = 3000f)
        assertEquals(LockscreenLayoutGeometry.frame(900f, 1f, 100f, 60f, 0f, 1f).expandedDeckTop, tall.expandedDeckTop, 0f)
        assertTrue(tall.clockHeight < closed.clockHeight)
        // One card has nowhere to go.
        val single = LockscreenLayoutGeometry.frame(900f, 1f, 100f, 0f, 0f, 1f, listHeight = 100f)
        assertEquals(single.collapsedDeckTop, single.expandedDeckTop, 0.001f)
    }
    @Test fun groupKeysNeverMergeTwoAppsAndPreserveHostOrder() {
        fun n(key: String, pkg: String) = UiNotification(key, pkg, pkg, title = key, text = "", timestamp = "now", groupKey = "same")
        val groups = notificationGroups(listOf(n("one", "a"), n("two", "b"), n("three", "a")))
        assertEquals(listOf("one", "three"), groups[0].items.map { it.key })
        assertEquals(listOf("two"), groups[1].items.map { it.key })
        val folded = stackEntries(groups, emptySet())
        assertEquals(2, folded.size)
        assertEquals(listOf("one", "three"), folded[0].groupKeys)
        val opened = stackEntries(groups, setOf(groups[0].id))
        assertEquals(3, opened.size)
        assertEquals(folded[0].key, opened[0].key)
        assertTrue(opened[0].groupHeader)
    }
    @Test fun actualAxionPositionModelClampsCancelsAndSnaps() {
        val geometry = ClockEditScaleGeometry(ClockSizeScaleRange(0.5f, 1f, 1.35f, 1.35f), 1f, 200f)
        val original = ClockEditModel.from(1f, 0f, 0f, geometry, ClockHorizontalOffsetRange(-10f, 10f), ClockTopPaddingRange(-24f, 240f))
        val moved = original.beginPositionDrag().movePositionBy(24f, 10f, 2f, 0.5f)
        assertEquals(10f, moved.horizontalOffsetDp, 0f)
        assertEquals(0f, moved.snapHorizontalPosition().horizontalOffsetDp, 0f)
        assertEquals(0f, moved.cancelDrag().horizontalOffsetDp, 0f)
    }
    @Test fun actualAxionResizeRuleKeepsTopEdgeFixedForHeightOnlyHandle() {
        val geometry = ClockEditScaleGeometry(ClockSizeScaleRange(0.5f, 1f, 1.25f, 1.5f), 1f, 200f,
            resizeDpPerScale = 0f, frameHeightDp = 100f, resizeHeightDpPerScale = 100f)
        val model = ClockEditModel.from(1f, 0f, 0f, geometry, ClockHorizontalOffsetRange.Zero, ClockTopPaddingRange(-24f, 240f))
            .beginSizeDrag().resizeBy(0f, 20f, 1f, 1f, ClockResizeCorner.BottomRight)
        assertEquals(1.2f, model.sizeScaleOverride, 0.0001f)
        assertEquals(10f, model.topPaddingDp, 0.0001f)
        assertEquals(-50f, model.topPaddingDp - model.scaleGeometry.frameHeightDp!! / 2f, 0.0001f)
    }
    @Test fun editableControlGridPacksWithoutOverlapAfterResizeAndReorder() {
        val resized = ControlCenterLayout.defaults.map { if (it.id == "media") ControlCenterLayout.resized(it) else it }
        val moved = ControlCenterLayout.move(resized, "camera", "rotation")
        val occupied = mutableSetOf<Pair<Int, Int>>()
        for (placement in ControlCenterLayout.pack(moved)) {
            assertTrue(placement.column + placement.item.columns <= 4)
            for (y in placement.row until placement.row + placement.item.rows) for (x in placement.column until placement.column + placement.item.columns) {
                assertTrue(occupied.add(x to y))
            }
        }
    }
}
