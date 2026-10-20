package dev.rime.ui.lockscreen

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import dev.rime.ui.notifications.NotificationDeckGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One presentation driver for clock, stacks, scroll and drag. No system/backend state lives here. */
@Stable
class LockscreenSceneState(initialExpansion: Float = 0f) {
    var expansion by mutableFloatStateOf(initialExpansion.coerceIn(0f, 1f))
        private set
    var scrollOffsetPx by mutableFloatStateOf(0f)
        private set
    var targetExpansion = initialExpansion.coerceIn(0f, 1f)
        private set
    var isDragging by mutableStateOf(false)
        private set
    var detailKey by mutableStateOf<String?>(null)
        private set
    fun showDetails(key: String) { detailKey = key }
    fun closeDetails() { detailKey = null }
    var expandedGroups by mutableStateOf(emptySet<String>())
        private set
    val heights = mutableStateMapOf<String, Float>()
    private var settleJob: Job? = null
    private var dragTravel = 1f
    private var dragScope: CoroutineScope? = null
    // Set once a drag has passed the auto-open point: the deck finishes opening by itself and the
    // rest of that movement is not followed until it has.
    private var opening = false
    // These are layout outputs consumed by gesture handling; they do not trigger layout themselves.
    var hitTopPx = Float.POSITIVE_INFINITY
    var hitBottomPx = 0f
    var maxScrollPx = 0f
    /** Expansion at which the second card has been revealed. Dragging up past it opens the deck. */
    var autoOpenExpansion = 0.5f
    /** Counts the times a drag has handed over to the deck opening by itself, for a haptic tick. */
    var autoOpens by mutableIntStateOf(0)
        private set
    /** Stiffness of the spring that opens the deck; lower is calmer. The deck sets it from calibration. */
    var openStiffness = 120f
    // How fast the finger is opening the deck, in expansion per second, so an animation that takes
    // over continues at that speed instead of starting from rest.
    private var dragRate = 0f
    private var lastDragNanos = 0L

    /** Explicit pose control for accessibility and host-owned preview/debug controls. */
    fun jumpTo(value: Float) {
        settleJob?.cancel(); isDragging = false
        expansion = value.coerceIn(0f, 1f); targetExpansion = expansion
        if (expansion < 1f) scrollOffsetPx = 0f
    }
    fun recordHeight(key: String, height: Float) {
        if (height > 0 && abs((heights[key] ?: 0f) - height) > 0.5f) heights[key] = height
    }
    /** With a [scope] the deck can finish opening by itself once the drag passes [autoOpenExpansion]. */
    fun beginDrag(travelPx: Float, scope: CoroutineScope? = null) {
        settleJob?.cancel()
        dragTravel = travelPx.coerceAtLeast(1f)
        dragScope = scope
        opening = false
        dragRate = 0f; lastDragNanos = 0L
        isDragging = true
    }
    /** Delta is measured in the fixed scene, never in a resizing/moving notification card. */
    fun dragBy(deltaY: Float) {
        if (!isDragging || !deltaY.isFinite()) return
        if (opening && expansion < 1f) return
        if (deltaY < 0f) {
            var remaining = -deltaY
            val expandPixels = minOf(remaining, (1f - expansion) * dragTravel)
            val before = expansion
            expansion = (expansion + expandPixels / dragTravel).coerceIn(0f, 1f)
            remaining -= expandPixels
            val now = System.nanoTime()
            val seconds = (now - lastDragNanos) / 1e9f
            if (lastDragNanos != 0L && seconds in 0.001f..0.1f) dragRate = dragRate * 0.5f + (expansion - before) / seconds * 0.5f
            lastDragNanos = now
            if (expansion == 1f) scrollOffsetPx = (scrollOffsetPx + remaining).coerceIn(0f, maxScrollPx.coerceAtLeast(0f))
            else if (before < autoOpenExpansion && expansion >= autoOpenExpansion) dragScope?.let { opening = true; autoOpens++; settle(it, 1f, dragRate) }
        } else {
            val scrollPixels = minOf(deltaY, scrollOffsetPx)
            scrollOffsetPx -= scrollPixels
            expansion = (expansion - (deltaY - scrollPixels) / dragTravel).coerceIn(0f, 1f)
        }
    }
    fun endDrag(scope: CoroutineScope, velocityY: Float, minimumVelocity: Float, decay: DecayAnimationSpec<Float>? = null,
        movedDown: Boolean = false) {
        isDragging = false
        dragScope = null
        if (opening && expansion < 1f) { opening = false; return }
        opening = false
        if (expansion < 1f && velocityY <= -minimumVelocity) settle(scope, 1f, -velocityY / dragTravel)
        else if (expansion in 0.0001f..0.9999f) settle(scope,
            NotificationDeckGeometry.settleTarget(expansion, velocityY, minimumVelocity, autoOpenExpansion, movedDown))
        else if (expansion == 1f && decay != null && abs(velocityY) >= minimumVelocity) {
            settleJob?.cancel()
            settleJob = scope.launch {
                val fling = Animatable(scrollOffsetPx)
                fling.animateDecay(-velocityY, decay) {
                    scrollOffsetPx = value.coerceIn(0f, maxScrollPx.coerceAtLeast(0f))
                    if (value < 0f || value > maxScrollPx) settleJob?.cancel()
                }
            }
        }
    }
    /** [velocity] is the speed the deck already has, in expansion per second. Opening is the calm direction. */
    fun settle(scope: CoroutineScope, target: Float, velocity: Float = 0f) {
        settleJob?.cancel()
        targetExpansion = target.coerceIn(0f, 1f)
        if (target == 0f) scrollOffsetPx = 0f
        val opens = targetExpansion > expansion
        settleJob = scope.launch {
            animate(expansion, targetExpansion, if (opens) velocity.coerceIn(0f, 4f) else 0f,
                spring(dampingRatio = 1f, stiffness = if (opens) openStiffness.coerceIn(20f, 1000f) else 400f)) { value, _ ->
                expansion = value.coerceIn(0f, 1f)
            }
        }
    }
    fun expandGroup(id: String, scope: CoroutineScope) {
        expandedGroups = expandedGroups + id
        settle(scope, 1f)
    }
    fun collapseGroup(id: String) { expandedGroups = expandedGroups - id }
}

