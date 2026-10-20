package dev.rime.preview.mock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import dev.rime.ui.models.LockscreenPreset
import dev.rime.ui.models.PresetCollection
import dev.rime.ui.models.PresetWallpaper
import dev.rime.ui.customization.PresetFileStore
import dev.rime.ui.customization.PresetLibraryController
import dev.rime.ui.state.PresetLibrary
import java.util.concurrent.Executors
import androidx.compose.ui.graphics.asImageBitmap
import dev.rime.ui.settings.LockscreenSettings
import dev.rime.ui.models.UiNotificationProgress
import dev.rime.ui.models.UiNotificationAction
import dev.rime.preview.notifications.NotificationListenerAccess
import dev.rime.ui.models.LockShortcut
import dev.rime.ui.models.NotificationDisplayMode
import dev.rime.ui.models.UiClockState
import dev.rime.ui.models.UiConnectivityState
import dev.rime.ui.models.UiControlAction
import dev.rime.ui.models.UiKeyguardState
import dev.rime.ui.models.UiMediaState
import dev.rime.ui.models.UiNotification
import dev.rime.ui.models.UiWallpaperState
import dev.rime.ui.state.RimeUiHost
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Standalone preview host: mock notifications, real read-only device state by default. */
object PreviewStore : RimeUiHost, PreviewSettingsController, PresetLibrary {
    private val _lockscreenContent = MutableStateFlow(dev.rime.ui.models.UiLockscreenContent())
    override val lockscreenContent = _lockscreenContent.asStateFlow()
    private val _theme = MutableStateFlow(dev.rime.ui.models.UiThemeState())
    override val theme = _theme.asStateFlow()
    // Created in initialize(); the activity initializes the store before it shows anything.
    private lateinit var library: PresetLibraryController
    override val presets get() = library.presets
    override val applyingPresetId get() = library.applyingPresetId
    override val importing get() = library.importing
    override val draft get() = library.draft
    private val _galleryRequested = MutableStateFlow(false)
    /** Set when the customization page should be shown; the activity clears it once it has. */
    val galleryRequested = _galleryRequested.asStateFlow()
    fun galleryShown() { _galleryRequested.value = false }
    private var pickPhoto: (() -> Unit)? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var presetStore: PresetFileStore? = null
    // Pixels of the active preset's own photo; null while the device wallpaper is showing.
    private var activeImage: ImageBitmap? = null
    private var activeSoftened: ImageBitmap? = null
    // Decoded on the worker while a preset is applied, taken over once it has become active.
    @Volatile private var loaded: Triple<String, ImageBitmap?, ImageBitmap?>? = null

    override fun requestWallpaperGallery() {
        // The pages show stored presets, so the active one has to hold what is on screen now.
        library.syncActiveAppearance()
        _galleryRequested.value = true
    }
    override fun applyPreset(id: String) = library.applyPreset(id)
    override fun savePreset(preset: LockscreenPreset) = library.savePreset(preset)
    override fun discardDraft() = library.discardDraft()
    override fun deletePreset(id: String) = library.deletePreset(id)
    override fun addPhotoPreset() = library.addPhotoPreset()
    override fun addCurrentWallpaperPreset() = library.addCurrentWallpaperPreset()
    /** Copies the chosen photo into the app and offers it as a draft. */
    fun importPhoto(uri: Uri) = library.importPhoto(uri)
    override suspend fun wallpaperImage(preset: LockscreenPreset) = library.wallpaperImage(preset)
    override suspend fun blurredWallpaper(preset: LockscreenPreset) = library.blurredWallpaper(preset)
    fun bindPhotoPicker(onPick: () -> Unit) { pickPhoto = onPick }

