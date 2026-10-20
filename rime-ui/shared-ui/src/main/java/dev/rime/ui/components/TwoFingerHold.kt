package dev.rime.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Two fingers held still for 750 ms. It never consumes anything, so whatever is underneath
 * still sees the touches; any movement or lifting a finger early cancels it.
 */
fun Modifier.twoFingerHold(onHold: () -> Unit): Modifier = pointerInput(onHold) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var twoPointersSeen = false
        val releasedBeforeHold = withTimeoutOrNull(750L) {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.any { (it.position - it.previousPosition).getDistance() > 2f }) return@withTimeoutOrNull true
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) twoPointersSeen = true
                if (twoPointersSeen && pressed < 2) return@withTimeoutOrNull true
                if (pressed == 0) return@withTimeoutOrNull true
            }
        }
        if (twoPointersSeen && releasedBeforeHold == null) onHold()
    }
}
