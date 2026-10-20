package dev.rime.ui.state

import kotlinx.coroutines.flow.StateFlow
import dev.rime.ui.settings.LockscreenSettingsSource
import dev.rime.ui.models.LockShortcut
import dev.rime.ui.models.NotificationDisplayMode
import dev.rime.ui.models.UiClockState
import dev.rime.ui.models.UiConnectivityState
import dev.rime.ui.models.UiControlAction
import dev.rime.ui.models.UiKeyguardState
import dev.rime.ui.models.UiMediaState
import dev.rime.ui.models.UiNotification
import dev.rime.ui.models.UiWallpaperState

/** Host-ranked notification data without Android or SystemUI notification classes. */
interface NotificationSource {
    val notifications: StateFlow<List<UiNotification>>
    val displayMode: StateFlow<NotificationDisplayMode>

    fun setDisplayMode(mode: NotificationDisplayMode)
    fun activateNotification(key: String)
    fun setNotificationExpanded(key: String, expanded: Boolean)
    fun dismissNotification(key: String)
    fun manageNotification(key: String)
    fun performNotificationAction(key: String, actionId: String)
    fun clearNotifications()
}

/** Reads control state and forwards user requests to the host's existing handlers. */
interface SystemControls {
    val connectivity: StateFlow<UiConnectivityState>

    fun requestControl(action: UiControlAction)
    fun setBrightness(level: Float)
    fun setVolume(level: Float)
}

interface KeyguardState {
    val keyguard: StateFlow<UiKeyguardState>
}

interface MediaState {
    val media: StateFlow<UiMediaState?>

    fun setMediaPlaying(playing: Boolean)
    fun skipToPrevious()
    fun skipToNext()
    fun seekTo(progress: Float)
}

interface ClockState {
    val clock: StateFlow<UiClockState>
}

interface WallpaperState {
    val wallpaper: StateFlow<UiWallpaperState>
}

interface UserActions {
    fun requestUnlock()
    fun setLocked(locked: Boolean)
    fun requestLockShortcut(shortcut: LockShortcut)
}

interface LockscreenCustomization {
    val lockscreenContent: StateFlow<dev.rime.ui.models.UiLockscreenContent>
    val theme: StateFlow<dev.rime.ui.models.UiThemeState>
    /** Opens the host's customization page. SystemUI unlocks first; the keyguard itself edits nothing. */
    fun requestWallpaperGallery()
    /** Opens Axion's own clock picker, where its clock faces are chosen. Hosts without one ignore it. */
    fun openClockPicker() {}
    /** Switches from an Axion clock face back to Rime's own glass clock. */
    fun useRimeClock() {}
}

interface RimeUiHost :
    LockscreenCustomization,
    NotificationSource,
    SystemControls,
    KeyguardState,
    MediaState,
    ClockState,
    WallpaperState,
    UserActions,
    LockscreenSettingsSource,
    dev.rime.ui.settings.CalibrationSource