    /** Assets are checked first: a preset whose photo cannot be read leaves the active one alone. */
    private fun loadPreset(store: PresetFileStore, preset: LockscreenPreset): Boolean {
        val image = store.wallpaper(preset)
        loaded = Triple(preset.id, image, store.softened(preset))
        return preset.wallpaper.assetId == null || image != null
    }
    private fun presetActivated(preset: LockscreenPreset?) {
        val images = loaded?.takeIf { it.first == preset?.id }
        activeImage = images?.second; activeSoftened = images?.third
        if (preset != null) updateLockscreenSettings(_lockscreenSettings.value.copy(clock = preset.clock, surfaces = preset.surfaces))
        publishWallpaper()
    }
    private val _lockscreenSettings = MutableStateFlow(LockscreenSettings())
    override val lockscreenSettings = _lockscreenSettings.asStateFlow()
    private var appearanceStore: PreviewAppearanceStore? = null
    private var nextMockId = 100
    override fun updateLockscreenSettings(settings: LockscreenSettings) {
        val validated = settings.validated()
        _lockscreenSettings.value = validated
        appearanceStore?.write(validated)
    }
    private val _calibration = MutableStateFlow(dev.rime.ui.settings.Calibration())
    override val calibration = _calibration.asStateFlow()
    private var calibrationStore: PreviewCalibrationStore? = null
    val calibrationTransfer: dev.rime.ui.settings.CalibrationTransfer? get() = calibrationStore
    override fun previewCalibration(candidate: dev.rime.ui.settings.Calibration) { _calibration.value = candidate.validated() }
    override fun saveCalibration(candidate: dev.rime.ui.settings.Calibration) {
        previewCalibration(candidate)
        calibrationStore?.write(_calibration.value)
    }
    private val _options = MutableStateFlow(PreviewOptions())
    override val options: StateFlow<PreviewOptions> = _options.asStateFlow()

    private val _notificationListenerConnected = MutableStateFlow(false)
    override val notificationListenerConnected: StateFlow<Boolean> =
        _notificationListenerConnected.asStateFlow()

    private val _notifications = MutableStateFlow<List<UiNotification>>(emptyList())
    override val notifications: StateFlow<List<UiNotification>> = _notifications.asStateFlow()

    private val _displayMode = MutableStateFlow(NotificationDisplayMode.Stack)
    override val displayMode: StateFlow<NotificationDisplayMode> = _displayMode.asStateFlow()

    private val _media = MutableStateFlow<UiMediaState?>(null)
    override val media: StateFlow<UiMediaState?> = _media.asStateFlow()

    private val _connectivity = MutableStateFlow(
        UiConnectivityState(
            airplaneMode = null,
            wifiEnabled = null,
            mobileDataEnabled = null,
            bluetoothEnabled = null,
            hotspotEnabled = null,
            rotationLocked = null,
            silentMode = null,
            focusEnabled = null,
            flashlightEnabled = null,
            batteryPercent = 50,
            brightness = 0.5f,
            volume = 0.5f,
        ),
    )
    override val connectivity: StateFlow<UiConnectivityState> = _connectivity.asStateFlow()

    private val _keyguard = MutableStateFlow(UiKeyguardState(locked = true))
    override val keyguard: StateFlow<UiKeyguardState> = _keyguard.asStateFlow()

    private val _clock = MutableStateFlow(currentClock())
    override val clock: StateFlow<UiClockState> = _clock.asStateFlow()

    private val _wallpaper = MutableStateFlow(
        UiWallpaperState(
            supportsDarkText = false,
            primaryColor = androidx.compose.ui.graphics.Color(0xFF374052),
        ),
    )
    override val wallpaper: StateFlow<UiWallpaperState> = _wallpaper.asStateFlow()
    private var systemWallpaper = _wallpaper.value

    private var appContext: Context? = null
    private var deviceStateReader: PreviewDeviceStateReader? = null
    private var realConnectivity = _connectivity.value
    private var realNotifications: List<UiNotification> = emptyList()
    private var mockNotifications: List<UiNotification> = emptyList()
    private val expansionOverrides = mutableMapOf<String, Boolean>()
    private var controlRequest: ((UiControlAction) -> Unit)? = null
    private var previewBrightnessRequest: ((Float) -> Unit)? = null
    private var volumeRequest: ((Float) -> Unit)? = null
    private var trackIndex = 0

