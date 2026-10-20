package dev.rime.ui.controlcenter

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rime.ui.backdrop.BackdropSurface
import dev.rime.ui.components.*
import dev.rime.ui.models.*
import dev.rime.ui.state.RimeUiHost
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Native primitives with a host-independent, editable four-column layout. */
@Composable
fun EditableControlGrid(snapshot: UiSnapshot, host: RimeUiHost, palette: UiPalette,
    editing: Boolean, onEditingChanged: (Boolean) -> Unit) {
    val latestSettings by rememberUpdatedState(snapshot.settings)
    var gallery by remember { mutableStateOf(false) }
    var expandedControl by remember { mutableStateOf<String?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val placements = remember(snapshot.settings.controls) { ControlCenterLayout.pack(snapshot.settings.controls) }
    val latestPlacements by rememberUpdatedState(placements)
    fun update(items: List<ControlLayoutItem>) = host.updateLockscreenSettings(latestSettings.copy(controls = items))
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 12.dp, end = 9.dp, top = 18.dp)) {
        val cellWidth = (maxWidth - 24.dp) / 4f
        val cellHeight = 78.dp
        val gap = 8.dp
        val rows = placements.maxOfOrNull { it.row + it.item.rows } ?: 0
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                BasicText("+", style = TextStyle(color = palette.foreground, fontSize = 26.sp),
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(palette.surfaceTint.copy(alpha = 0.72f)).clickable { onEditingChanged(true); gallery = false }
                        .semantics { contentDescription = "Edit controls" }.padding(start = 8.dp))
                if (editing) SmallButton("Done", palette) { onEditingChanged(false); gallery = false }
            }
            Box(Modifier.fillMaxWidth().height((cellHeight + gap) * rows)) {
                placements.forEach { placement ->
                    val item = placement.item
                    key(item.id) {
                        var dx by remember { mutableFloatStateOf(0f) }
                        var dy by remember { mutableFloatStateOf(0f) }
                        val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
                        val x by animateDpAsState((cellWidth + gap) * placement.column, spring(dampingRatio = 1f, stiffness = 500f), label = "control-x")
                        val y by animateDpAsState((cellHeight + gap) * placement.row, spring(dampingRatio = 1f, stiffness = 500f), label = "control-y")
                        Box(Modifier.offset(x, y).width(cellWidth * item.columns + gap * (item.columns - 1))
                            .height(cellHeight * item.rows + gap * (item.rows - 1))
                            .graphicsLayer { translationX = dx; translationY = dy; shadowElevation = if (draggingId == item.id) 8.dp.toPx() else 0f }
                            .onGloballyPositioned { coordinates[0] = it }
                            .pointerInput(editing, item.id) {
                                if (editing) {
                                    var last = Offset.Zero
                                    detectDragGestures(onDragStart = { point ->
                                        last = coordinates[0]?.localToRoot(point) ?: point; draggingId = item.id
                                    }, onDragEnd = {
                                        val center = Offset(((cellWidth + gap) * placement.column + cellWidth * item.columns / 2f).toPx() + dx,
                                            ((cellHeight + gap) * placement.row + cellHeight * item.rows / 2f).toPx() + dy)
                                        val target = latestPlacements.filterNot { it.item.id == item.id }.minByOrNull { candidate ->
                                            (center - Offset(((cellWidth + gap) * candidate.column + cellWidth * candidate.item.columns / 2f).toPx(),
                                                ((cellHeight + gap) * candidate.row + cellHeight * candidate.item.rows / 2f).toPx())).getDistance()
                                        }
                                        if (target != null) update(ControlCenterLayout.move(latestSettings.controls, item.id, target.item.id))
                                        scope.launch {
                                            coroutineScope {
                                                launch { animate(dx, 0f, animationSpec = spring(dampingRatio = 1f, stiffness = 500f)) { value, _ -> dx = value } }
                                                launch { animate(dy, 0f, animationSpec = spring(dampingRatio = 1f, stiffness = 500f)) { value, _ -> dy = value } }
                                            }
                                            draggingId = null
                                        }
                                    }, onDragCancel = { dx = 0f; dy = 0f; draggingId = null }) { change, _ ->
                                        val current = coordinates[0]?.localToRoot(change.position) ?: change.position
                                        val delta = current - last; last = current
                                        dx += delta.x; dy += delta.y; change.consume()
                                    }
                                }
                            }, contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxSize().then(if (editing) Modifier.border(1.dp, palette.foreground.copy(alpha = 0.25f), RoundedCornerShape(22.dp)) else Modifier),
                                contentAlignment = Alignment.Center) {
                                ControlModule(item.id, snapshot, host, palette, Modifier.fillMaxSize(), wide = item.columns > 1) { if (!editing) expandedControl = item.id }
                            }
                            if (editing) {
                                // Consume taps/holds over the module while editing; actions stay host-owned.
                                Box(Modifier.matchParentSize().clickable { })
                                SmallButton("−", palette, Modifier.align(Alignment.TopStart).offset((-3).dp, (-3).dp)) {
                                    update(latestSettings.controls.filterNot { it.id == item.id })
                                }
                                val resizeCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
                                SmallButton("⌟", palette, Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp)
                                    .onGloballyPositioned { resizeCoordinates[0] = it }
                                    .pointerInput(item.id) {
                                        var movement = Offset.Zero
                                        var last = Offset.Zero
                                        detectDragGestures(onDragStart = { point ->
                                            movement = Offset.Zero; last = resizeCoordinates[0]?.localToRoot(point) ?: point
                                        }, onDragEnd = {
                                            if (movement.getDistance() > viewConfiguration.touchSlop) update(latestSettings.controls.map { if (it.id == item.id) ControlCenterLayout.resized(it) else it })
                                        }) { change, _ ->
                                            val current = resizeCoordinates[0]?.localToRoot(change.position) ?: change.position
                                            movement += current - last; last = current; change.consume()
                                        }
                                    }) { update(latestSettings.controls.map { if (it.id == item.id) ControlCenterLayout.resized(it) else it }) }
                            }
                        }
                    }
                }
            }
            if (editing) {
                Spacer(Modifier.height(18.dp))
                SmallButton("Add a control", palette) { gallery = true }
            }
        }
        if (gallery) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(palette.surfaceTint.copy(alpha = 0.97f)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BasicText("Controls", style = TextStyle(color = palette.foreground, fontSize = 18.sp))
                SmallButton("Done", palette) { gallery = false }
            }
            val missing = ControlCenterLayout.defaults.filter { candidate -> latestSettings.controls.none { it.id == candidate.id } }
            missing.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { candidate -> SmallButton(candidate.id.replaceFirstChar(Char::uppercase), palette) { update(latestSettings.controls + candidate); gallery = false } }
            } }
            if (missing.isEmpty()) BasicText("All available controls are added", style = TextStyle(color = palette.secondary, fontSize = 13.sp))
            SmallButton("Reset layout", palette) { update(ControlCenterLayout.defaults); gallery = false }
        }
        expandedControl?.let { id ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).clickable { expandedControl = null })
            BackdropSurface(palette.surfaceStyle().copy(opacity = 0.94f), Modifier.align(Alignment.Center).fillMaxWidth().height(300.dp)) {
                Column(Modifier.fillMaxSize().padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        BasicText(id.replaceFirstChar(Char::uppercase), style = TextStyle(color = palette.foreground, fontSize = 18.sp))
                        SmallButton("Done", palette) { expandedControl = null }
                    }
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        ControlModule(id, snapshot, host, palette, Modifier.fillMaxSize()) { }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, palette: UiPalette, modifier: Modifier = Modifier, action: () -> Unit) {
    BasicText(label, style = TextStyle(color = palette.foreground, fontSize = 14.sp),
        modifier = modifier.clip(CircleShape).background(palette.surfaceTint.copy(alpha = 0.92f))
            .clickable(onClick = action).padding(horizontal = 12.dp, vertical = 10.dp))
}

