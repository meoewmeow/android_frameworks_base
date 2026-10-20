package dev.rime.preview.mock

import dev.rime.ui.models.NotificationDisplayMode
import kotlinx.coroutines.flow.StateFlow

enum class ForegroundOverride {
    Auto,
    Light,
    Dark,
}

data class PreviewOptions(
    val notificationCount: Int = 5,
    val groupedNotifications: Boolean = true,
    val conversations: Boolean = true,
    val longNotificationText: Boolean = false,
    val notificationDisplayMode: NotificationDisplayMode = NotificationDisplayMode.Stack,
    val mediaPresent: Boolean = false,
    val mediaPlaying: Boolean = true,
    val locked: Boolean = true,
    val clockOverride: Boolean = false,
    val time: String = "9:41",
    val date: String = "Tuesday, September 24",
    val foregroundOverride: ForegroundOverride = ForegroundOverride.Auto,
    val previewWidthDp: Int = 0,
    val animationSpeed: Float = 1f,
    val mockDeviceState: Boolean = false,
    val mockBatteryPercent: Int = 68,
    val mockWifi: Boolean = true,
    val mockBluetooth: Boolean = true,
    val mockMobileData: Boolean = true,
    val mockAirplaneMode: Boolean = false,
    val mockHotspot: Boolean = false,
    val mockRotationLocked: Boolean = false,
    val mockSilentMode: Boolean = false,
    val mockFocus: Boolean = false,
    val mockFlashlight: Boolean = false,
    val mockBrightness: Float = 0.72f,
    val mockVolume: Float = 0.55f,
    val forceTranslucentFallback: Boolean = false,
)

interface PreviewSettingsController {
    val options: StateFlow<PreviewOptions>
    val notificationListenerConnected: StateFlow<Boolean>

    fun addMockNotification()
    fun advanceMockProgress()
    fun resetMockNotifications()
    fun updateOptions(update: (PreviewOptions) -> PreviewOptions)
    fun openNotificationAccessSettings()
}
