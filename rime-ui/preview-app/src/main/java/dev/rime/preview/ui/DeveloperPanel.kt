package dev.rime.preview.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rime.preview.mock.ForegroundOverride
import dev.rime.preview.mock.PreviewOptions
import dev.rime.preview.mock.PreviewSettingsController
import dev.rime.ui.UiScene
import dev.rime.ui.models.NotificationDisplayMode
import dev.rime.ui.state.RimeUiHost

@Composable
fun DeveloperPanel(
    settings: PreviewSettingsController,
    host: RimeUiHost,
    scene: UiScene,
    onSceneSelected: (UiScene) -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onDismiss: () -> Unit,
    onOpenCalibration: () -> Unit,
    sceneController: dev.rime.ui.lockscreen.LockscreenSceneState? = null,
) {
    var showAppearance by remember { mutableStateOf(false) }
    if (showAppearance) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            dev.rime.ui.settings.LockscreenSettingsPanel(host, { showAppearance = false }, Modifier.fillMaxWidth().heightIn(max = 540.dp))
        }
        return
    }
    val options by settings.options.collectAsState()
    val listenerConnected by settings.notificationListenerConnected.collectAsState()
    val wallpaper by host.wallpaper.collectAsState()
    val controls by host.connectivity.collectAsState()

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.36f))
                .clickable(onClick = onDismiss),
        )
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.91f)
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .background(Color(0xF21B1D22))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 19.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(4.dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.38f)),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        "Preview controls",
                        style = androidx.compose.ui.text.TextStyle(
                            color = Color.White,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    BasicText(
                        "Hidden from the simulated system surface",
                        style = androidx.compose.ui.text.TextStyle(color = Color(0xFFB9BEC8), fontSize = 12.sp),
                    )
                }
                dev.rime.ui.components.NativeIcon(
                    dev.rime.ui.components.RimeIcons.Close,
                    "Close preview controls",
                    Color.White,
                    Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onDismiss).padding(10.dp),
                )
            }

            SceneChoice("Axion clock + shared surface settings", false, { showAppearance = true }, Modifier.fillMaxWidth())
            SceneChoice("Calibration", false, onOpenCalibration, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SceneChoice("Insert", false, settings::addMockNotification, Modifier.weight(1f))
                SceneChoice("Progress", false, settings::advanceMockProgress, Modifier.weight(1f))
                SceneChoice("Reset deck", false, settings::resetMockNotifications, Modifier.weight(1f))
            }
            sceneController?.let { controller ->
                SectionTitle("Notification motion")
                DebugSlider("Expansion", controller.expansion * 100f, 0f, 100f) { controller.jumpTo(it / 100f) }
            }
            SectionTitle("Preview surface")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                SceneChoice("Lock", scene == UiScene.Lockscreen, { onSceneSelected(UiScene.Lockscreen) }, Modifier.weight(1f))
                SceneChoice("Notifications", scene == UiScene.NotificationCenter, {
                    onSceneSelected(UiScene.NotificationCenter)
                }, Modifier.weight(1f))
                SceneChoice("Control Center", scene == UiScene.ControlCenter, {
                    onSceneSelected(UiScene.ControlCenter)
                }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(0 to "Fit", 320 to "Compact", 390 to "Standard", 430 to "Wide").forEach { (width, label) ->
                    SceneChoice(label, options.previewWidthDp == width, {
                        settings.updateOptions { it.copy(previewWidthDp = width) }
                    }, Modifier.weight(1f))
                }
            }

            SectionTitle("Wallpaper and contrast")
            BasicText(
                (if (wallpaper.image != null) "Showing the active preset's photo" else "Showing the Android system wallpaper") +
                    " · hold the lockscreen to change it",
                style = androidx.compose.ui.text.TextStyle(color = Color(0xFFD2D5DA), fontSize = 12.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                ForegroundOverride.entries.forEach { mode ->
                    SceneChoice(
                        mode.name,
                        options.foregroundOverride == mode,
                        { settings.updateOptions { it.copy(foregroundOverride = mode) } },
                        Modifier.weight(1f),
                    )
                }
            }

            SectionTitle("Notifications")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 3, 20).forEach { count ->
                    SceneChoice(
                        count.toString(),
                        options.notificationCount == count,
                        { settings.updateOptions { it.copy(notificationCount = count) } },
                        Modifier.weight(1f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    NotificationDisplayMode.Count,
                    NotificationDisplayMode.Stack,
                    NotificationDisplayMode.List,
                ).forEach { mode ->
                    SceneChoice(
                        mode.name,
                        options.notificationDisplayMode == mode,
                        { settings.updateOptions { it.copy(notificationDisplayMode = mode) } },
                        Modifier.weight(1f),
                    )
                }
            }
            SettingToggle("Group notifications", options.groupedNotifications) {
                settings.updateOptions { it.copy(groupedNotifications = !it.groupedNotifications) }
            }
            SettingToggle("Long text", options.longNotificationText) {
                settings.updateOptions { it.copy(longNotificationText = !it.longNotificationText) }
            }
            SettingToggle("Conversation notifications", options.conversations) {
                settings.updateOptions { it.copy(conversations = !it.conversations) }
            }
            BasicText(
                if (listenerConnected) "Real notifications active"
                else "Mock notifications active · real access optional",
                style = androidx.compose.ui.text.TextStyle(color = Color(0xFFD2D5DA), fontSize = 12.sp),
            )
            SceneChoice(
                "Notification access settings",
                false,
                settings::openNotificationAccessSettings,
                Modifier.fillMaxWidth(),
            )

            SectionTitle("Clock and keyguard")
            SettingToggle("Override device clock", options.clockOverride) {
                settings.updateOptions { it.copy(clockOverride = !it.clockOverride) }
            }
            if (options.clockOverride) {
                DebugTextField(options.time, "Time") { newTime ->
                    settings.updateOptions { it.copy(time = newTime) }
                }
                DebugTextField(options.date, "Date") { newDate ->
                    settings.updateOptions { it.copy(date = newDate) }
                }
            }
            SettingToggle("Locked preview state", options.locked) {
                settings.updateOptions { it.copy(locked = !it.locked) }
                host.setLocked(!options.locked)
            }

            SectionTitle("Device state")
            BasicText(
                "Battery " + controls.batteryPercent.toString() + "%  ·  Wi-Fi " +
                    stateText(controls.wifiEnabled) + "  ·  Bluetooth " + stateText(controls.bluetoothEnabled),
                style = androidx.compose.ui.text.TextStyle(color = Color(0xFFD2D5DA), fontSize = 12.sp),
            )
            SettingToggle("Mock Control Center state", options.mockDeviceState) {
                settings.updateOptions { it.copy(mockDeviceState = !it.mockDeviceState) }
            }
            if (options.mockDeviceState) {
                SettingToggle("Wi-Fi", options.mockWifi) { settings.updateOptions { it.copy(mockWifi = !it.mockWifi) } }
                SettingToggle("Bluetooth", options.mockBluetooth) { settings.updateOptions { it.copy(mockBluetooth = !it.mockBluetooth) } }
                SettingToggle("Mobile data", options.mockMobileData) { settings.updateOptions { it.copy(mockMobileData = !it.mockMobileData) } }
                SettingToggle("Airplane mode", options.mockAirplaneMode) { settings.updateOptions { it.copy(mockAirplaneMode = !it.mockAirplaneMode) } }
                SettingToggle("Hotspot", options.mockHotspot) { settings.updateOptions { it.copy(mockHotspot = !it.mockHotspot) } }
                SettingToggle("Rotation lock", options.mockRotationLocked) { settings.updateOptions { it.copy(mockRotationLocked = !it.mockRotationLocked) } }
                SettingToggle("Silent mode", options.mockSilentMode) { settings.updateOptions { it.copy(mockSilentMode = !it.mockSilentMode) } }
                SettingToggle("Focus", options.mockFocus) { settings.updateOptions { it.copy(mockFocus = !it.mockFocus) } }
                SettingToggle("Flashlight", options.mockFlashlight) { settings.updateOptions { it.copy(mockFlashlight = !it.mockFlashlight) } }
                DebugSlider("Battery", options.mockBatteryPercent.toFloat(), 0f, 100f) { value ->
                    settings.updateOptions { it.copy(mockBatteryPercent = value.toInt()) }
                }
                DebugSlider("Brightness", options.mockBrightness * 100f, 0f, 100f) { value ->
                    settings.updateOptions { it.copy(mockBrightness = value / 100f) }
                }
                DebugSlider("Volume", options.mockVolume * 100f, 0f, 100f) { value ->
                    settings.updateOptions { it.copy(mockVolume = value / 100f) }
                }
            } else if (controls.bluetoothEnabled == null) {
                SceneChoice("Grant Bluetooth state access", false, onRequestBluetoothPermission, Modifier.fillMaxWidth())
            }

            SectionTitle("Motion and backdrop")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 1f, 2f).forEach { speed ->
                    SceneChoice(
                        speed.toString() + "×",
                        options.animationSpeed == speed,
                        { settings.updateOptions { it.copy(animationSpeed = speed) } },
                        Modifier.weight(1f),
                    )
                }
            }
            SettingToggle("Stronger translucent fallback test", options.forceTranslucentFallback) {
                settings.updateOptions { it.copy(forceTranslucentFallback = !it.forceTranslucentFallback) }
            }
            Spacer(Modifier.height(14.dp))
            SceneChoice("Reset preview state", false, { settings.updateOptions { PreviewOptions() } }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(
        text,
        style = androidx.compose.ui.text.TextStyle(
            color = Color(0xFFAFCBFF),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    )
}

@Composable
private fun SettingToggle(label: String, value: Boolean, onChange: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(42.dp)
            .toggleable(value = value, role = Role.Switch, onValueChange = { onChange() })
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(label, style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp))
        Box(
            Modifier.size(width = 46.dp, height = 27.dp)
                .clip(CircleShape)
                .background(if (value) Color(0xFF4C93FF) else Color(0xFF51545B)),
            contentAlignment = if (value) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier.padding(horizontal = 3.dp).size(21.dp).clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun SceneChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.heightIn(min = 36.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) Color(0xFF445678) else Color(0xFF2A2C33))
            .border(1.dp, Color.White.copy(alpha = if (selected) 0.3f else 0.12f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.ui.text.TextStyle(
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
    }
}

@Composable
private fun DebugTextField(value: String, label: String, onValueChange: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF292B31)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BasicText(label, style = androidx.compose.ui.text.TextStyle(color = Color(0xFFAEB4C0), fontSize = 10.sp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DebugSlider(
    label: String,
    value: Float,
    minimum: Float,
    maximum: Float,
    onValueChange: (Float) -> Unit,
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val progress = ((value - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        BasicText(
            label + " · " + value.toInt().toString() + "%",
            style = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
        )
        Canvas(
            Modifier.fillMaxWidth().height(32.dp).onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(widthPx, minimum, maximum) {
                    detectTapGestures { tap ->
                        onValueChange(minimum + (tap.x / widthPx).coerceIn(0f, 1f) * (maximum - minimum))
                    }
                }
                .pointerInput(widthPx, minimum, maximum) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            onValueChange(minimum + (change.position.x / widthPx).coerceIn(0f, 1f) * (maximum - minimum))
                        },
                    )
                },
        ) {
            val y = size.height / 2f
            drawLine(Color.White.copy(alpha = 0.28f), Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0xFF7CABFF), Offset(0f, y), Offset(size.width * progress, y), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(size.width * progress, y))
        }
    }
}

private fun stateText(value: Boolean?): String = when (value) {
    true -> "on"
    false -> "off"
    null -> "—"
}
