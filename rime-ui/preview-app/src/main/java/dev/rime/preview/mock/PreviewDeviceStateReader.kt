package dev.rime.preview.mock

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import dev.rime.ui.models.UiConnectivityState

/** Read-only public Android state used by the standalone preview host. */
class PreviewDeviceStateReader(context: Context) {
    private val appContext = context.applicationContext

    fun read(): UiConnectivityState {
        val wifi = runCatching {
            appContext.getSystemService(WifiManager::class.java)?.isWifiEnabled
        }.getOrNull()
        val bluetooth = readBluetooth()
        val audio = appContext.getSystemService(AudioManager::class.java)
        val battery = appContext.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: 50
        val brightness = runCatching {
            Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        }.getOrDefault(0.5f)
        val volume = audio?.let {
            val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            it.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
        } ?: 0.5f
        val focus = runCatching {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            if (manager?.isNotificationPolicyAccessGranted == true) {
                manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            } else {
                null
            }
        }.getOrNull()
        val airplane = runCatching {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        }.getOrNull()
        val rotationLocked = runCatching {
            Settings.System.getInt(appContext.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 0
        }.getOrNull()

        return UiConnectivityState(
            airplaneMode = airplane,
            wifiEnabled = wifi,
            mobileDataEnabled = null,
            bluetoothEnabled = bluetooth,
            hotspotEnabled = null,
            rotationLocked = rotationLocked,
            silentMode = audio?.ringerMode?.let { it != AudioManager.RINGER_MODE_NORMAL },
            focusEnabled = focus,
            flashlightEnabled = null,
            batteryPercent = battery,
            brightness = brightness.coerceIn(0f, 1f),
            volume = volume.coerceIn(0f, 1f),
        )
    }

    private fun readBluetooth(): Boolean? {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return runCatching {
            appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
        }.getOrNull()
    }
}
