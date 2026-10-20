package dev.rime.ui.lockscreen

import android.view.ViewConfiguration
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rime.ui.backdrop.*
import dev.rime.ui.components.*
import dev.rime.ui.models.*
import dev.rime.ui.notifications.*
import dev.rime.ui.settings.*
import dev.rime.ui.state.RimeUiHost
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

/** Axion capabilities with a scene-owned notification/clock transition and direct editing. */
@Composable
fun LockscreenScene(snapshot: UiSnapshot, host: RimeUiHost, palette: UiPalette,
    editing: Boolean = false, onEdit: () -> Unit = {}, onDeveloperGesture: () -> Unit = {},
    onEditDate: () -> Unit = {}, onEditMore: () -> Unit = {},
    onFinishEdit: () -> Unit = {}, onCancelEdit: () -> Unit = {}, startExpanded: Boolean = false,
    providedState: LockscreenSceneState? = null, onWallpaperGallery: () -> Unit = {},
    contentRenderer: LockscreenContentRenderer = DefaultLockscreenContentRenderer,
    /** Read while drawing, so a transition can fade the deck without recomposing the scene. */
    deckAlpha: () -> Float = { 1f }) {
    val clock = snapshot.settings.clock
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalContext.current
    val minimumVelocity = remember { ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat() }
    val groups = remember(snapshot.notifications) { notificationGroups(snapshot.notifications) }
    val decay = rememberSplineBasedDecay<Float>()
    val state = providedState ?: remember(snapshot.notificationDisplayMode, startExpanded) {
        LockscreenSceneState(if (startExpanded || snapshot.notificationDisplayMode == NotificationDisplayMode.List) 1f else 0f)
    }
    var countRevealed by remember(snapshot.notificationDisplayMode) { mutableStateOf(startExpanded) }
    val entries = remember(groups, state.expandedGroups) { stackEntries(groups, state.expandedGroups) }
    val currentHost by rememberUpdatedState(host)
    var gestureOriginal by remember { mutableStateOf<ClockAppearance?>(null) }
    val calibration = LocalCalibration.current
    val haptics = LocalHapticFeedback.current
    val openGallery by rememberUpdatedState(onWallpaperGallery)
    // Observed as flows, not as effect keys: keyed on expansion these would restart on every drag frame.
    LaunchedEffect(state, startExpanded, snapshot.notificationDisplayMode) {
        snapshotFlow { state.expansion <= 0.0001f && !state.isDragging }.collect { closed ->
            if (closed && !startExpanded && snapshot.notificationDisplayMode == NotificationDisplayMode.Count && state.targetExpansion == 0f) countRevealed = false
        }
    }
    // A light tick at the moment the deck takes over from the finger and opens by itself.
    LaunchedEffect(state) {
        snapshotFlow { state.autoOpens }.drop(1).collect { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
    }
    LaunchedEffect(entries.isEmpty()) { if (entries.isEmpty()) state.settle(scope, 0f) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenHeight = maxHeight.value
        val screenWidth = maxWidth.value
        val frontHeight = (entries.firstOrNull()?.key?.let { state.heights[it] } ?: with(density) { 100.dp.toPx() }) / density.density
        val peek = calibration.stackLipDp * (entries.size.coerceAtMost(3) - 1).coerceAtLeast(0)
        // The cutout counts even while the status bar is hidden.
        val safeTop = maxOf(WindowInsets.statusBars.getTop(density), WindowInsets.displayCutout.getTop(density)) / density.density + 8f
        val content = snapshot.lockscreenContent
        val widgetHeight = content.widgetHeightDp.coerceAtLeast(0f)
        // The editor lays the clock out as collapsed without touching the deck's own state.
        // The open list's full height, animated so a group opening or a card leaving moves its top smoothly.
        val listHeight by animateFloatAsState(entries.sumOf { ((state.heights[it.key] ?: with(density) { 100.dp.toPx() }) / density.density).toDouble() }.toFloat() +
            8f * (entries.size - 1).coerceAtLeast(0), spring(dampingRatio = 1f, stiffness = 400f), label = "notification-list-height")
        // Room above the open list for its Clear control.
        val header = if (entries.isEmpty()) 0f else 36f
        // Derived, so the scene recomposes only when the frame really changes: while the clock is
        // contracting, not on every frame of a drag that never reaches it.
        val frame by remember(screenHeight, frontHeight, peek, clock.topPaddingDp, clock.heightScale, safeTop, widgetHeight,
            content.preferredClockHeightDp, content.minimumClockHeightDp, content.fingerprintTopDp, editing, header) {
            derivedStateOf {
                LockscreenLayoutGeometry.frame(screenHeight, if (editing) 0f else state.expansion, frontHeight, peek,
                    clock.topPaddingDp, clock.heightScale, safeTop, widgetHeight, content.preferredClockHeightDp, content.minimumClockHeightDp,
                    listHeight = if (header == 0f) null else listHeight + header, fingerprintTop = content.fingerprintTopDp)
            }
        }
        if (snapshot.keyguard.dozing && !editing) {
            // Always-on display: the clock and date only, solid and dimmed (no glass, which costs
            // power), moved by a few pixels each minute so nothing burns into the panel.
            val minute = snapshot.clock.epochMillis / 60_000L
            val shiftX = ((minute % 7) - 3) * 2f
            val shiftY = ((minute / 7) % 5 - 2) * 2f
            contentRenderer.Clock(content, snapshot.clock, clock.copy(translucentDigits = false, dualTone = false),
                snapshot.theme.clockColor ?: palette.foreground, 0f,
                height = frame.clockHeight.dp, referenceHeight = frame.referenceClockHeight.dp,
                editing = false, onEdit = {}, onGestureStarted = {}, onGestureFinished = {},
                onMove = { _, _ -> }, onResize = { _, _ -> }, onEditDate = {},
                modifier = Modifier.align(Alignment.TopCenter).padding(top = frame.clockTop.dp, start = 14.dp, end = 14.dp)
                    .offset(x = (clock.horizontalOffsetDp + shiftX).dp, y = shiftY.dp).graphicsLayer { alpha = 0.8f })
            return@BoxWithConstraints
        }
        val deckTop = frame.expandedDeckTop + header
        val travel = with(density) { (frame.collapsedDeckTop - deckTop).coerceAtLeast(24f).dp.toPx() }
        val listOpen by remember { derivedStateOf { state.expansion > 0.85f } }
        val currentTravel by rememberUpdatedState(travel)
        val gesture = if (editing || state.detailKey != null) Modifier else Modifier.pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val frozenTravel = currentTravel
                val inside = down.position.y >= state.hitTopPx && down.position.y <= state.hitBottomPx
                var last = down.position
                var dragging = false
                var decided = false
                var pinchSpan: Float? = null
                var pinchFired = false
                var lastTime = down.uptimeMillis
                // Includes the samples batched inside one frame, which a bare position does not.
                val velocity = VelocityTracker().apply { addPointerInputChange(down) }
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2 && pressed.all { it.position.y > size.height * 0.45f }) {
                        val span = (pressed[0].position - pressed[1].position).getDistance()
                        if (pinchSpan == null) pinchSpan = span
                        if (!pinchFired && abs(span - pinchSpan!!) > 48.dp.toPx()) {
                            val mode = if (span > pinchSpan!!) {
                                if (snapshot.notificationDisplayMode == NotificationDisplayMode.Count) NotificationDisplayMode.Stack else NotificationDisplayMode.List
                            } else {
                                if (snapshot.notificationDisplayMode == NotificationDisplayMode.List) NotificationDisplayMode.Stack else NotificationDisplayMode.Count
                            }
                            currentHost.setDisplayMode(mode); pinchFired = true
                            pressed.forEach { it.consume() }
                        }
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    velocity.addPointerInputChange(change)
                    lastTime = change.uptimeMillis
                    if (!change.pressed) break
                    val delta = change.position - down.position
                    if (!decided && NotificationDeckGeometry.dominantVertical(delta.x, delta.y, viewConfiguration.touchSlop)) {
                        decided = true
                        dragging = inside
                        if (dragging) { state.beginDrag(frozenTravel, scope); last = down.position }
                    }
                    if (dragging) { change.consume(); state.dragBy(change.position.y - last.y) }
                    last = change.position
                }
                if (dragging) state.endDrag(scope, NotificationDeckGeometry.releaseVelocity(velocity.calculateVelocity().y,
                    last.y - down.position.y, lastTime - down.uptimeMillis), minimumVelocity, decay,
                    movedDown = last.y - down.position.y > viewConfiguration.touchSlop * 3f)
            }
        }
        val clockContentWidth = remember { mutableStateOf<Float?>(null) }
        fun editModel(forHeight: Boolean = false): ClockEditModel {
            val scale = if (forHeight) clock.heightScale else clock.sizeScale
            // Faces other than the wide one are as wide as their digits; the wide face fills its box.
            val width = clockContentWidth.value?.takeIf { !clock.face.stretch && it > 0f }
                ?: ((screenWidth - 28f) * (clock.sizeScale * 0.72f).coerceAtMost(1f))
            val geometry = ClockEditScaleGeometry(
                scaleRange = if (forHeight) ClockSizeScaleRange(0.5f, 1f, 1.25f, 1.5f) else AxionClockLimits.sizeScaleRange,
                requestedScale = scale, frameWidthDp = width, minFrameWidthDp = 0f,
                resizeDpPerScale = if (forHeight) 0f else width / clock.sizeScale,
                frameHeightDp = frame.referenceClockHeight,
                resizeHeightDpPerScale = (screenHeight * 0.38f).coerceIn(200f, 340f),
            )
            return ClockEditModel.from(scale, clock.horizontalOffsetDp, clock.topPaddingDp, geometry,
                ClockHorizontalOffsetRange.from(geometry, screenWidth - 28f), ClockTopPaddingRange(-170f, 240f))
        }
        Box(Modifier.fillMaxSize().then(gesture)) {
            // Only empty space handles wallpaper selection; child clock/widgets/cards own holds.
            // Compose's own long press ignores movement, so a slow swipe would open the gallery.
            // This hold ends as soon as the finger leaves touch slop, lifts, or a second one lands.
            if (!editing) Box(Modifier.matchParentSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed || change.isConsumed || event.changes.size > 1 ||
                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                        }
                    }
                    if (interrupted == null) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); openGallery() }
                }
            }.semantics { onLongClick("Lock screen gallery") { openGallery(); true } })
            if (editing) Row(Modifier.zIndex(5f).align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                EditButton("Cancel", palette, onCancelEdit)
                EditButton("Done", palette, onFinishEdit)
            } else if (snapshot.keyguard.showStatusRegion) Row(Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = 24.dp)
                .combinedClickable(onClick = {}, onLongClick = onDeveloperGesture),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NativeIcon(RimeIcons.Wifi, "Wi-Fi", palette.foreground, Modifier.size(16.dp))
                BasicText("${snapshot.connectivity.batteryPercent}%", style = TextStyle(color = palette.foreground, fontSize = 12.sp))
            }
            CompositionLocalProvider(LocalClockContentWidth provides clockContentWidth) {
            contentRenderer.Clock(content, snapshot.clock, clock, snapshot.theme.clockColor ?: palette.foreground, clock.blurRadiusDp ?: snapshot.settings.surfaces.blurRadiusDp,
                height = frame.clockHeight.dp, referenceHeight = frame.referenceClockHeight.dp,
                editing = editing, onEdit = onEdit,
                onGestureStarted = { gestureOriginal = clock },
                onGestureFinished = { cancelled ->
                    val original = gestureOriginal
                    if (cancelled && original != null) host.updateLockscreenSettings(snapshot.settings.copy(clock = original))
                    else if (abs(clock.horizontalOffsetDp) <= AxionClockLimits.HORIZONTAL_CENTER_SNAP_THRESHOLD_DP)
                        host.updateLockscreenSettings(snapshot.settings.copy(clock = clock.copy(horizontalOffsetDp = 0f)))
                    gestureOriginal = null
                },
                onMove = { dx, dy ->
                    val model = editModel().beginPositionDrag().movePositionBy(dx, dy, 1f, 1f)
                    host.updateLockscreenSettings(snapshot.settings.copy(clock = clock.copy(horizontalOffsetDp = model.horizontalOffsetDp, topPaddingDp = model.topPaddingDp)))
                },
                onResize = { dx, dy ->
                    if (!content.supportsHeightResize) return@Clock
                    var next = clock
                    // Sideways drags widen the wide face; every face grows and shrinks with the height.
                    if (clock.face.stretch && abs(dx) > abs(dy)) {
                        val model = editModel().beginSizeDrag().resizeBy(dx, 0f, 1f, 1f, ClockResizeCorner.BottomRight)
                        next = next.copy(sizeScale = model.sizeScaleOverride, horizontalOffsetDp = model.horizontalOffsetDp)
                    } else {
                        val model = editModel(true).beginSizeDrag().resizeBy(0f, dy, 1f, 1f, ClockResizeCorner.BottomRight)
                        next = next.copy(heightScale = model.sizeScaleOverride, topPaddingDp = model.topPaddingDp)
                    }
                    host.updateLockscreenSettings(snapshot.settings.copy(clock = next))
                },
                onEditDate = onEditDate,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = frame.clockTop.dp, start = 14.dp, end = 14.dp)
                    .offset(x = editModel().horizontalOffsetDp.dp)
                    // Second step of the swipe: once the list is open and scrolls, the clock and date
                    // scroll away with it, which is what shows that the list has moved.
                    .offset { IntOffset(0, -state.scrollOffsetPx.coerceIn(0f, state.maxScrollPx.coerceAtLeast(0f)).roundToInt()) },
            )
            }
            if (widgetHeight > 0f) contentRenderer.Widgets(content, Modifier.align(Alignment.TopCenter)
                .padding(top = (frame.clockTop + frame.clockHeight + 26f).dp, start = 14.dp, end = 14.dp)
                .offset { IntOffset(0, -state.scrollOffsetPx.coerceIn(0f, state.maxScrollPx.coerceAtLeast(0f)).roundToInt()) }
                .fillMaxWidth().height(widgetHeight.dp))
            if (!editing) {
                if (snapshot.notificationDisplayMode == NotificationDisplayMode.Count && !countRevealed) {
                    if (snapshot.notifications.isNotEmpty()) NotificationCountSurface(snapshot.notifications.size, palette,
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp).graphicsLayer { alpha = deckAlpha() }.clickable {
                            countRevealed = true; state.settle(scope, 1f)
                        })
                } else NotificationDeck(entries, host, palette, state, deckTop, frame.deckBottom,
                    calibration.stackLipDp,
                    // The deck keeps its place in the layout while faded, so the clock is sized the same either way.
                    Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 118.dp).graphicsLayer { alpha = deckAlpha() })
                // Above the open list and scrolling with it, like the clock.
                if (listOpen && entries.isNotEmpty()) ClearAllButton(palette, { ((state.expansion - 0.85f) / 0.15f).coerceIn(0f, 1f) },
                    host::clearNotifications, Modifier.align(Alignment.TopEnd).padding(top = (frame.expandedDeckTop + 2f).dp, end = 16.dp)
                        .offset { IntOffset(0, -state.scrollOffsetPx.coerceIn(0f, state.maxScrollPx.coerceAtLeast(0f)).roundToInt()) })
                if (snapshot.keyguard.showShortcuts || snapshot.keyguard.showUnlockAffordance) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 26.dp, vertical = 35.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    if (snapshot.keyguard.showShortcuts) Shortcut(RimeIcons.Camera, "Camera", palette) { host.requestLockShortcut(LockShortcut.Camera) }
                    if (snapshot.keyguard.locked && snapshot.keyguard.showUnlockAffordance) BackdropSurface(palette.surfaceStyle(BackdropShape.Circle), Modifier.size(66.dp).clickable { host.requestUnlock() }) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { NativeIcon(RimeIcons.Fingerprint, "Request unlock", palette.foreground, Modifier.size(40.dp)) }
                    } else Spacer(Modifier.size(66.dp))
                    if (snapshot.keyguard.showShortcuts) Shortcut(RimeIcons.Flashlight, "Flashlight", palette) { host.requestLockShortcut(LockShortcut.Flashlight) }
                }
            } else BackdropSurface(palette.surfaceStyle(BackdropShape.Circle), Modifier.align(Alignment.BottomEnd).padding(26.dp).size(46.dp)
                .clip(CircleShape).clickable(onClickLabel = "More options", onClick = onEditMore)) {
                // The editor's only standing control besides Cancel and Done; details live behind it.
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { Box(Modifier.padding(horizontal = 2.dp).size(5.dp).clip(CircleShape).background(palette.foreground)) }
                }
            }
            state.detailKey?.let { key ->
                val notification = snapshot.notifications.firstOrNull { it.key == key }
                if (notification != null) {
                    BackdropSurface(palette.surfaceStyle().copy(cornerRadius = 0.dp, opacity = 0.45f),
                        Modifier.fillMaxSize().clickable { state.closeDetails() }) { }
                    Column(Modifier.align(Alignment.Center).padding(horizontal = 14.dp).fillMaxWidth()
                        .heightIn(max = (screenHeight * 0.75f).dp).verticalScroll(rememberScrollState())) {
                        NotificationSurface(notification.copy(expanded = true), palette,
                            styleOverride = palette.surfaceStyle().copy(opacity = 0.94f),
                            onActivate = { if (notification.canOpen) host.activateNotification(key) },
                            onExpand = { state.closeDetails() },
                            onDismiss = { host.dismissNotification(key); state.closeDetails() },
                            onManage = { host.manageNotification(key) },
                            onAction = { host.performNotificationAction(key, it); state.closeDetails() })
                        Spacer(Modifier.height(10.dp))
                        EditButton("Done", palette) { state.closeDetails() }
                    }
                }
            }
            if (snapshot.keyguard.showGestureHandle) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).width(80.dp).height(2.dp).background(palette.foreground.copy(alpha = 0.6f)))
        }
    }
}

