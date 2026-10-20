package dev.rime.preview.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.format.DateUtils
import androidx.compose.ui.graphics.asImageBitmap
import dev.rime.preview.mock.PreviewStore
import dev.rime.ui.models.UiNotification
import dev.rime.ui.models.UiNotificationProgress
import dev.rime.ui.models.UiNotificationAction

/** Optional public-API bridge. All Android notification objects stay in preview-app. */
class PreviewNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationListenerAccess.attach(this)
        val active = activeNotifications.orEmpty()
        active.forEach(NotificationListenerAccess::remember)
        val items = active.mapNotNull(::toModel)
        PreviewStore.setRealNotificationSourceActive(active = true, items = items)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationListenerAccess.remember(sbn)
        PreviewStore.replaceRealNotifications(
            PreviewStore.realNotificationSnapshot().filterNot { it.key == sbn.key } + listOfNotNull(toModel(sbn)),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationListenerAccess.forget(sbn.key)
        PreviewStore.removeRealNotification(sbn.key)
    }

    override fun onListenerDisconnected() {
        NotificationListenerAccess.detach(this)
        PreviewStore.setRealNotificationSourceActive(active = false, items = emptyList())
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        NotificationListenerAccess.detach(this)
        PreviewStore.setRealNotificationSourceActive(active = false, items = emptyList())
        super.onDestroy()
    }

    private fun toModel(sbn: StatusBarNotification): UiNotification? {
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0),
            ).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            sbn.packageName.substringAfterLast('.')
        }
        val extras = sbn.notification.extras
        val isGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf(String::isNotBlank) ?: appName
        val body = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).firstOrNull { !it.isNullOrBlank() }?.toString().orEmpty()
        val icon = runCatching {
            packageManager.getApplicationIcon(sbn.packageName).toImageBitmap()
        }.recoverCatching {
            sbn.notification.smallIcon?.loadDrawable(this)?.toImageBitmap()
        }.getOrNull()
        return UiNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            appIcon = icon,
            title = title,
            text = body,
            timestamp = DateUtils.getRelativeTimeSpanString(
                sbn.postTime,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString(),
            groupKey = sbn.groupKey,
            isConversation = sbn.notification.category == Notification.CATEGORY_MESSAGE ||
                extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null,
            isGroupSummary = isGroupSummary,
            ongoing = sbn.isOngoing,
            canOpen = sbn.notification.contentIntent != null,
            progress = if (extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
                UiNotificationProgress(extras.getInt(Notification.EXTRA_PROGRESS).toFloat() / extras.getInt(Notification.EXTRA_PROGRESS_MAX, 1).coerceAtLeast(1), extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) else null,
            actions = sbn.notification.actions.orEmpty().mapIndexedNotNull { index, action ->
                if (action.remoteInputs.isNullOrEmpty()) UiNotificationAction(index.toString(), action.title.toString()) else null
            },
        )
    }

    private fun Drawable.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap? {
        val width = intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(128) ?: 64
        val height = intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(128) ?: 64
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, width, height)
        draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }
}

/** Holds only the public service operations needed by preview controls. */
object NotificationListenerAccess {
    @Volatile private var service: PreviewNotificationListenerService? = null
    @Volatile private var applicationContext: Context? = null
    private val actionIntents = mutableMapOf<String, Map<String, PendingIntent>>()
    private val contentIntents = mutableMapOf<String, PendingIntent>()

    fun setApplicationContext(context: Context) {
        applicationContext = context.applicationContext
    }

    fun openSettings() {
        applicationContext?.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun attach(instance: PreviewNotificationListenerService) {
        service = instance
    }

    fun detach(instance: PreviewNotificationListenerService) {
        if (service === instance) service = null
        synchronized(contentIntents) { contentIntents.clear(); actionIntents.clear() }
    }

    fun remember(sbn: StatusBarNotification) {
        val pendingIntent = sbn.notification.contentIntent
        synchronized(contentIntents) {
            actionIntents[sbn.key] = sbn.notification.actions.orEmpty().mapIndexedNotNull { index, action ->
                action.actionIntent?.let { index.toString() to it }
            }.toMap()
            if (pendingIntent == null) contentIntents.remove(sbn.key)
            else contentIntents[sbn.key] = pendingIntent
        }
    }

    fun forget(key: String) {
        synchronized(contentIntents) { contentIntents.remove(key); actionIntents.remove(key) }
    }

    fun activate(key: String): Boolean {
        val intent = synchronized(contentIntents) { contentIntents[key] } ?: return false
        try {
            intent.send()
            return true
        } catch (_: PendingIntent.CanceledException) {
            forget(key)
            return false
        }
    }

    fun performAction(key: String, actionId: String) {
        val intent = synchronized(contentIntents) { actionIntents[key]?.get(actionId) } ?: return
        runCatching { intent.send() }
    }
    fun dismiss(key: String) {
        service?.cancelNotification(key)
    }

    fun clearAll() {
        service?.cancelAllNotifications()
    }
}
