package dev.rime.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Image

object RimeIcons {
    val Fingerprint = Icons.Filled.Fingerprint
    val Airplane = Icons.Filled.AirplanemodeActive
    val Bluetooth = Icons.Filled.Bluetooth
    val Brightness = Icons.Filled.Brightness6
    val Camera = Icons.Filled.CameraAlt
    val Calculator = Icons.Filled.Calculate
    val Cellular = Icons.Filled.SignalCellularAlt
    val Focus = Icons.Filled.DoNotDisturbOn
    val Close = Icons.Filled.Close
    val Flashlight = Icons.Filled.FlashlightOn
    val Rotation = Icons.Filled.ScreenLockRotation
    val Notifications = Icons.Filled.Notifications
    val Music = Icons.Filled.MusicNote
    val Pause = Icons.Filled.Pause
    val Play = Icons.Filled.PlayArrow
    val Power = Icons.Filled.PowerSettingsNew
    val ScreenRecord = Icons.Filled.ScreenShare
    val SkipPrevious = Icons.Filled.SkipPrevious
    val SkipNext = Icons.Filled.SkipNext
    val Timer = Icons.Filled.Timer
    val Wifi = Icons.Filled.Wifi
    val Hotspot = Icons.Filled.WifiTethering
    val Volume = Icons.Filled.VolumeUp
}

@Composable
fun NativeIcon(
    image: ImageVector,
    description: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Image(
        imageVector = image,
        contentDescription = description,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier,
    )
}