/** Clears every notification that can be cleared. The first tap arms it, the second confirms, as on iOS. */
@Composable
private fun ClearAllButton(palette: UiPalette, visibility: () -> Float, onClear: () -> Unit, modifier: Modifier = Modifier) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
    BackdropSurface(palette.surfaceStyle(BackdropShape.Capsule), modifier.graphicsLayer { alpha = visibility() }.height(30.dp).clip(CircleShape)
        .clickable(onClickLabel = if (armed) "Clear all notifications" else "Clear notifications") {
            if (visibility() < 0.99f) return@clickable
            if (armed) { armed = false; onClear() } else armed = true
        }) {
        if (armed) Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            BasicText("Clear", style = TextStyle(color = palette.foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        } else androidx.compose.foundation.Canvas(Modifier.size(30.dp)) {
            val inset = size.width * 0.34f; val stroke = 1.8.dp.toPx()
            drawLine(palette.foreground, Offset(inset, inset), Offset(size.width - inset, size.height - inset), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(palette.foreground, Offset(size.width - inset, inset), Offset(inset, size.height - inset), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
private fun EditButton(label: String, palette: UiPalette, action: () -> Unit) {
    BackdropSurface(palette.surfaceStyle(BackdropShape.Capsule), Modifier.clickable(onClick = action)) {
        BasicText(label, style = TextStyle(color = palette.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
    }
}
@Composable
private fun Shortcut(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, palette: UiPalette, onClick: () -> Unit) {
    BackdropSurface(palette.surfaceStyle(BackdropShape.Circle), Modifier.size(46.dp).clip(CircleShape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { NativeIcon(icon, label, palette.foreground, Modifier.size(24.dp)) }
    }
}