/** Collision-free allocations from the same expansion value, in density-independent units. */
object LockscreenLayoutGeometry {
    data class Frame(val clockTop: Float, val clockHeight: Float, val referenceClockHeight: Float,
        val expandedDeckTop: Float, val deckBottom: Float, val collapsedDeckTop: Float)
    fun frame(height: Float, expansion: Float, frontHeight: Float, peeks: Float,
        clockOffset: Float, heightScale: Float, minimumTop: Float = 30f,
        widgetHeight: Float = 0f, preferredHeight: Float? = null, minimumClockHeight: Float? = null,
        listHeight: Float? = null, fingerprintTop: Float? = null): Frame {
        // Clear of the fingerprint sensor, with room for a finger around it.
        val bottom = minOf(height - 118f, fingerprintTop?.let { it - 24f } ?: Float.MAX_VALUE).coerceAtLeast(180f)
        val compactHeight = minimumClockHeight ?: (height * 0.12f).coerceIn(70f, 104f)
        val dateAndGap = 26f + 20f
        val collapsedTop = bottom - frontHeight - peeks
        val compactTop = (minimumTop + dateAndGap + compactHeight + widgetHeight).coerceAtMost(bottom - 80f)
        // A list shorter than the space under the clock opens only as far as it needs, so the clock keeps its size.
        val expandedTop = if (listHeight == null) compactTop else maxOf(compactTop, bottom - listHeight).coerceAtMost(collapsedTop.coerceAtLeast(compactTop))
        val baseHeight = preferredHeight ?: (height * 0.38f).coerceIn(200f, 340f)
        val maxLargeHeight = (collapsedTop - minimumTop - dateAndGap - widgetHeight).coerceAtLeast(compactHeight)
        val largeHeight = (baseHeight * heightScale).coerceIn(compactHeight, maxLargeHeight)
        // Keep the original default center; negative user offsets may move up to the safe top.
        val center = height * 0.12f + baseHeight / 2f + clockOffset
        val largeTop = (center - largeHeight / 2f).coerceIn(minimumTop,
            (collapsedTop - largeHeight - dateAndGap - widgetHeight).coerceAtLeast(minimumTop))
        val e = expansion.coerceIn(0f, 1f)
        val deckTop = collapsedTop + (expandedTop - collapsedTop) * e
        // Notifications consume free space first. Clock contraction starts only on proximity.
        val availableHeight = deckTop - largeTop - dateAndGap - widgetHeight
        val clockHeight = availableHeight.coerceIn(compactHeight, largeHeight)
        val clockTop = minOf(largeTop, deckTop - compactHeight - dateAndGap - widgetHeight).coerceAtLeast(minimumTop)
        return Frame(clockTop, clockHeight, largeHeight, expandedTop, bottom, collapsedTop)
    }
}