@Composable
private fun ControlModule(id: String, snapshot: UiSnapshot, host: RimeUiHost, palette: UiPalette,
    modifier: Modifier, wide: Boolean = false, onExpand: () -> Unit) {
    val state = snapshot.connectivity
    when (id) {
        "connectivity" -> ConnectivityModule(palette, state.wifiEnabled, state.bluetoothEnabled, state.airplaneMode, state.mobileDataEnabled, host::requestControl, modifier, onExpand)
        "media" -> snapshot.media?.let { MediaControl(it, palette, host::setMediaPlaying, host::skipToPrevious, host::skipToNext, host::seekTo, modifier = modifier, onLongClick = onExpand) }
            ?: EmptyMediaModule(palette, modifier.combinedClickable(onClick = {}, onLongClick = onExpand))
        "brightness" -> VerticalSliderControl("Brightness", RimeIcons.Brightness, state.brightness, palette, host::setBrightness, modifier, onExpand)
        "volume" -> VerticalSliderControl("Volume", RimeIcons.Volume, state.volume, palette, host::setVolume, modifier, onExpand)
        "focus" -> if (wide) PillControl("Focus", RimeIcons.Focus, state.focusEnabled, palette, { host.requestControl(UiControlAction.Focus) }, modifier, onExpand)
            else CircularControl("Focus", RimeIcons.Focus, state.focusEnabled, palette, { host.requestControl(UiControlAction.Focus) }, Modifier.size(66.dp), onExpand)
        else -> {
            val (label, icon, enabled, action) = when (id) {
                "rotation" -> Module("Rotation", RimeIcons.Rotation, state.rotationLocked, UiControlAction.RotationLock)
                "silent" -> Module("Silent", RimeIcons.Notifications, state.silentMode, UiControlAction.SilentMode)
                "flashlight" -> Module("Flashlight", RimeIcons.Flashlight, state.flashlightEnabled, UiControlAction.Flashlight)
                "timer" -> Module("Timer", RimeIcons.Timer, null, UiControlAction.Timer)
                "calculator" -> Module("Calculator", RimeIcons.Calculator, null, UiControlAction.Calculator)
                else -> Module("Camera", RimeIcons.Camera, null, UiControlAction.Camera)
            }
            if (wide) PillControl(label, icon, enabled, palette, { host.requestControl(action) }, modifier, onExpand)
            else CircularControl(label, icon, enabled, palette, { host.requestControl(action) }, Modifier.size(66.dp), onExpand)
        }
    }
}
private data class Module(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val enabled: Boolean?, val action: UiControlAction)
