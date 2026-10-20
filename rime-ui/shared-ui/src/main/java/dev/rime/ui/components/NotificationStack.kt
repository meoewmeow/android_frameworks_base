package dev.rime.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rime.ui.backdrop.BackdropShape
import dev.rime.ui.backdrop.BackdropStyle
import dev.rime.ui.backdrop.BackdropSurface
import dev.rime.ui.models.UiNotification

@Composable
fun NotificationStack(
    items: List<UiNotification>,
    palette: UiPalette,
    expanded: Boolean,
    onToggleGroup: () -> Unit,
    onActivate: (String) -> Unit,
    onExpand: (String, Boolean) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAction: (String, String) -> Unit = { _, _ -> },
) {
    if (items.isEmpty()) return
    if (items.size == 1 || expanded) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items.forEach { item ->
                NotificationSurface(
                    notification = item,
                    palette = palette,
                    onActivate = { onActivate(item.key) },
                    onExpand = { onExpand(item.key, !item.expanded) },
                    onDismiss = { onDismiss(item.key) },
                    onAction = { onAction(item.key, it) },
                )
            }
        }
        return
    }

    val top = items.last()
    val backLayers = items.size.coerceAtMost(3) - 1
    Box(
        modifier = modifier.fillMaxWidth().height(76.dp + (backLayers * 6).dp),
    ) {
        for (depth in backLayers downTo 1) {
            BackdropSurface(
                style = BackdropStyle(
                    shape = BackdropShape.RoundedRectangle,
                    cornerRadius = palette.cornerRadiusDp.dp,
                    tint = palette.surfaceTint,
                    opacity = palette.surfaceOpacity * 0.88f,
                    blurRadius = palette.blurRadiusDp.dp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = (depth * 5).dp)
                    .height(68.dp)
                    .offset(y = (depth * 6).dp)
                    .zIndex(-depth.toFloat()),
            ) {}
        }
        NotificationSurface(
            notification = top,
            palette = palette,
            groupCount = items.size,
            onGroupTap = onToggleGroup,
            onActivate = { onActivate(top.key) },
            onExpand = { onExpand(top.key, !top.expanded) },
            onDismiss = { items.forEach { onDismiss(it.key) } },
            onAction = { onAction(top.key, it) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun NotificationCountSurface(count: Int, palette: UiPalette, modifier: Modifier = Modifier) {
    BackdropSurface(
        style = BackdropStyle(
            shape = BackdropShape.Capsule,
            tint = palette.surfaceTint,
            opacity = palette.surfaceOpacity,
            blurRadius = palette.blurRadiusDp.dp,
        ),
        modifier = modifier,
    ) {
        androidx.compose.foundation.text.BasicText(
            text = count.toString() + " notifications",
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            style = androidx.compose.ui.text.TextStyle(color = palette.onSurface, fontSize = 13.sp),
        )
    }
}
