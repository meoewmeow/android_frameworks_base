package dev.rime.ui.models

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

data class UiNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val appIcon: ImageBitmap? = null,
    val title: String,
    val text: String,
    val timestamp: String,
    val groupKey: String? = null,
    val isConversation: Boolean = false,
    val isGroupSummary: Boolean = false,
    val expanded: Boolean = false,
    val ongoing: Boolean = false,
    val canOpen: Boolean = false,
    val progress: UiNotificationProgress? = null,
    val actions: List<UiNotificationAction> = emptyList(),
)

data class UiNotificationProgress(val value: Float, val indeterminate: Boolean = false)
data class UiNotificationAction(val id: String, val label: String)

data class UiMediaState(
    val title: String,
    val artist: String,
    val source: String,
    val artwork: ImageBitmap? = null,
    val isPlaying: Boolean,
    val progress: Float? = null,
)

data class UiConnectivityState(
    val airplaneMode: Boolean?,
    val wifiEnabled: Boolean?,
    val mobileDataEnabled: Boolean?,
    val bluetoothEnabled: Boolean?,
    val hotspotEnabled: Boolean?,
    val rotationLocked: Boolean?,
    val silentMode: Boolean?,
    val focusEnabled: Boolean?,
    val flashlightEnabled: Boolean?,
    val batteryPercent: Int,
    val brightness: Float,
    val volume: Float,
)

data class UiKeyguardState(val locked: Boolean,
    val showStatusRegion: Boolean = true,
    val showUnlockAffordance: Boolean = true,
    val showShortcuts: Boolean = true,
    val showGestureHandle: Boolean = true,
    val canCustomize: Boolean = true,
    /** Always-on display: the scene shows only the clock and date, dimmed. */
    val dozing: Boolean = false)

/**
 * [date] is the host's own short date, used when [epochMillis] is unknown. The rest feed the date
 * row's optional item; a null value hides it.
 */
data class UiClockState(val time: String, val date: String, val epochMillis: Long = 0L,
    val weather: String? = null, val weatherIcon: ImageBitmap? = null, val nextAlarm: String? = null,
    val battery: String? = null, val accent: Color? = null)

/** [image] is set only when the host draws the wallpaper itself; [blurredImage] is its softened copy. */
data class UiWallpaperState(
    val supportsDarkText: Boolean,
    val primaryColor: Color,
    val image: ImageBitmap? = null,
    val blurredImage: ImageBitmap? = null,
    val crop: NormalizedRect = NormalizedRect.Full,
)

enum class NotificationDisplayMode {
    Count,
    Stack,
    List,
}

enum class UiControlAction {
    AirplaneMode,
    Wifi,
    MobileData,
    Bluetooth,
    Hotspot,
    RotationLock,
    SilentMode,
    Focus,
    Flashlight,
    Timer,
    Calculator,
    Camera,
}

enum class LockShortcut {
    Flashlight,
    Camera,
}

data class UiSnapshot(
    val notifications: List<UiNotification>,
    val notificationDisplayMode: NotificationDisplayMode,
    val media: UiMediaState?,
    val connectivity: UiConnectivityState,
    val keyguard: UiKeyguardState,
    val clock: UiClockState,
    val wallpaper: UiWallpaperState,
    val theme: UiThemeState = UiThemeState(),
    val lockscreenContent: UiLockscreenContent = UiLockscreenContent(),
    val settings: dev.rime.ui.settings.LockscreenSettings = dev.rime.ui.settings.LockscreenSettings(),
)

/** Host-supplied layout capabilities, independent of clock controller/widget implementations. */
data class UiLockscreenContent(
    val clockId: String = "axion-wide",
    val preferredClockHeightDp: Float? = null,
    val minimumClockHeightDp: Float? = null,
    val supportsHeightResize: Boolean = true,
    val widgetHeightDp: Float = 0f,
    val depthEnabled: Boolean = false,
    /** The wallpaper subject's outline in window pixels; the clock is cut away where it overlaps. */
    val depthSubject: androidx.compose.ui.graphics.Path? = null,
    /** The date is cut around the subject too. */
    val depthIncludeDate: Boolean = true,
    /** When the subject would hide most of the digits, the clock is shown whole instead. */
    val depthKeepReadable: Boolean = true,
    /**
     * Spatial: how far the wallpaper's subject has moved, in window pixels, updated every frame
     * while the phone tilts. A State, so only the clock redraws, not the whole scene.
     */
    val depthShift: androidx.compose.runtime.State<androidx.compose.ui.geometry.Offset>? = null,
    /**
     * One of Axion's own clock faces is chosen: the host draws its real clock view in the clock's
     * slot, and the scene draws no clock of its own. [hostedClockName] labels the slot in the editor.
     */
    val hostedClock: Boolean = false,
    val hostedClockName: String? = null,
    /** Top of the under-display fingerprint sensor, from the screen top; the deck stays above it. */
    val fingerprintTopDp: Float? = null,
)
/**
 * [surface], [onSurface], [onSurfaceVariant]: the colours Android's own notifications use, from
 * the system's light or dark mode (not the wallpaper): the surface tone and the text on it.
 */
data class UiThemeState(val accent: Color = Color(0xFF246A9C), val clockColor: Color? = null,
    val surface: Color? = null, val onSurface: Color? = null, val onSurfaceVariant: Color? = null)