    fun initialize(context: Context) {
        appContext = context.applicationContext
        appearanceStore = PreviewAppearanceStore(context)
        _lockscreenSettings.value = appearanceStore!!.read()
        calibrationStore = PreviewCalibrationStore(context)
        _calibration.value = calibrationStore!!.read()
        if (presetStore == null) {
            val app = context.applicationContext
            val store = PresetFileStore(app, java.io.File(app.filesDir, "presets"))
            presetStore = store
            library = PresetLibraryController(store, worker, main,
                appearance = { _lockscreenSettings.value.let { it.clock to it.surfaces } },
                deviceWallpaper = { PresetWallpaper(supportsDarkText = systemWallpaper.supportsDarkText, primaryColorArgb = systemWallpaper.primaryColor.toArgb()) },
                pickPhoto = { pickPhoto?.invoke() },
                apply = { loadPreset(store, it) },
                onActiveChanged = ::presetActivated)
            library.load { importLegacyProfiles(app, store) }
            // The active photo is decoded off the main thread; until then the device wallpaper shows.
            library.presets.value.active?.takeIf { it.wallpaper.assetId != null }?.let { active ->
                worker.execute {
                    val image = store.wallpaper(active); val softened = store.softened(active)
                    main.post { if (library.presets.value.activeId == active.id) { activeImage = image; activeSoftened = softened; publishWallpaper() } }
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) _theme.value = dev.rime.ui.models.UiThemeState(
            androidx.compose.ui.graphics.Color(context.getColor(android.R.color.system_accent1_600)),
            surface = notificationSurfaceColors(context).first, onSurface = notificationSurfaceColors(context).second, onSurfaceVariant = notificationSurfaceColors(context).third)
        mockNotifications = makeMockNotifications(_options.value)
        publishNotifications()
        deviceStateReader = PreviewDeviceStateReader(context)
        refreshDeviceState()
    }

    fun bindControlHandlers(
        onRequest: (UiControlAction) -> Unit,
        onBrightness: (Float) -> Unit,
        onVolume: (Float) -> Unit,
    ) {
        controlRequest = onRequest
        previewBrightnessRequest = onBrightness
        volumeRequest = onVolume
    }

    fun refreshDeviceState() {
        realConnectivity = deviceStateReader?.read() ?: realConnectivity
        publishConnectivity()
    }

    fun refreshClock() {
        if (!_options.value.clockOverride) {
            _clock.value = currentClock()
        }
    }

    /** The device wallpaper's colors changed. Presets that follow it take the new values. */
    fun updateSystemWallpaper(state: UiWallpaperState) {
        if (presetStore != null) library.followDeviceWallpaper(PresetWallpaper(supportsDarkText = state.supportsDarkText, primaryColorArgb = state.primaryColor.toArgb()))
        systemWallpaper = state
        appContext?.let { context ->
            if (android.os.Build.VERSION.SDK_INT >= 31) _theme.value = dev.rime.ui.models.UiThemeState(
                androidx.compose.ui.graphics.Color(context.getColor(android.R.color.system_accent1_600)),
                androidx.compose.ui.graphics.Color(context.getColor(if (state.supportsDarkText) android.R.color.system_accent1_900 else android.R.color.system_accent1_100)),
                surface = notificationSurfaceColors(context).first, onSurface = notificationSurfaceColors(context).second, onSurfaceVariant = notificationSurfaceColors(context).third)
        }
        publishWallpaper()
    }

    override fun updateOptions(update: (PreviewOptions) -> PreviewOptions) {
        val old = _options.value
        val requested = update(old)
        val next = requested.copy(
            notificationCount = requested.notificationCount.coerceIn(0, 20),
            mockBatteryPercent = requested.mockBatteryPercent.coerceIn(0, 100),
            previewWidthDp = requested.previewWidthDp.coerceIn(0, 480),
            animationSpeed = requested.animationSpeed.coerceIn(0f, 2f),
            mockBrightness = requested.mockBrightness.coerceIn(0f, 1f),
            mockVolume = requested.mockVolume.coerceIn(0f, 1f),
        )
        _options.value = next
        _displayMode.value = next.notificationDisplayMode
        _keyguard.value = UiKeyguardState(next.locked)
        _clock.value = if (next.clockOverride) {
            UiClockState(next.time, next.date)
        } else {
            currentClock()
        }
        publishWallpaper()
        if (next.mediaPresent) {
            val track = TRACKS[trackIndex % TRACKS.size]
            _media.value = UiMediaState(
                title = track.first,
                artist = track.second,
                source = "Music",
                isPlaying = next.mediaPlaying,
                progress = 0.38f,
            )
        } else {
            _media.value = null
        }

        val notificationInputsChanged =
            old.notificationCount != next.notificationCount ||
                old.groupedNotifications != next.groupedNotifications ||
                old.conversations != next.conversations ||
                old.longNotificationText != next.longNotificationText
        if (notificationInputsChanged) {
            mockNotifications = makeMockNotifications(next)
            publishNotifications()
        }
        publishConnectivity()
    }

    private fun makeMockNotifications(options: PreviewOptions): List<UiNotification> =
        (0 until options.notificationCount).map { index ->
            val sample = SAMPLES[index % SAMPLES.size]
            val group = if (!options.groupedNotifications) null else "app"
            UiNotification(
                key = "mock-" + index,
                packageName = sample.packageName,
                appName = sample.appName,
                appIcon = mockIcon(index),
                title = sample.title,
                progress = if (index == 0) UiNotificationProgress(0.62f) else null,
                actions = listOf(UiNotificationAction("open", "Open"), UiNotificationAction("dismiss", "Dismiss")),
                text = if (options.longNotificationText) LONG_BODY else sample.text,
                timestamp = sample.timestamp,
                groupKey = group?.let { sample.packageName + ":" + it },
                isConversation = options.conversations && sample.isConversation,
            )
        }

    private fun mockIcon(index: Int): androidx.compose.ui.graphics.ImageBitmap? {
        val context = appContext ?: return null
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(listOf(0xFFE97B38.toInt(), 0xFF426E75.toInt(), 0xFF5D6591.toInt())[index % 3])
        val icon = context.getDrawable(if (index == 0) android.R.drawable.ic_menu_send else android.R.drawable.ic_dialog_email)?.mutate()
        icon?.setTint(android.graphics.Color.WHITE)
        icon?.setBounds(16, 16, 64, 64); icon?.draw(canvas)
        return bitmap.asImageBitmap()
    }
    override fun addMockNotification() {
        val item = makeMockNotifications(_options.value.copy(notificationCount = 1)).first().copy(
            key = "inserted-${nextMockId++}", appName = "Messages", title = "New message", text = "Inserted while the deck is open.", progress = null, groupKey = null)
        mockNotifications = listOf(item) + mockNotifications
        publishNotifications()
    }
    override fun advanceMockProgress() {
        mockNotifications = mockNotifications.map { n -> n.copy(progress = n.progress?.let { p -> p.copy(value = (p.value + 0.15f).let { if (it > 1f) 0f else it }) }) }
        publishNotifications()
    }
    override fun resetMockNotifications() {
        expansionOverrides.clear()
        mockNotifications = makeMockNotifications(_options.value)
        publishNotifications()
    }
    override fun manageNotification(key: String) {
        val item = _notifications.value.firstOrNull { it.key == key } ?: return
        if (!_notificationListenerConnected.value) setNotificationExpanded(key, true)
        else appContext?.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, item.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun performNotificationAction(key: String, actionId: String) {
        if (_notificationListenerConnected.value) NotificationListenerAccess.performAction(key, actionId)
        else if (actionId == "dismiss") dismissNotification(key) else activateNotification(key)
    }
    private fun publishNotifications() {
        val source = if (_notificationListenerConnected.value) realNotifications else mockNotifications
        _notifications.value = source.map { item ->
            item.copy(expanded = expansionOverrides[item.key] ?: item.expanded)
        }
    }

    private fun publishConnectivity() {
        val options = _options.value
        _connectivity.value = if (!options.mockDeviceState) {
            realConnectivity
        } else {
            realConnectivity.copy(
                airplaneMode = options.mockAirplaneMode,
                wifiEnabled = options.mockWifi,
                mobileDataEnabled = options.mockMobileData,
                bluetoothEnabled = options.mockBluetooth,
                hotspotEnabled = options.mockHotspot,
                rotationLocked = options.mockRotationLocked,
                silentMode = options.mockSilentMode,
                focusEnabled = options.mockFocus,
                flashlightEnabled = options.mockFlashlight,
                batteryPercent = options.mockBatteryPercent,
                brightness = options.mockBrightness,
                volume = options.mockVolume,
            )
        }
    }

    private fun applyForegroundOverride(
        state: UiWallpaperState,
        override: ForegroundOverride,
    ): UiWallpaperState = when (override) {
        ForegroundOverride.Auto -> state
        ForegroundOverride.Light -> state.copy(supportsDarkText = false)
        ForegroundOverride.Dark -> state.copy(supportsDarkText = true)
    }

    private fun publishWallpaper() {
        val active = if (presetStore != null) library.presets.value.active?.wallpaper else null
        val image = activeImage
        val shown = if (active?.assetId != null && image != null) UiWallpaperState(active.supportsDarkText,
            androidx.compose.ui.graphics.Color(active.primaryColorArgb), image, activeSoftened, active.crop) else systemWallpaper
        _wallpaper.value = applyForegroundOverride(shown, _options.value.foregroundOverride)
    }

    fun setRealNotificationSourceActive(active: Boolean, items: List<UiNotification>) {
        _notificationListenerConnected.value = active
        realNotifications = items
        publishNotifications()
    }

    fun replaceRealNotifications(items: List<UiNotification>) {
        realNotifications = items
        if (_notificationListenerConnected.value) publishNotifications()
    }

    fun realNotificationSnapshot(): List<UiNotification> = realNotifications

    fun removeRealNotification(key: String) {
        realNotifications = realNotifications.filterNot { it.key == key }
        if (_notificationListenerConnected.value) publishNotifications()
    }

    override fun setDisplayMode(mode: NotificationDisplayMode) {
        updateOptions { it.copy(notificationDisplayMode = mode) }
    }

    override fun activateNotification(key: String) {
        if (_notificationListenerConnected.value && NotificationListenerAccess.activate(key)) return
        val expanded = _notifications.value.firstOrNull { it.key == key }?.expanded == true
        setNotificationExpanded(key, !expanded)
    }

    override fun setNotificationExpanded(key: String, expanded: Boolean) {
        expansionOverrides[key] = expanded
        publishNotifications()
    }

    override fun dismissNotification(key: String) {
        if (_notificationListenerConnected.value) {
            NotificationListenerAccess.dismiss(key)
        } else {
            mockNotifications = mockNotifications.filterNot { it.key == key }
            publishNotifications()
        }
    }

    override fun clearNotifications() {
        if (_notificationListenerConnected.value) {
            NotificationListenerAccess.clearAll()
        } else {
            mockNotifications = emptyList()
            publishNotifications()
        }
    }

    override fun requestControl(action: UiControlAction) {
        if (!_options.value.mockDeviceState || action !in PREVIEW_TOGGLE_ACTIONS) {
            controlRequest?.invoke(action)
            return
        }
        updateOptions { options ->
            when (action) {
                UiControlAction.AirplaneMode -> options.copy(mockAirplaneMode = !options.mockAirplaneMode)
                UiControlAction.Wifi -> options.copy(mockWifi = !options.mockWifi)
                UiControlAction.MobileData -> options.copy(mockMobileData = !options.mockMobileData)
                UiControlAction.Bluetooth -> options.copy(mockBluetooth = !options.mockBluetooth)
                UiControlAction.Hotspot -> options.copy(mockHotspot = !options.mockHotspot)
                UiControlAction.RotationLock -> options.copy(mockRotationLocked = !options.mockRotationLocked)
                UiControlAction.SilentMode -> options.copy(mockSilentMode = !options.mockSilentMode)
                UiControlAction.Focus -> options.copy(mockFocus = !options.mockFocus)
                UiControlAction.Flashlight -> options.copy(mockFlashlight = !options.mockFlashlight)
                else -> options
            }
        }
    }

    override fun setBrightness(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        if (_options.value.mockDeviceState) {
            updateOptions { it.copy(mockBrightness = clamped) }
        } else {
            realConnectivity = realConnectivity.copy(brightness = clamped)
            _connectivity.value = realConnectivity
            previewBrightnessRequest?.invoke(clamped)
        }
    }

    override fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        if (_options.value.mockDeviceState) {
            updateOptions { it.copy(mockVolume = clamped) }
        } else {
            volumeRequest?.invoke(clamped)
            refreshDeviceState()
        }
    }

    override fun setMediaPlaying(playing: Boolean) {
        _media.value = _media.value?.copy(isPlaying = playing)
        _options.value = _options.value.copy(mediaPlaying = playing)
    }

    override fun skipToNext() {
        trackIndex = (trackIndex + 1) % TRACKS.size
        val track = TRACKS[trackIndex]
        _media.value = _media.value?.copy(title = track.first, artist = track.second)
    }

    override fun skipToPrevious() {
        trackIndex = (trackIndex - 1 + TRACKS.size) % TRACKS.size
        val track = TRACKS[trackIndex]
        _media.value = _media.value?.copy(title = track.first, artist = track.second)
    }

    override fun seekTo(progress: Float) {
        _media.value = _media.value?.copy(progress = progress.coerceIn(0f, 1f))
    }

    fun setFlashlightState(enabled: Boolean?) {
        realConnectivity = realConnectivity.copy(flashlightEnabled = enabled)
        publishConnectivity()
    }

    override fun requestUnlock() {
        setLocked(false)
    }

    override fun setLocked(locked: Boolean) {
        _keyguard.value = UiKeyguardState(locked)
        _options.value = _options.value.copy(locked = locked)
    }

    override fun requestLockShortcut(shortcut: LockShortcut) {
        requestControl(
            if (shortcut == LockShortcut.Flashlight) UiControlAction.Flashlight else UiControlAction.Camera,
        )
    }

    override fun openNotificationAccessSettings() {
        NotificationListenerAccess.openSettings()
    }

    private fun currentClock(): UiClockState {
        val now = ZonedDateTime.now()
        val locale = Locale.getDefault()
        return UiClockState(
            time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(now),
            date = DateTimeFormatter.ofPattern("EEE, d MMM", locale).format(now),
        )
    }

    private data class Sample(
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val timestamp: String,
        val isConversation: Boolean,
    )

    private val SAMPLES = listOf(
        Sample("preview.delivery", "Delivery", "On the way", "Your order will arrive soon.", "now", false),
        Sample("preview.messages", "Messages", "Sam", "Are we still meeting for coffee this afternoon?", "now", true),
        Sample("preview.messages", "Messages", "Family", "The photos from the weekend are ready to share.", "2 min", true),
        Sample("preview.messages", "Messages", "Alex", "I sent you the address. See you there!", "8 min", true),
        Sample("preview.mail", "Mail", "Studio newsletter", "A few new releases are waiting in your reading list.", "12 min", false),
        Sample("preview.calendar", "Calendar", "Design review", "Starts in 30 minutes · Project room", "18 min", false),
        Sample("preview.weather", "Weather", "Local forecast", "Clear skies through the evening.", "24 min", false),
        Sample("preview.photos", "Photos", "Memories", "A look back at this day last year.", "31 min", false),
        Sample("preview.downloads", "Downloads", "File ready", "Your document finished downloading.", "38 min", false),
        Sample("preview.mail", "Mail", "Travel update", "Your saved itinerary has a new detail.", "45 min", false),
        Sample("preview.calendar", "Calendar", "Reminder", "Pick up the order on your way home.", "52 min", false),
        Sample("preview.messages", "Messages", "Jordan", "Thanks — that works for me.", "1 hr", true),
        Sample("preview.system", "System", "Backup complete", "Your device backup is up to date.", "1 hr", false),
        Sample("preview.music", "Music", "New release", "A new album is available from an artist you follow.", "2 hr", false),
        Sample("preview.mail", "Mail", "Receipt", "Your recent purchase receipt is ready.", "2 hr", false),
        Sample("preview.calendar", "Calendar", "Tomorrow", "You have two events on your calendar.", "3 hr", false),
        Sample("preview.messages", "Messages", "Casey", "I'll send the details later today.", "3 hr", true),
        Sample("preview.photos", "Photos", "Shared album", "There are new items in the shared album.", "4 hr", false),
        Sample("preview.system", "System", "Security check", "Review the latest security recommendations.", "5 hr", false),
        Sample("preview.mail", "Mail", "Saved for later", "One message is still marked unread.", "6 hr", false),
        Sample("preview.calendar", "Calendar", "Weekly summary", "Your schedule for the week is ready.", "Yesterday", false),
    )

    private val LONG_BODY =
        "This generated notification has enough text to inspect wrapping and expansion at narrow " +
            "display widths. It remains sample content, not a real message."

    private val TRACKS = listOf(
        "Midnight City" to "M83",
        "Sunset Lover" to "Petit Biscuit",
        "A Walk" to "Tycho",
    )

    private val PREVIEW_TOGGLE_ACTIONS = setOf(
        UiControlAction.AirplaneMode,
        UiControlAction.Wifi,
        UiControlAction.MobileData,
        UiControlAction.Bluetooth,
        UiControlAction.Hotspot,
        UiControlAction.RotationLock,
        UiControlAction.SilentMode,
        UiControlAction.Focus,
        UiControlAction.Flashlight,
    )

    init {
        _clock.value = currentClock()
        _displayMode.value = _options.value.notificationDisplayMode
        mockNotifications = makeMockNotifications(_options.value)
        publishNotifications()
    }
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
