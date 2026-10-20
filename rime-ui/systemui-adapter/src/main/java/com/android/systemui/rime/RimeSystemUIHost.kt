/* Copyright (C) 2026 Rime contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.rime

import android.app.Notification
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import com.android.systemui.biometrics.AuthController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.shade.CameraLauncher
import com.android.systemui.shared.clocks.ClockSettingsRepository
import com.android.systemui.shared.clocks.DepthWallpaperProvider
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.notification.collection.*
import com.android.systemui.statusbar.notification.data.repository.ActiveNotificationListRepository
import com.android.systemui.statusbar.notification.shared.ActiveBundleModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationGroupModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.ActivePipelineEntryModel
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.KeyguardStateController
import dev.rime.ui.models.*
import dev.rime.ui.settings.*
import dev.rime.ui.state.RimeUiHost
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Adapts the rendered pipeline and existing action/authentication controllers. No backend replacement. */
@SysUISingleton
class RimeSystemUIHost @Inject constructor(
    @ShadeDisplayAware private val context: Context,
    @Main private val handler: Handler,
    private val pipeline: NotifPipeline,
    private val renderedNotifications: ActiveNotificationListRepository,
    private val visibilityProvider: KeyguardNotificationVisibilityProvider,
    private val users: NotificationLockscreenUserManager,
    private val keyguardController: KeyguardStateController,
    private val activityStarter: ActivityStarter,
    private val flashlight: FlashlightController,
    private val camera: CameraLauncher,
    battery: BatteryController,
    private val guard: RimeFailureGuard,
    private val auth: dagger.Lazy<AuthController>,
    private val configuration: ConfigurationController,
) : RimeUiHost {
    private val _notifications = MutableStateFlow<List<UiNotification>>(emptyList())
    override val notifications = _notifications.asStateFlow()
    private val _display = MutableStateFlow(NotificationDisplayMode.Stack)
    override val displayMode = _display.asStateFlow()
    override fun setDisplayMode(mode: NotificationDisplayMode) { _display.value = mode }
    private val _media = MutableStateFlow<UiMediaState?>(null)
    override val media = _media.asStateFlow()
    private val _connectivity = MutableStateFlow(UiConnectivityState(null, null, null, null, null, null, null, null, null, 0, 0f, 0f))
    override val connectivity = _connectivity.asStateFlow()
    private val _keyguard = MutableStateFlow(UiKeyguardState(true))
    override val keyguard = _keyguard.asStateFlow()
    private val _clock = MutableStateFlow(UiClockState("", ""))
    override val clock = _clock.asStateFlow()
    private val _wallpaper = MutableStateFlow(UiWallpaperState(false, Color.Black))
    override val wallpaper = _wallpaper.asStateFlow()
    private val _content = MutableStateFlow(UiLockscreenContent())
    override val lockscreenContent = _content.asStateFlow()
    private val _theme = MutableStateFlow(UiThemeState())
    override val theme = _theme.asStateFlow()
    private val _settings = MutableStateFlow(LockscreenSettings())
    override val lockscreenSettings = _settings.asStateFlow()
    // Tuned on the customization page and stored as one Secure value; read-only here.
    private val _calibration = MutableStateFlow(Calibration())
    override val calibration = _calibration.asStateFlow()
    private var orderedEntries: List<NotificationEntry> = emptyList()
    private val icons = mutableMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()

    // Declared before init: Kotlin runs initializers in order, and init registers this listener.
    private var depthSubject: androidx.compose.ui.graphics.Path? = null
    // The date row's optional item. Weather is read off the main thread, at most every 10 minutes.
    private val weatherExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var weatherText: String? = null
    private var weatherIcon: androidx.compose.ui.graphics.ImageBitmap? = null
    private var weatherQueriedAt = 0L
    private var batteryLevel = -1
    private var batteryCharging = false
    private var widgetHeightDp = 0f
    /** Android's own rows show on the lock screen; Rime's deck then lists nothing. */
    private var nativeNotifications = false
    /** Always-on display: the scene shows its dimmed clock only. */
    private var dozing = false
    // An Axion clock face shown in the clock slot, and its measured height (dp) once known.
    private var hostedClock = false
    private var hostedClockId = ""
    private var hostedClockHeightDp: Float? = null
    /** Spatial subject movement in window pixels; a State so a tilt redraws only the clock. */
    private val depthShift = androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    private val depthListener = object : DepthWallpaperProvider.DepthMaskListener {
        override fun onDepthDataChanged(path: android.graphics.Path?, pathAspect: Float) {
            guard.run("depth mask") { depthSubject = path?.let { toWindow(it, pathAspect) }; publishContent() }
        }
        override fun onWallpaperZoomActiveChanged(active: Boolean) {}
        override fun onWallpaperZoomDisabledChanged(disabled: Boolean) {}
    }

    init {
      guard.run("host initialization") {
        ClockSettingsRepository.init(context)
        // Axion's wallpaper effects service finds the subject of whatever wallpaper is set.
        DepthWallpaperProvider.init(context)
        DepthWallpaperProvider.addListener(context, depthListener)
        // Android re-themes a few seconds after a new wallpaper; the scene re-reads its colours
        // then, instead of keeping the old ones until the next lock.
        configuration.addCallback(object : ConfigurationController.ConfigurationListener {
            override fun onThemeChanged() { refresh() }
            override fun onUiModeChanged() { refresh() }
        })
        WallpaperManager.getInstance(context).addOnColorsChangedListener({ _, _ -> refresh() }, handler)
        pipeline.addOnAfterRenderListListener { entries ->
            fun flatten(entry: PipelineEntry): List<NotificationEntry> = when (entry) {
                is NotificationEntry -> listOf(entry)
                is GroupEntry -> listOfNotNull(entry.representativeEntry) + entry.children
                is BundleEntry -> entry.children.flatMap(::flatten)
                else -> listOfNotNull(entry.asListEntry()?.representativeEntry)
            }
            guard.run("rendered notification callback") {
                orderedEntries = entries.flatMap(::flatten).toList()
                publishNotifications()
            }
        }
        visibilityProvider.addOnStateChangedListener { guard.run("notification visibility callback") { publishNotifications() } }
        users.addNotificationStateChangedListener { guard.run("notification policy callback") { publishNotifications() } }
        battery.addCallback(object : BatteryController.BatteryStateChangeCallback {
            override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
                guard.run("battery callback") {
                    _connectivity.value = _connectivity.value.copy(batteryPercent = level)
                    batteryLevel = level; batteryCharging = charging
                    publishClock()
                }
            }
        })
        keyguardController.addCallback(object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() { guard.run("keyguard showing callback") { synchronizeKeyguard() } }
            override fun onUnlockedChanged() { guard.run("keyguard trust callback") { synchronizeKeyguard() } }
        })
        refresh()
      }
    }

    fun refresh() { guard.run("host refresh") { refreshInternal() } }
    private fun refreshInternal() {
        // The existing coordinator has already filtered, grouped and ordered this snapshot.
        // A late-created host must replay it rather than wait for another notification post.
        val store = renderedNotifications.activeNotifications.value
        fun keys(model: ActivePipelineEntryModel): List<String> = when (model) {
            is ActiveNotificationModel -> listOf(model.key)
            is ActiveNotificationGroupModel -> listOf(model.summary.key) + model.children.map { it.key }
            is ActiveBundleModel -> model.children.flatMap(::keys)
        }
        orderedEntries = store.renderList.flatMap { key -> store[key]?.let(::keys).orEmpty() }
            .distinct().mapNotNull(pipeline::getEntry)
        synchronizeKeyguard()
        _calibration.value = Settings.Secure.getStringForUser(context.contentResolver, "rime_calibration", UserHandle.USER_CURRENT)
            ?.let(CalibrationCodec::fromJson) ?: Calibration()
        val colors = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_LOCK)
            ?: WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        val dark = colors != null && colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
        _wallpaper.value = UiWallpaperState(dark, colors?.primaryColor?.toArgb()?.let(::Color) ?: Color.Black)
        val surfaces = notificationSurfaceColors(context)
        _theme.value = UiThemeState(Color(context.getColor(android.R.color.system_accent1_600)),
            Color(context.getColor(if (dark) android.R.color.system_accent1_900 else android.R.color.system_accent1_100)),
            surface = surfaces.first, onSurface = surfaces.second, onSurfaceVariant = surfaces.third)
        val c = _settings.value.clock
        _settings.value = _settings.value.copy(clock = c.copy(
            sizeScale = ClockSettingsRepository.sizeScale.value,
            horizontalOffsetDp = ClockSettingsRepository.horizontalOffsetDp.value,
            topPaddingDp = Settings.Secure.getFloatForUser(context.contentResolver, "rime_clock_top_padding_dp", ClockSettingsRepository.topPaddingDp.value, UserHandle.USER_CURRENT),
            dateBelow = ClockSettingsRepository.isDateBelow.value,
            showDate = ClockSettingsRepository.infoDisplayMode.value != ClockSettingsRepository.INFO_DISPLAY_OFF,
            colorArgb = ClockSettingsRepository.clockColorOverride.value,
            dualTone = ClockSettingsRepository.oplusBigDualTone.value,
            translucentDigits = ClockSettingsRepository.clockBackgroundBlurEnabled.value,
            digitOpacity = ClockSettingsRepository.clockBackgroundTransparency.value / 100f,
            heightScale = Settings.Secure.getFloatForUser(context.contentResolver, "rime_clock_height_scale", 1f, UserHandle.USER_CURRENT),
            blurRadiusDp = Settings.Secure.getStringForUser(context.contentResolver, "rime_clock_blur_radius_dp", UserHandle.USER_CURRENT)?.toFloatOrNull(),
        ).let { clock -> rimeClockSettings(context, clock) }, surfaces = _settings.value.surfaces.copy(
            blurRadiusDp = ClockSettingsRepository.clockBackgroundBlurRadius.value / 100f * 48f,
            cornerRadiusDp = Settings.Secure.getFloatForUser(context.contentResolver, "rime_surface_corner_radius_dp", 28f, UserHandle.USER_CURRENT),
            opacity = Settings.Secure.getFloatForUser(context.contentResolver, "rime_surface_opacity", 0.72f, UserHandle.USER_CURRENT),
        )).validated()
        publishClock()
        publishContent()
        publishNotifications()
    }

    /** Time, date and the date row's optional item. */
    private fun publishClock() {
        val now = Date()
        val extra = _settings.value.clock.date.extra
        if (extra == DateStyle.Extra.Weather) refreshWeather() else { weatherText = null; weatherIcon = null }
        val alarm = if (extra != DateStyle.Extra.Alarm) null else
            context.getSystemService(android.app.AlarmManager::class.java)?.nextAlarmClock?.triggerTime
                ?.takeIf { it - now.time in 0..ALARM_WINDOW_MS }?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
        _clock.value = UiClockState(DateFormat.getTimeFormat(context).format(now), DateFormat.format("EEE, d MMM", now).toString(),
            epochMillis = now.time, weather = weatherText, weatherIcon = weatherIcon, nextAlarm = alarm,
            battery = batteryLevel.takeIf { extra == DateStyle.Extra.Battery && it >= 0 }?.let { if (batteryCharging) "$it% \u26A1" else "$it%" },
            accent = _theme.value.accent)
    }

    private fun refreshWeather() {
        val started = android.os.SystemClock.uptimeMillis()
        if (weatherQueriedAt != 0L && started - weatherQueriedAt < WEATHER_INTERVAL_MS) return
        weatherQueriedAt = started
        val size = (24 * context.resources.displayMetrics.density).toInt()
        weatherExecutor.execute {
            val result = runCatching {
                val client = com.android.internal.util.crdroid.OmniJawsClient.get()
                client.queryWeather(context)
                val info = client.weatherInfo ?: return@runCatching null
                val icon = client.getWeatherConditionImage(context, info.conditionCode)?.let { drawable ->
                    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                        drawable.setBounds(0, 0, size, size); drawable.draw(Canvas(bitmap))
                    }.asImageBitmap()
                }
                "${info.temp}${info.tempUnits.orEmpty()}" to icon
            }.getOrNull()
            handler.post { guard.run("weather result") {
                weatherText = result?.first; weatherIcon = result?.second
                // A failed read is retried sooner than a good one.
                if (result == null) weatherQueriedAt = android.os.SystemClock.uptimeMillis() - WEATHER_INTERVAL_MS + WEATHER_RETRY_MS
                val c = _clock.value
                _clock.value = c.copy(weather = weatherText, weatherIcon = weatherIcon)
            } }
        }
    }

    /** The mask is normalised to 0..10000 over the wallpaper, which the lock screen centre-crops to the display. */
    private fun toWindow(path: android.graphics.Path, wallAspect: Float): androidx.compose.ui.graphics.Path {
        val metrics = android.util.DisplayMetrics().also { context.display?.getRealMetrics(it) }
        val w = metrics.widthPixels.toFloat(); val h = metrics.heightPixels.toFloat()
        val screenAspect = w / h
        val visibleW = if (wallAspect > screenAspect) screenAspect / wallAspect * 10000f else 10000f
        val visibleH = if (wallAspect > screenAspect) 10000f else wallAspect / screenAspect * 10000f
        val matrix = android.graphics.Matrix().apply {
            setTranslate(-(10000f - visibleW) / 2f, -(10000f - visibleH) / 2f)
            postScale(w / visibleW, h / visibleH)
        }
        return android.graphics.Path(path).apply { transform(matrix) }.asComposePath()
    }

    fun setHostedClock(hosted: Boolean, clockId: String, heightDp: Float?) {
        val height = heightDp?.takeIf { it > 1f } ?: hostedClockHeightDp?.takeIf { hosted && clockId == hostedClockId }
        if (hosted == hostedClock && clockId == hostedClockId && height == hostedClockHeightDp) return
        hostedClock = hosted; hostedClockId = clockId; hostedClockHeightDp = if (hosted) height else null
        publishContent()
    }

    fun setDozing(value: Boolean) {
        if (value == dozing) return
        dozing = value
        _keyguard.value = _keyguard.value.copy(dozing = value)
    }

    fun setNativeNotifications(native: Boolean) {
        if (native == nativeNotifications) return
        nativeNotifications = native
        publishNotifications()
    }

    fun setDepthShift(x: Float, y: Float) { depthShift.value = androidx.compose.ui.geometry.Offset(x, y) }

    /** The native widget area's height, reported by the controller that places it. */
    fun setWidgetHeightDp(dp: Float) {
        if (dp == widgetHeightDp) return
        widgetHeightDp = dp
        publishContent()
    }

    private fun publishContent() {
        _content.value = UiLockscreenContent(clockId = ClockSettingsRepository.clockId.value, fingerprintTopDp = fingerprintTopDp(),
            hostedClock = hostedClock, hostedClockName = "Axion " + hostedClockId.removePrefix("OPLUS_").lowercase().replace('_', ' '),
            preferredClockHeightDp = hostedClockHeightDp?.takeIf { hostedClock }, minimumClockHeightDp = hostedClockHeightDp?.takeIf { hostedClock },
            supportsHeightResize = !hostedClock,
            depthEnabled = depthSubject != null, depthSubject = depthSubject, widgetHeightDp = widgetHeightDp, depthShift = depthShift,
            depthIncludeDate = Settings.Secure.getIntForUser(context.contentResolver, "rime_depth_date", 1, UserHandle.USER_CURRENT) == 1,
            depthKeepReadable = Settings.Secure.getIntForUser(context.contentResolver, "rime_depth_readable", 1, UserHandle.USER_CURRENT) == 1)
    }

    /** The under-display sensor's top edge in window dp, or null on phones without one. */
    private fun fingerprintTopDp(): Float? {
        val center = auth.get().udfpsLocation ?: return null
        val radius = auth.get().udfpsRadius.takeIf { it > 0f } ?: return null
        return (center.y - radius) / context.resources.displayMetrics.density
    }

    private fun synchronizeKeyguard() {
        // Sanitize models before a newly locked surface can become visible.
        publishNotifications()
        _keyguard.value = UiKeyguardState(keyguardController.isShowing,
            showStatusRegion = false, showUnlockAffordance = false, showShortcuts = false, showGestureHandle = false, canCustomize = keyguardController.canDismissLockScreen(), dozing = dozing)
    }

    private fun publishNotifications() { guard.run("notification mapping") { publishNotificationsInternal() } }
    private fun publishNotificationsInternal() {
        if (nativeNotifications) { _notifications.value = emptyList(); return }
        _notifications.value = orderedEntries.filterNot { it.isRowDismissed || visibilityProvider.shouldHideNotification(it) }.map { entry ->
            val sbn = entry.sbn
            val n = sbn.notification
            // Do not expose private text, actions, progress or group contents behind the keyguard.
            val redact = users.getRedactionType(entry) != NotificationLockscreenUserManager.REDACTION_TYPE_NONE || entry.isSensitive.value ||
                (keyguardController.isShowing && !keyguardController.canDismissLockScreen() &&
                    n.visibility != Notification.VISIBILITY_PUBLIC && !users.userAllowsPrivateNotificationsInPublic(sbn.userId))
            val appName = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            val icon = icons.getOrPut(sbn.packageName) {
                val drawable = runCatching { context.packageManager.getApplicationIcon(sbn.packageName) }.getOrNull()
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                drawable?.setBounds(0, 0, 64, 64); drawable?.draw(Canvas(bitmap)); bitmap.asImageBitmap()
            }
            UiNotification(key = entry.key, packageName = sbn.packageName, appName = appName, appIcon = icon,
                title = if (redact) appName else n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = if (redact) "Notification content hidden" else (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty(),
                timestamp = DateFormat.getTimeFormat(context).format(Date(sbn.postTime)),
                groupKey = sbn.groupKey.takeIf { sbn.isGroup }, isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                isConversation = entry.ranking.isConversation,
                ongoing = entry.row?.canViewBeDismissed() != true, canOpen = n.contentIntent != null,
                progress = if (!redact && n.extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0) UiNotificationProgress(
                    n.extras.getInt(Notification.EXTRA_PROGRESS).toFloat() / n.extras.getInt(Notification.EXTRA_PROGRESS_MAX),
                    n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) else null)
        }
    }

    override fun activateNotification(key: String) { guard.run("notification click") { pipeline.getEntry(key)?.row?.performClick() } }
    override fun dismissNotification(key: String) { guard.run("notification dismiss") {
        pipeline.getEntry(key)?.row?.let { if (it.canViewBeDismissed()) it.performDismiss(false) }
        publishNotifications()
    } }
    override fun clearNotifications() { notifications.value.filterNot { it.ongoing }.forEach { dismissNotification(it.key) } }
    override fun manageNotification(key: String) { guard.run("notification settings") {
        pipeline.getEntry(key)?.let { entry -> activityStarter.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, entry.sbn.packageName), true) }
    } }
    override fun setNotificationExpanded(key: String, expanded: Boolean) { guard.run("notification expand") { pipeline.getEntry(key)?.row?.setUserExpanded(expanded) } }
    override fun performNotificationAction(key: String, actionId: String) { /* No arbitrary RemoteViews/remote-input actions are synthesized. */ }
    override fun requestUnlock() { guard.run("unlock request") { activityStarter.dismissKeyguardThenExecute({ false }, null, false) } }
    override fun setLocked(locked: Boolean) { /* Real keyguard state is never simulated. */ }
    override fun requestLockShortcut(shortcut: LockShortcut) {
        when (shortcut) {
            LockShortcut.Flashlight -> flashlight.setFlashlight(!flashlight.isEnabled)
            LockShortcut.Camera -> camera.launchCamera(android.app.StatusBarManager.CAMERA_LAUNCH_SOURCE_QUICK_AFFORDANCE, false)
        }
    }
    fun authorizeEdit(onAuthorized: () -> Unit) { guard.run("edit authorization") {
        if (keyguardController.canDismissLockScreen()) onAuthorized() else openNativeEditor()
    } }
    fun openNativeEditor() { guard.run("open native editor") { activityStarter.startActivity(Intent().setClassName("com.android.axion.themepicker", "com.android.axion.themepicker.ui.MainActivity"), true) } }
    /** The customization page lives in AxThemePicker and opens after unlock; nothing is edited on the keyguard. */
    override fun requestWallpaperGallery() { guard.run("open customization page") {
        activityStarter.startActivity(Intent().setClassName("com.android.axion.themepicker",
            "com.android.axion.themepicker.ui.rime.RimeCustomizationActivity")
            .putExtra("from_lockscreen", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), true)
    } }
    override fun updateLockscreenSettings(settings: LockscreenSettings) { guard.run("settings write") {
        if (keyguardController.isShowing && !keyguardController.canDismissLockScreen()) return@run
        val s = settings.validated()
        _settings.value = s
        ClockSettingsRepository.writeSizeAndPosition(context, s.clock.sizeScale, s.clock.horizontalOffsetDp, s.clock.topPaddingDp)
        AxionSettingsCodec.encode(s).filterKeys { it !in setOf("ax_clock_size_scale", "ax_clock_horizontal_offset_dp", "ax_clock_top_padding_dp") }.forEach { (key, value) ->
            Settings.Secure.putStringForUser(context.contentResolver, key, value, UserHandle.USER_CURRENT)
        }
        Settings.Secure.putStringForUser(context.contentResolver, "rime_clock_top_padding_dp",
            if (s.clock.topPaddingDp < ClockSettingsRepository.TOP_PADDING_MIN_DP) s.clock.topPaddingDp.toString() else null, UserHandle.USER_CURRENT)
        Settings.Secure.putFloatForUser(context.contentResolver, "rime_surface_corner_radius_dp", s.surfaces.cornerRadiusDp, UserHandle.USER_CURRENT)
        Settings.Secure.putFloatForUser(context.contentResolver, "rime_surface_opacity", s.surfaces.opacity, UserHandle.USER_CURRENT)
        Settings.Secure.putFloatForUser(context.contentResolver, "rime_clock_height_scale", s.clock.heightScale, UserHandle.USER_CURRENT)
        Settings.Secure.putStringForUser(context.contentResolver, "rime_clock_blur_radius_dp", s.clock.blurRadiusDp?.toString(), UserHandle.USER_CURRENT)
        publishClock()
    }
    }
    override fun requestControl(action: UiControlAction) { }
    override fun setBrightness(level: Float) { }
    override fun setVolume(level: Float) { }
    override fun setMediaPlaying(playing: Boolean) { }
    override fun skipToPrevious() { }
    override fun skipToNext() { }
    override fun seekTo(progress: Float) { }
}

