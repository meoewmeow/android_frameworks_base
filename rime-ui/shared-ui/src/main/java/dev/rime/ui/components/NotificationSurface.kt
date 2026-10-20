package dev.rime.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rime.ui.backdrop.BackdropSurface
import dev.rime.ui.backdrop.BackdropStyle
import dev.rime.ui.models.UiNotification
import dev.rime.ui.settings.LocalCalibration
import kotlinx.coroutines.launch

/** Common notification content shell. Host keeps ranking, intents, security and action execution. */
@Composable
fun NotificationSurface(
    notification: UiNotification, palette: UiPalette,
    onActivate: () -> Unit, onExpand: () -> Unit, onDismiss: () -> Unit,
    groupCount: Int? = null, onGroupTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier, onAction: (String) -> Unit = {}, contentAlpha: Float = 1f,
    interactive: Boolean = true, collapsedHeightPx: Float? = null, presentationExpansion: Float = 1f,
    onNaturalHeight: (Int) -> Unit = {}, onCollapseGroup: (() -> Unit)? = null, onManage: (() -> Unit)? = null,
    styleOverride: BackdropStyle? = null,
    surfaceAlpha: Float = 1f, surfaceScale: Float = 1f,
    // While a card is only a lip in the stack, the lip shows one summary line or [lipLabel].
    lipAlpha: Float = 0f, lipHeight: Dp = 0.dp, lipLabel: String? = null,
    // A tap on a card that is only a lip, where it is not interactive itself.
    onStackTap: (() -> Unit)? = null,
    // Several notifications from one app, closed: two cards peek out below this one. The space
    // they take is added to the reported natural height, so the deck leaves room for them.
    showGroupLips: Boolean = false,
) {
    val calibration = LocalCalibration.current
    val style = (styleOverride ?: palette.surfaceStyle()).copy(alpha = surfaceAlpha, scale = surfaceScale)
    var offsetX by remember(notification.key) { mutableFloatStateOf(0f) }
    var widthPx by remember(notification.key) { mutableFloatStateOf(1f) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val activate by rememberUpdatedState(onGroupTap ?: onActivate)
    val expand by rememberUpdatedState(onExpand)
    val dismiss by rememberUpdatedState(onDismiss)
    val canDismiss = !notification.ongoing && interactive
    val revealWidth = with(LocalDensity.current) { 152.dp.toPx() }
    val groupLips = showGroupLips && (groupCount ?: 0) > 1
    val lipsPx = with(LocalDensity.current) { if (groupLips) GROUP_LIPS_DP.dp.roundToPx() else 0 }
    // Reported whenever the content or the lips change; the lips can appear without a size change.
    var contentHeight by remember(notification.key) { mutableIntStateOf(-1) }
    val reportHeight by rememberUpdatedState(onNaturalHeight)
    LaunchedEffect(lipsPx) { if (contentHeight >= 0) reportHeight(contentHeight + lipsPx) }
    Box(modifier.fillMaxWidth()) {
    if (offsetX < -1f) Row(Modifier.matchParentSize().padding(end = 4.dp),
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        BasicText("Options", style = TextStyle(color = palette.onSurface, fontSize = 12.sp),
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(palette.surfaceTint.copy(alpha = 0.95f))
                .clickable(enabled = interactive) { (onManage ?: onExpand)() }.padding(horizontal = 13.dp, vertical = 20.dp))
        Spacer(Modifier.width(4.dp))
        BasicText("Clear", style = TextStyle(color = Color.White, fontSize = 12.sp),
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF9F3541))
                .clickable(enabled = canDismiss) { dismiss() }.padding(horizontal = 16.dp, vertical = 20.dp))
    }
    BackdropSurface(style, Modifier.fillMaxWidth()
        .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
        .graphicsLayer { translationX = offsetX; alpha = 1f - (kotlin.math.abs(offsetX) / widthPx).coerceIn(0f, 0.3f) }
        .surfaceShadow(style.cornerRadius, calibration.shadowOpacity, calibration.shadowBlurDp.dp, calibration.shadowOffsetDp.dp)
        .then(if (!groupLips) Modifier else Modifier.drawBehind {
            // Drawn behind the card and below its bottom edge, narrower each step, like a real pile.
            val radius = style.cornerRadius.toPx()
            // Only below the card: the card is translucent, so a lip behind it would darken its bottom.
            clipRect(top = size.height, bottom = size.height + GROUP_LIPS_DP.dp.toPx()) {
            listOf(2 to 0.55f, 1 to 0.8f).forEach { (step, strength) ->
                val inset = (10.dp.toPx() * step)
                val drop = (GROUP_LIPS_DP.dp.toPx() / 2f) * step
                drawRoundRect(palette.surfaceTint.copy(alpha = (palette.surfaceOpacity * strength + 0.12f).coerceAtMost(1f) * surfaceAlpha),
                    topLeft = Offset(inset, size.height - radius * 2f + drop),
                    size = Size(size.width - inset * 2f, radius * 2f),
                    cornerRadius = CornerRadius(radius * 0.8f))
            }
            }
        })
        .pointerInput(notification.key, canDismiss) {
            if (canDismiss) detectHorizontalDragGestures(
                onHorizontalDrag = { change, amount -> change.consume(); offsetX = (offsetX + amount).coerceIn(-widthPx, 0f) },
                onDragEnd = {
                    if (-offsetX >= widthPx * 0.60f) {
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate); dismiss()
                    } else scope.launch {
                        val target = if (-offsetX >= threshold) -revealWidth.coerceAtMost(widthPx * 0.6f) else 0f
                        animate(offsetX, target, animationSpec = spring()) { v, _ -> offsetX = v }
                    }
                }, onDragCancel = { offsetX = 0f },
            )
        // A disabled clickable still swallows the tap, so a lip handles its own instead of leaving it to a parent.
        }.combinedClickable(enabled = interactive || onStackTap != null, onClick = {
            if (!interactive) onStackTap?.invoke()
            else if (offsetX < -1f) scope.launch { animate(offsetX, 0f, animationSpec = spring()) { v, _ -> offsetX = v } }
            else activate()
        }, onLongClick = if (interactive) ({ expand() }) else null)
        .semantics {
            contentDescription = "${notification.appName}, ${notification.title}" + (groupCount?.let { ", $it notifications" } ?: "")
            customActions = buildList {
                add(CustomAccessibilityAction(if (notification.expanded) "Collapse notification" else "Expand notification") { expand(); true })
                if (canDismiss) add(CustomAccessibilityAction("Dismiss notification") { dismiss(); true })
            }
        }) {
        Layout(content = {
        Column(Modifier.fillMaxWidth().onSizeChanged { contentHeight = it.height; reportHeight(it.height + lipsPx) }.graphicsLayer { alpha = contentAlpha }
            .padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                AppIcon(notification)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(notification.appName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = palette.onSurfaceSecondary, fontSize = 12.sp), modifier = Modifier.weight(1f))
                        if (onCollapseGroup != null) BasicText("Show less", style = TextStyle(color = palette.onSurface, fontSize = 11.sp),
                            modifier = Modifier.clickable(enabled = interactive, onClick = onCollapseGroup).padding(horizontal = 8.dp, vertical = 5.dp))
                        BasicText(notification.timestamp, style = TextStyle(color = palette.onSurfaceSecondary, fontSize = 11.sp))
                        // The count is a badge so a pile from one app reads as a pile at a glance.
                        if (groupCount != null && groupCount > 1) BasicText(groupCount.toString(),
                            style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(palette.accent.copy(alpha = 0.92f))
                                .padding(horizontal = 7.dp, vertical = 1.dp))
                    }
                    BasicText(notification.title, maxLines = if (notification.expanded) 3 else 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = palette.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                    if (notification.text.isNotBlank()) BasicText(notification.text,
                        maxLines = if (notification.expanded) 12 else 2, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = palette.onSurface, fontSize = 13.sp, lineHeight = 17.sp))
                    if (groupCount != null && groupCount > 1 && onCollapseGroup == null) BasicText(
                        "+${groupCount - 1} more from ${notification.appName}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = palette.onSurfaceSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                }
            }
            notification.progress?.let { progress ->
                Box(Modifier.fillMaxWidth().padding(start = 53.dp).height(8.dp)
                    .clip(RoundedCornerShape(8.dp)).background(palette.onSurface.copy(alpha = 0.18f))
                    .semantics { progressBarRangeInfo = if (progress.indeterminate) ProgressBarRangeInfo.Indeterminate else ProgressBarRangeInfo(progress.value.coerceIn(0f, 1f), 0f..1f) }) {
                    Box(Modifier.fillMaxWidth(if (progress.indeterminate) 0.35f else progress.value.coerceIn(0f, 1f)).fillMaxHeight().background(palette.onSurface))
                }
            }
            if (notification.expanded && notification.actions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    notification.actions.forEach { action ->
                        BasicText(action.label, style = TextStyle(color = palette.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(palette.onSurface.copy(alpha = 0.12f))
                                .clickable(enabled = interactive) { onAction(action.id) }.padding(horizontal = 14.dp, vertical = 12.dp))
                    }
                }
            }
        }
        }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
            val body = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
            val height = collapsedHeightPx?.let { it + (body.height - it) * presentationExpansion } ?: body.height.toFloat()
            layout(constraints.maxWidth, height.toInt().coerceAtLeast(1)) { body.place(0, 0) }
        }
        if (lipAlpha > 0f) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(lipHeight)
            .graphicsLayer { alpha = lipAlpha }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (lipLabel != null) Arrangement.Center else Arrangement.Start) {
            if (lipLabel != null) BasicText(lipLabel, maxLines = 1,
                style = TextStyle(color = palette.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            else {
                AppIcon(notification, 18.dp)
                Spacer(Modifier.width(9.dp))
                BasicText(notification.appName + "  " + notification.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = palette.onSurface, fontSize = 13.sp), modifier = Modifier.weight(1f))
                BasicText(notification.timestamp, style = TextStyle(color = palette.onSurfaceSecondary, fontSize = 11.sp))
            }
        }
    }
    }
}

private const val GROUP_LIPS_DP = 14

@Composable
private fun AppIcon(notification: UiNotification, size: Dp = 38.dp) {
    val icon = notification.appIcon
    val shape = RoundedCornerShape(size * 0.29f)
    if (icon != null) Image(icon, "${notification.appName} icon", modifier = Modifier.size(size).clip(shape))
    else Box(Modifier.size(size).clip(shape).background(Color(0xFF526F73)), contentAlignment = Alignment.Center) {
        BasicText(notification.appName.take(1), style = TextStyle(color = Color.White, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold))
    }
}
