/* Copyright (C) 2026 AxionOS Project; presentation extracted from StackScrollAlgorithm under Apache-2.0. */
package dev.rime.ui.notifications

import kotlin.math.abs

/** Pure geometry shared by the preview and a future SystemUI row host. Never ranks notifications. */
object NotificationDeckGeometry {
    /** The front card and the lips of the two behind it. */
    const val VISIBLE_CARDS = 3
    const val SCALE_STEP = 0.06f

    /**
     * One card for one frame, in the deck viewport's pixels.
     * [reveal] is 0 while the card is only a lip behind its neighbour and 1 once it is fully uncovered.
     * [scale] is applied around the bottom edge, which is what stays put while a card is revealed.
     */
    data class Pose(val top: Float, val height: Float, val scale: Float, val reveal: Float, val alpha: Float) {
        val bottom get() = top + height
    }

    /** Where the front card rests while the deck is closed. Also the drag distance that opens it fully. */
    fun restTop(heights: List<Float>, viewport: Float, peek: Float): Float =
        if (heights.isEmpty()) 0f else viewport - heights[0] - peek * (minOf(heights.size, VISIBLE_CARDS) - 1)

    /**
     * The deck peeled open by [lift] pixels. The front card follows the finger. Each card behind
     * stays where it is, uncovered from the top down, until the card in front has cleared it; only
     * then does it follow, keeping [gap]. Lifted all the way this is exactly a list, and lifting
     * further scrolls that list, so one rule covers closed, opening, open and scrolled.
     *
     * [spacing] carries a structural reflow that is still animating: element k is how far card k
     * currently sits from card k-1's top in list order. Null means heights plus [gap].
     */
    fun peel(heights: List<Float>, viewport: Float, lift: Float, peek: Float, gap: Float,
        scaleStep: Float = SCALE_STEP, spacing: List<Float>? = null): List<Pose> {
        if (heights.isEmpty()) return emptyList()
        val visible = minOf(heights.size, VISIBLE_CARDS)
        val front = heights[0]
        val poses = ArrayList<Pose>(heights.size)
        poses += Pose(restTop(heights, viewport, peek) - lift.coerceAtLeast(0f), front, 1f, 1f, 1f)
        for (k in 1 until heights.size) {
            val ahead = poses[k - 1]
            val lipIndex = minOf(k, visible - 1)
            // Cards past the last lip wait under it and have no lip of their own.
            val restBottom = viewport - peek * (visible - 1 - lipIndex)
            val lip = if (k < visible) peek else 0f
            val natural = heights[k]
            // In the stack a card is never taller than the front card that hides it.
            val stacked = minOf(natural, front)
            val reveal = if (stacked - lip <= 0f) 1f else ((restBottom - lip - ahead.bottom) / (stacked - lip)).coerceIn(0f, 1f)
            val height = stacked + (natural - stacked) * reveal
            val reflow = if (spacing == null) 0f else spacing[k] - (heights[k - 1] + gap)
            val top = minOf(restBottom - height, ahead.bottom + gap + reflow)
            val restScale = (1f - lipIndex * scaleStep).coerceAtLeast(0.5f)
            poses += Pose(top, height, restScale + (1f - restScale) * reveal, reveal,
                if (k < visible) 1f else (reveal * 4f).coerceIn(0f, 1f))
        }
        return poses
    }

    /**
     * The lift at which the second card has been revealed and starts to follow. Past this point
     * the deck opens the rest of the way by itself.
     */
    fun autoOpenLift(heights: List<Float>, viewport: Float, peek: Float, gap: Float): Float {
        val travel = restTop(heights, viewport, peek).coerceAtLeast(0f)
        if (heights.size < 2) return travel * 0.5f
        val visible = minOf(heights.size, VISIBLE_CARDS)
        val secondRestBottom = viewport - peek * (visible - 2)
        val lift = restTop(heights, viewport, peek) + heights[0] + gap - (secondRestBottom - heights[1])
        return lift.coerceIn(0f, travel * 0.6f)
    }

    fun expansionForDrag(start: Float, startY: Float, currentY: Float, distance: Float) =
        if (distance <= 0f) start.coerceIn(0f, 1f) else (start + (startY - currentY) / distance).coerceIn(0f, 1f)

    /**
     * Where a released deck settles. A flick decides first. Otherwise a gesture that moved down
     * closes the deck, and one that moved up opens it once it is past [openAt].
     */
    fun settleTarget(expansion: Float, velocityY: Float = 0f, minVelocity: Float = 1000f,
        openAt: Float = 0.5f, movedDown: Boolean = false): Float = when {
        velocityY <= -minVelocity -> 1f
        velocityY >= minVelocity -> 0f
        movedDown -> 0f
        expansion >= openAt -> 1f
        else -> 0f
    }
    fun dominantVertical(dx: Float, dy: Float, slop: Float) = abs(dy) > slop && abs(dy) > abs(dx)
    /** How long a whole gesture may last and still count as a flick by its average speed. */
    const val FLICK_WINDOW_MS = 300L
    /**
     * Velocity to settle with on release. A slow frame can leave the tracker too few samples to
     * report a short flick, so a gesture that ends inside the flick window also counts by its
     * average speed. A longer drag keeps the tracked value, so stopping before release still stops.
     */
    fun releaseVelocity(tracked: Float, deltaY: Float, elapsedMs: Long): Float {
        if (elapsedMs !in 1..FLICK_WINDOW_MS) return tracked
        val average = deltaY / elapsedMs * 1000f
        return if (abs(average) > abs(tracked)) average else tracked
    }
    /** 1 inside the deck viewport, falling to 0 as a card's edge leaves it by [distance]. */
    fun edgeFade(top: Float, bottom: Float, viewport: Float, distance: Float): Float {
        if (distance <= 0f) return if (bottom <= 0f || top >= viewport) 0f else 1f
        val overshoot = maxOf(-top, bottom - viewport, 0f)
        return (1f - overshoot / distance).coerceIn(0f, 1f)
    }
}