/** The colours Android's own notifications use for the current light or dark mode. */
internal fun notificationSurfaceColors(context: android.content.Context): Triple<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> {
    val night = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    fun c(id: Int) = androidx.compose.ui.graphics.Color(context.getColor(id))
    return Triple(
        c(if (night) android.R.color.system_surface_dim_dark else android.R.color.system_surface_bright_light),
        c(if (night) android.R.color.system_on_surface_dark else android.R.color.system_on_surface_light),
        c(if (night) android.R.color.system_on_surface_variant_dark else android.R.color.system_on_surface_variant_light))
}

/** Rime's own clock keys (face, layout, weight, date row) over [clock]. Shared by every reader. */
internal fun rimeClockSettings(context: android.content.Context, clock: dev.rime.ui.settings.ClockAppearance): dev.rime.ui.settings.ClockAppearance {
    val values = AxionSettingsCodec.RIME_KEYS.mapNotNull { key ->
        Settings.Secure.getStringForUser(context.contentResolver, key, UserHandle.USER_CURRENT)?.let { key to it }
    }.toMap()
    return AxionSettingsCodec.decode(values, LockscreenSettings(clock = clock)).clock
}

private const val WEATHER_INTERVAL_MS = 10 * 60_000L
private const val WEATHER_RETRY_MS = 60_000L
private const val ALARM_WINDOW_MS = 24 * 60 * 60_000L
