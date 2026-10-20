package dev.rime.preview.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import dev.rime.preview.mock.PreviewSettingsController
import dev.rime.ui.RimeUiRoot
import dev.rime.ui.backdrop.TintBackdropRenderer
import dev.rime.ui.backdrop.WallpaperCopyBackdropRenderer
import dev.rime.ui.customization.CalibrationPage
import dev.rime.ui.customization.PresetGallery
import dev.rime.ui.settings.CalibrationTransfer
import dev.rime.ui.lockscreen.LockscreenSceneState
import dev.rime.ui.models.NotificationDisplayMode
import dev.rime.ui.UiScene
import dev.rime.ui.state.PresetLibrary
import dev.rime.ui.state.RimeUiHost
import kotlinx.coroutines.flow.StateFlow

@Composable
fun PreviewApp(
    host: RimeUiHost,
    settings: PreviewSettingsController,
    library: PresetLibrary,
    galleryRequested: StateFlow<Boolean>,
    onGalleryShown: () -> Unit,
    calibrationTransfer: CalibrationTransfer?,
    developerPanelVisible: Boolean,
    onDeveloperGesture: () -> Unit,
    onDeveloperDismiss: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
) {
    val options by settings.options.collectAsState()
    val wallpaper by host.wallpaper.collectAsState()
    val sceneController = remember(options.notificationDisplayMode) { LockscreenSceneState(if (options.notificationDisplayMode == NotificationDisplayMode.List) 1f else 0f) }
    var scene by remember { mutableStateOf(UiScene.Lockscreen) }
    val tint = remember(options.forceTranslucentFallback) { TintBackdropRenderer(options.forceTranslucentFallback) }
    // A preset's own photo gets its softened copy under each surface; the device wallpaper stays tint-only.
    val crop by rememberUpdatedState(wallpaper.crop)
    val renderer = remember(wallpaper.blurredImage, tint) {
        wallpaper.blurredImage?.let { copy -> WallpaperCopyBackdropRenderer(copy) { crop } } ?: tint
    }
    // The preview stands in for the customization page that a ROM opens after unlock.
    val galleryRequest by galleryRequested.collectAsState()
    var galleryOpen by remember { mutableStateOf(false) }
    var calibrationOpen by remember { mutableStateOf(false) }
    var closeRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(galleryRequest) { if (galleryRequest) { galleryOpen = true; onGalleryShown() } }
    BackHandler(galleryOpen) { closeRequests++ }
    BackHandler(calibrationOpen) { calibrationOpen = false }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val contentWidth = if (options.previewWidthDp == 0) {
            maxWidth
        } else {
            min(options.previewWidthDp.dp, maxWidth)
        }
        RimeUiRoot(
            scene = scene,
            sceneState = sceneController,
            onSceneChanged = { scene = it },
            host = host,
            backdropRenderer = renderer,
            animationScale = options.animationSpeed,
            onDeveloperGesture = onDeveloperGesture,
            // Hidden, not removed: the page covers it and hands back the same scene state.
            modifier = Modifier.width(contentWidth).fillMaxHeight().graphicsLayer { alpha = if (galleryOpen || calibrationOpen) 0f else 1f },
        )
        if (galleryOpen) PresetGallery(
            library = library,
            host = host,
            fallbackRenderer = tint,
            onClosed = { galleryOpen = false },
            modifier = Modifier.width(contentWidth).fillMaxHeight(),
            activeSceneState = sceneController,
            closeRequests = closeRequests,
        )
        if (calibrationOpen) CalibrationPage(host, tint, onClose = { calibrationOpen = false },
            modifier = Modifier.width(contentWidth).fillMaxHeight(), transfer = calibrationTransfer)
    }

    if (developerPanelVisible) {
        DeveloperPanel(
            settings = settings,
            host = host,
            scene = scene,
            onSceneSelected = { scene = it },
            onRequestBluetoothPermission = onRequestBluetoothPermission,
            onDismiss = onDeveloperDismiss,
            onOpenCalibration = { calibrationOpen = true; onDeveloperDismiss() },
            sceneController = sceneController,
        )
    }
}
