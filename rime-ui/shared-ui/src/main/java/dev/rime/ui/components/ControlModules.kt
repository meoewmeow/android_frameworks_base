package dev.rime.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rime.ui.backdrop.BackdropShape
import dev.rime.ui.backdrop.BackdropStyle
import dev.rime.ui.backdrop.BackdropSurface
import dev.rime.ui.models.UiControlAction

@Composable
fun ConnectivityModule(
    palette: UiPalette,
    wifi: Boolean?,
    bluetooth: Boolean?,
    airplane: Boolean?,
    mobileData: Boolean?,
    onAction: (UiControlAction) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    BackdropSurface(
        style = palette.surfaceStyle(BackdropShape.RoundedRectangle, selected = false),
        modifier = modifier,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ControlCell(
                    "Airplane", RimeIcons.Airplane, airplane, palette,
                    Modifier.weight(1f).fillMaxHeight(),
                    onLongClick = onLongClick) { onAction(UiControlAction.AirplaneMode) }
                ControlCell(
                    "Mobile", RimeIcons.Cellular, mobileData, palette,
                    Modifier.weight(1f).fillMaxHeight(),
                    onLongClick = onLongClick) { onAction(UiControlAction.MobileData) }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ControlCell(
                    "Wi-Fi", RimeIcons.Wifi, wifi, palette,
                    Modifier.weight(1f).fillMaxHeight(),
                    onLongClick = onLongClick) { onAction(UiControlAction.Wifi) }
                ControlCell(
                    "Bluetooth", RimeIcons.Bluetooth, bluetooth, palette,
                    Modifier.weight(1f).fillMaxHeight(),
                    onLongClick = onLongClick) { onAction(UiControlAction.Bluetooth) }
            }
        }
    }
}

@Composable
private fun ControlCell(
    label: String,
    icon: ImageVector,
    enabled: Boolean?,
    palette: UiPalette,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val selected = enabled == true
    val tint = if (selected) Color.White else palette.onSurface
    val fill = if (selected) palette.accent.copy(alpha = 0.92f) else Color.Transparent
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(19.dp))
            .background(fill)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = label + " " + stateLabel(enabled) }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        NativeIcon(icon, null, tint, Modifier.size(18.dp))
        androidx.compose.foundation.text.BasicText(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.ui.text.TextStyle(
                color = tint,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

@Composable
fun CircularControl(
    label: String,
    icon: ImageVector,
    enabled: Boolean?,
    palette: UiPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val selected = enabled == true
    val tint = if (selected) Color.White else palette.onSurface
    BackdropSurface(
        style = palette.surfaceStyle(BackdropShape.Circle, selected = selected),
        modifier = modifier.size(66.dp).clip(CircleShape).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = label + " " + stateLabel(enabled) },
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            NativeIcon(icon, null, tint, Modifier.size(23.dp))
            androidx.compose.foundation.text.BasicText(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                style = androidx.compose.ui.text.TextStyle(color = tint, fontSize = 9.sp, fontWeight = FontWeight.Medium))
        }
    }
}

@Composable
fun PillControl(
    label: String,
    icon: ImageVector,
    enabled: Boolean?,
    palette: UiPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val selected = enabled == true
    val tint = if (selected) Color.White else palette.onSurface
    BackdropSurface(
        style = palette.surfaceStyle(BackdropShape.Capsule, selected = selected),
        modifier = modifier.clip(CircleShape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NativeIcon(icon, null, tint, Modifier.size(20.dp))
            androidx.compose.foundation.text.BasicText(
                label,
                style = androidx.compose.ui.text.TextStyle(
                    color = tint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

private fun stateLabel(value: Boolean?): String = when (value) {
    true -> "on"
    false -> "off"
    null -> "unavailable"
}
