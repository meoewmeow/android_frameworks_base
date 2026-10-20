package dev.rime.ui.notifications

import dev.rime.ui.models.UiNotification

/** Preserves host order and group policy. Never merges separate apps or invents notification ranking. */
data class NotificationGroup(val id: String, val items: List<UiNotification>)
data class NotificationStackEntry(val key: String, val groupId: String, val notification: UiNotification,
    val groupCount: Int = 1, val collapsedGroup: Boolean = false, val groupHeader: Boolean = false,
    val groupKeys: List<String> = listOf(notification.key))

fun notificationGroups(notifications: List<UiNotification>): List<NotificationGroup> {
    val childGroups = notifications.filterNot { it.isGroupSummary }.mapNotNull { n -> n.groupKey?.let { n.packageName to it } }.toSet()
    return notifications.filterNot { it.isGroupSummary && (it.packageName to it.groupKey) in childGroups }
        .groupBy { it.packageName + ":" + (it.groupKey ?: "single:${it.key}") }
        .map { (id, items) -> NotificationGroup(id, items) }
}

fun stackEntries(groups: List<NotificationGroup>, expanded: Set<String>): List<NotificationStackEntry> =
    groups.flatMap { group ->
        val open = group.id in expanded
        val visible = if (open) group.items else group.items.take(1)
        visible.mapIndexed { index, notification -> NotificationStackEntry(
            key = group.id + ":" + notification.key, groupId = group.id, notification = notification,
            groupKeys = group.items.filterNot { it.ongoing }.map { it.key },
            groupCount = if (index == 0) group.items.size else 1,
            collapsedGroup = !open && group.items.size > 1,
            groupHeader = open && index == 0 && group.items.size > 1,
        ) }
    }
