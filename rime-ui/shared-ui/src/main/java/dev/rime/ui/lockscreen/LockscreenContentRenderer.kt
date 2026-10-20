package dev.rime.ui.lockscreen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import dev.rime.ui.models.UiClockState
import dev.rime.ui.models.UiLockscreenContent
import dev.rime.ui.settings.ClockAppearance

/**
 * Host-owned clock/widget slots. The shared scene supplies available bounds and editing intents.
 * A SystemUI renderer can host every existing Axion controller without importing it into shared-ui.
 * It must update depth mask coordinates from the actual placed clock bounds on every layout change.
 * Clock events, doze, weather, widget actions and depth segmentation remain host-owned.
 */
interface LockscreenContentRenderer {
    @Composable
    fun Clock(content: UiLockscreenContent, clock: UiClockState, appearance: ClockAppearance,
        foreground: Color, blurRadiusDp: Float, height: Dp, referenceHeight: Dp,
        editing: Boolean, onEdit: () -> Unit, onGestureStarted: () -> Unit,
        onGestureFinished: (Boolean) -> Unit, onMove: (Float, Float) -> Unit,
        onResize: (Float, Float) -> Unit, onEditDate: () -> Unit, modifier: Modifier)
    @Composable
    fun Widgets(content: UiLockscreenContent, modifier: Modifier)
}

object DefaultLockscreenContentRenderer : LockscreenContentRenderer {
    @Composable
    override fun Clock(content: UiLockscreenContent, clock: UiClockState, appearance: ClockAppearance,
        foreground: Color, blurRadiusDp: Float, height: Dp, referenceHeight: Dp,
        editing: Boolean, onEdit: () -> Unit, onGestureStarted: () -> Unit,
        onGestureFinished: (Boolean) -> Unit, onMove: (Float, Float) -> Unit,
        onResize: (Float, Float) -> Unit, onEditDate: () -> Unit, modifier: Modifier) {
        if (content.hostedClock) { HostedClockSlot(content, height, editing, modifier); return }
        AxionClock(clock, appearance, foreground, blurRadiusDp, height, referenceHeight,
            editing, onEdit, onMove, onResize, onGestureStarted, onGestureFinished, modifier,
            canResize = content.supportsHeightResize, onEditDate = onEditDate,
            subject = if (content.depthEnabled) content.depthSubject else null,
            includeDate = content.depthIncludeDate, keepReadable = content.depthKeepReadable,
            subjectShift = content.depthShift)
    }
    @Composable
    override fun Widgets(content: UiLockscreenContent, modifier: Modifier) { }
}

/**
 * The slot an Axion clock face occupies. The lock screen's host draws the real face there; the
 * editor, which cannot, outlines it and names the face.
 */
@Composable
fun HostedClockSlot(content: UiLockscreenContent, height: Dp, editing: Boolean, modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier.then(Modifier
        .fillMaxWidth().height(height)), contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (editing) {
            androidx.compose.foundation.layout.Box(Modifier.matchParentSize().border(1.5.dp, Color.White.copy(alpha = 0.55f),
                androidx.compose.foundation.shape.RoundedCornerShape(16.dp)))
            androidx.compose.foundation.text.BasicText((content.hostedClockName ?: "Axion clock") + "\nchange it in Axion clocks",
                style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        }
    }
}
