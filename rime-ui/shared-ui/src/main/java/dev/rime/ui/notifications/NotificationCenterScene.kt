package dev.rime.ui.notifications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rime.ui.backdrop.BackdropShape
import dev.rime.ui.backdrop.BackdropStyle
import dev.rime.ui.backdrop.BackdropSurface
import dev.rime.ui.components.NativeIcon
import dev.rime.ui.components.NotificationCountSurface
import dev.rime.ui.components.NotificationStack
import dev.rime.ui.components.UiPalette
import dev.rime.ui.models.NotificationDisplayMode
import dev.rime.ui.models.UiNotification
import dev.rime.ui.models.UiSnapshot
import dev.rime.ui.state.RimeUiHost

@Composable
fun NotificationCenterScene(snapshot: UiSnapshot, host: RimeUiHost, palette: UiPalette) {
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val notifications = visibleNotifications(snapshot.notifications)
    val groups = notifications.groupBy { it.groupKey ?: ("single-" + it.key) }.toList()

    Column(
        modifier = Modifier.fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.text.BasicText(
                snapshot.clock.date,
                style = androidx.compose.ui.text.TextStyle(
                    color = palette.foreground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier.weight(1f),
            )
            if (notifications.isNotEmpty()) {
                BackdropSurface(
                    style = BackdropStyle(
                        shape = BackdropShape.Capsule,
                        tint = palette.surfaceTint,
                        opacity = palette.surfaceOpacity * 0.9f,
                        blurRadius = palette.blurRadiusDp.dp,
                    ),
                    modifier = Modifier.clickable(onClick = host::clearNotifications)
                        .semantics { contentDescription = "Clear notifications" },
                ) {
                    androidx.compose.foundation.text.BasicText(
                        text = "Clear",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = androidx.compose.ui.text.TextStyle(color = palette.foreground, fontSize = 12.sp),
                    )
                }
            }
        }

        when (snapshot.notificationDisplayMode) {
            NotificationDisplayMode.Count -> {
                Spacer(Modifier.weight(1f))
                if (notifications.isNotEmpty()) {
                    NotificationCountSurface(notifications.size, palette, Modifier.align(Alignment.CenterHorizontally))
                }
                Spacer(Modifier.weight(1f))
            }
            NotificationDisplayMode.Stack,
            NotificationDisplayMode.List -> {
                if (groups.isEmpty()) {
                    Spacer(Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 6.dp, bottom = 18.dp),
                    ) {
                        if (snapshot.notificationDisplayMode == NotificationDisplayMode.Stack) {
                            items(groups, key = { it.first }) { (groupKey, group) ->
                                val expanded = expandedGroups[groupKey] ?: false
                                NotificationStack(
                                    items = group,
                                    palette = palette,
                                    expanded = expanded,
                                    onToggleGroup = { expandedGroups[groupKey] = !expanded },
                                    onActivate = host::activateNotification,
                                    onExpand = host::setNotificationExpanded,
                                    onDismiss = host::dismissNotification,
                                    onAction = host::performNotificationAction,
                                )
                            }
                        } else {
                            items(notifications, key = { it.key }) { notification ->
                                NotificationStack(
                                    items = listOf(notification),
                                    palette = palette,
                                    expanded = true,
                                    onToggleGroup = {},
                                    onActivate = host::activateNotification,
                                    onExpand = host::setNotificationExpanded,
                                    onDismiss = host::dismissNotification,
                                    onAction = host::performNotificationAction,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun visibleNotifications(items: List<UiNotification>): List<UiNotification> {
    val childGroups = items.filterNot { it.isGroupSummary }.mapNotNull { it.groupKey }.toSet()
    return items.filterNot { it.isGroupSummary && it.groupKey in childGroups }
}
