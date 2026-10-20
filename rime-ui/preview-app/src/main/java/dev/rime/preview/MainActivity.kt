package dev.rime.preview

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraManager.TorchCallback
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.rime.preview.mock.PreviewStore
import dev.rime.preview.notifications.NotificationListenerAccess
import dev.rime.preview.ui.PreviewApp
import dev.rime.preview.wallpaper.WallpaperHost
import dev.rime.ui.models.UiControlAction

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var developerPanelVisible by mutableStateOf(false)
    private lateinit var wallpaperHost: WallpaperHost
    private var torchCameraId: String? = null

    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) PreviewStore.importPhoto(uri)
    }

    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) PreviewStore.refreshDeviceState()
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) toggleTorch()
    }

    private val clockRefresh = object : Runnable {
        override fun run() {
            PreviewStore.refreshClock()
            mainHandler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        developerPanelVisible = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && intent.getBooleanExtra("developer", false)
        configureWallpaperWindow()
        NotificationListenerAccess.setApplicationContext(applicationContext)
        PreviewStore.initialize(applicationContext)
        wallpaperHost = WallpaperHost(applicationContext) { wallpaper ->
            PreviewStore.updateSystemWallpaper(wallpaper)
            updateSystemBarAppearance(wallpaper.supportsDarkText)
        }
        PreviewStore.bindPhotoPicker {
            wallpaperPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        // Debug builds can import a fixture already in the app's files, so checks need no picker.
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            intent.getStringExtra("importPhoto")?.takeIf { '/' !in it }?.let { name ->
                PreviewStore.importPhoto(Uri.fromFile(java.io.File(filesDir, name)))
                PreviewStore.requestWallpaperGallery()
            }
        }
        wallpaperHost.start()
        PreviewStore.bindControlHandlers(
            onRequest = ::handleControlRequest,
            onBrightness = ::setPreviewBrightness,
            onVolume = ::setDeviceVolume,
        )
        registerTorchState()

        setContent {
            PreviewApp(
                host = PreviewStore,
                settings = PreviewStore,
                library = PreviewStore,
                galleryRequested = PreviewStore.galleryRequested,
                onGalleryShown = PreviewStore::galleryShown,
                calibrationTransfer = PreviewStore.calibrationTransfer,
                developerPanelVisible = developerPanelVisible,
                onDeveloperGesture = { developerPanelVisible = true },
                onDeveloperDismiss = { developerPanelVisible = false },
                onRequestBluetoothPermission = { requestBluetoothPermission() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        PreviewStore.refreshDeviceState()
        PreviewStore.refreshClock()
        mainHandler.removeCallbacks(clockRefresh)
        mainHandler.postDelayed(clockRefresh, 30_000L)
        wallpaperHost.refresh()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(clockRefresh)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(clockRefresh)
        wallpaperHost.stop()
        super.onDestroy()
    }

    private fun configureWallpaperWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // Draw under the camera cutout like a real lockscreen; otherwise that strip is bare wallpaper.
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        updateSystemBarAppearance(darkIcons = false)
        hideSystemBars()
    }

    private fun updateSystemBarAppearance(darkIcons: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = darkIcons
        controller.isAppearanceLightNavigationBars = darkIcons
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun handleControlRequest(action: UiControlAction) {
        when (action) {
            UiControlAction.Wifi -> openSettingsPanelOrActivity(
                if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS,
            )
            UiControlAction.Bluetooth -> {
                if (Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestBluetoothPermission()
                } else {
                    openSettingsPanelOrActivity(Settings.ACTION_BLUETOOTH_SETTINGS)
                }
            }
            UiControlAction.AirplaneMode -> openSettingsPanelOrActivity(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            UiControlAction.MobileData, UiControlAction.Hotspot ->
                openSettingsPanelOrActivity(Settings.ACTION_WIRELESS_SETTINGS)
            UiControlAction.RotationLock -> openSettingsPanelOrActivity(Settings.ACTION_DISPLAY_SETTINGS)
            UiControlAction.SilentMode -> toggleSilentMode()
            UiControlAction.Focus -> openFocusSettingsOrToggle()
            UiControlAction.Flashlight -> requestOrToggleTorch()
            UiControlAction.Timer -> launchIfAvailable(
                Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, 300),
            )
            UiControlAction.Calculator -> launchIfAvailable(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR),
            )
            UiControlAction.Camera -> launchIfAvailable(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            PreviewStore.refreshDeviceState()
        }
    }

    private fun requestOrToggleTorch() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            toggleTorch()
        }
    }

    private fun toggleTorch() {
        val manager = getSystemService(CameraManager::class.java)
        val cameraId = torchCameraId ?: runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        if (cameraId == null) return
        val enabled = PreviewStore.connectivity.value.flashlightEnabled != true
        runCatching { manager.setTorchMode(cameraId, enabled) }
        PreviewStore.setFlashlightState(enabled)
    }

    private fun registerTorchState() {
        val manager = getSystemService(CameraManager::class.java)
        runCatching {
            manager.registerTorchCallback(object : TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    torchCameraId = cameraId
                    PreviewStore.setFlashlightState(enabled)
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    if (torchCameraId == cameraId) PreviewStore.setFlashlightState(null)
                }
            }, mainHandler)
        }
    }

    private fun toggleSilentMode() {
        val audio = getSystemService(AudioManager::class.java)
        audio.ringerMode = if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            AudioManager.RINGER_MODE_VIBRATE
        } else {
            AudioManager.RINGER_MODE_NORMAL
        }
        PreviewStore.refreshDeviceState()
    }

    private fun openFocusSettingsOrToggle() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.isNotificationPolicyAccessGranted) {
            manager.setInterruptionFilter(
                if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                } else {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                },
            )
        } else {
            openSettingsPanelOrActivity(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }
        PreviewStore.refreshDeviceState()
    }

    private fun setPreviewBrightness(level: Float) {
        val attributes = window.attributes
        attributes.screenBrightness = level.coerceIn(0f, 1f)
        window.attributes = attributes
    }

    private fun setDeviceVolume(level: Float) {
        val audio = getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * level.coerceIn(0f, 1f)).toInt(), 0)
        PreviewStore.refreshDeviceState()
    }

    private fun openSettingsPanelOrActivity(action: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29 && action.startsWith("android.settings.panel.")) {
                startActivity(Intent(action))
            } else {
                startActivity(Intent(action))
            }
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun launchIfAvailable(intent: Intent) {
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
    }
}
