package dev.rime.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.node.DelegatableNode
import dev.rime.ui.settings.Calibration
import dev.rime.ui.settings.LocalCalibration
import androidx.compose.foundation.layout.Box
import dev.rime.ui.lockscreen.LockscreenSceneState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.toSize
import dev.rime.ui.backdrop.BackdropSurfaceRenderer
import dev.rime.ui.backdrop.ProvideBackdropSurfaceRenderer
import dev.rime.ui.components.twoFingerHold
import dev.rime.ui.components.uiPalette
import dev.rime.ui.controlcenter.ControlCenterScene
import dev.rime.ui.lockscreen.LockscreenScene
import dev.rime.ui.lockscreen.WallpaperFrame
import dev.rime.ui.models.UiSnapshot
import dev.rime.ui.notifications.NotificationCenterScene
import dev.rime.ui.state.RimeUiHost
import kotlinx.coroutines.withTimeoutOrNull

enum class UiScene {
    Lockscreen,
    NotificationCenter,
    ControlCenter,
    Unlocked,
}

/** One immersive presentation tree; the host owns all system behavior and state. */
@Composable
fun RimeUiRoot(
    scene: UiScene,
    onSceneChanged: (UiScene) -> Unit,
    host: RimeUiHost,
    backdropRenderer: BackdropSurfaceRenderer,
    animationScale: Float = 1f,
    onDeveloperGesture: () -> Unit,
    modifier: Modifier = Modifier,
    sceneState: LockscreenSceneState? = null,
    lockscreenContentRenderer: dev.rime.ui.lockscreen.LockscreenContentRenderer = dev.rime.ui.lockscreen.DefaultLockscreenContentRenderer,
) {
    val calibration by host.calibration.collectAsState()
    val snapshot = collectUiSnapshot(host)
    val settings = snapshot.settings
    val theme = snapshot.theme
    val notificationDisplayMode = snapshot.notificationDisplayMode
    val keyguard = snapshot.keyguard
    val wallpaper = snapshot.wallpaper
    val sceneScope = rememberCoroutineScope()
    val lockscreenState = sceneState ?: remember(notificationDisplayMode) { LockscreenSceneState(if (notificationDisplayMode == dev.rime.ui.models.NotificationDisplayMode.List) 1f else 0f) }
    val palette = uiPalette(wallpaper.supportsDarkText, wallpaper.primaryColor, settings.surfaces, theme.accent, theme.surface, theme.onSurface, theme.onSurfaceVariant)
    var returnScene by remember { mutableStateOf(UiScene.Lockscreen) }
    var controlPage by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    LaunchedEffect(keyguard.locked, scene) {
        if (!keyguard.locked && scene == UiScene.Lockscreen) onSceneChanged(UiScene.Unlocked)
        if (keyguard.locked && scene == UiScene.Unlocked) onSceneChanged(UiScene.Lockscreen)
    }

    fun enterOverlay(destination: UiScene) {
        if (scene != UiScene.NotificationCenter && scene != UiScene.ControlCenter) {
            returnScene = scene
        }
        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        if (destination == UiScene.NotificationCenter) lockscreenState.settle(sceneScope, 1f)
        onSceneChanged(destination)
    }

    fun closeOverlay() {
        if (scene == UiScene.NotificationCenter) lockscreenState.settle(sceneScope, 0f)
        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        onSceneChanged(returnScene)
    }

    val systemGestures = Modifier.pointerInput(scene, returnScene, lockscreenState.detailKey) {
        if (lockscreenState.detailKey != null) return@pointerInput
        val edgeSize = with(density) { 92.dp.toPx() }
        val bottomSize = with(density) { 72.dp.toPx() }
        val distance = with(density) { 72.dp.toPx() }
        val screenWidth = size.width.toFloat()
        val screenHeight = size.height.toFloat()
        val controlCenterGestureHeight = maxOf(edgeSize, screenHeight * 0.17f)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var totalY = 0f
            var fired = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } > 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                totalY += change.positionChange().y
                if (fired || kotlin.math.abs(totalY) < distance) continue
                val start = down.position
                when {
                    (scene == UiScene.Lockscreen || scene == UiScene.Unlocked) &&
                        totalY > 0f && start.y <= controlCenterGestureHeight &&
                        start.x >= screenWidth * 0.62f -> {
                        change.consume()
                        fired = true
                        enterOverlay(UiScene.ControlCenter)
                    }
                    (scene == UiScene.Lockscreen || scene == UiScene.Unlocked) &&
                        totalY > 0f && start.y <= controlCenterGestureHeight && start.x < screenWidth * 0.62f -> {
                        change.consume()
                        fired = true
                        enterOverlay(UiScene.NotificationCenter)
                    }
                    scene == UiScene.Lockscreen && totalY < 0f && start.y > screenHeight * 0.90f -> {
                        change.consume()
                        fired = true
                        host.requestUnlock()
                    }
                    (scene == UiScene.NotificationCenter || scene == UiScene.ControlCenter) &&
                        totalY < 0f && start.y > screenHeight * 0.85f -> {
                        change.consume()
                        fired = true
                        closeOverlay()
                    }
                }
            }
        }
    }
    val debugGesture = Modifier.twoFingerHold(onDeveloperGesture)

    ProvideRimeUi(calibration, backdropRenderer) {
        // A preset's own wallpaper is drawn here; the device wallpaper stays the window behind.
        // Nothing is edited here: appearance changes belong to the customization page.
        WallpaperFrame(wallpaper.image, { wallpaper.crop }, modifier.fillMaxSize().then(debugGesture).then(systemGestures)) {
            AnimatedContent(
                targetState = if (scene == UiScene.NotificationCenter) UiScene.Lockscreen else scene,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val duration = (190f * animationScale.coerceIn(0f, 2f)).toInt()
                    val entering = fadeIn(tween(duration)) +
                        slideInVertically(spring(dampingRatio = 0.88f, stiffness = 460f)) { it / 26 }
                    val exiting = fadeOut(tween((duration * 0.72f).toInt()))
                    entering togetherWith exiting
                },
                label = "rime-system-surface",
            ) { target ->
                when (target) {
                    UiScene.Lockscreen -> LockscreenScene(snapshot, host, palette, onDeveloperGesture = onDeveloperGesture,
                        startExpanded = scene == UiScene.NotificationCenter, providedState = lockscreenState,
                        onWallpaperGallery = host::requestWallpaperGallery,
                        contentRenderer = lockscreenContentRenderer)
                    UiScene.NotificationCenter -> NotificationCenterScene(snapshot, host, palette)
                    UiScene.ControlCenter -> ControlCenterScene(
                        snapshot = snapshot,
                        host = host,
                        palette = palette,
                        selectedPage = controlPage,
                        onPageSelected = { controlPage = it },
                    )
                    UiScene.Unlocked -> Box(Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** The locals every shared scene relies on, for a host that shows one outside [RimeUiRoot]. */
@Composable
fun ProvideRimeUi(calibration: Calibration, backdropRenderer: BackdropSurfaceRenderer, content: @Composable () -> Unit) {
    ProvideBackdropSurfaceRenderer(backdropRenderer) {
        CompositionLocalProvider(LocalIndication provides NoPressOverlay, LocalCalibration provides calibration, content = content)
    }
}

/** Everything a scene draws from, collected from the host's flows. */
@Composable
fun collectUiSnapshot(host: RimeUiHost): UiSnapshot {
    val notifications by host.notifications.collectAsState()
    val notificationDisplayMode by host.displayMode.collectAsState()
    val media by host.media.collectAsState()
    val connectivity by host.connectivity.collectAsState()
    val keyguard by host.keyguard.collectAsState()
    val clock by host.clock.collectAsState()
    val wallpaper by host.wallpaper.collectAsState()
    val theme by host.theme.collectAsState()
    val content by host.lockscreenContent.collectAsState()
    val settings by host.lockscreenSettings.collectAsState()
    return UiSnapshot(notifications, notificationDisplayMode, media, connectivity, keyguard, clock, wallpaper,
        theme, content, settings)
}

/** Replaces the foundation default, which darkens every pressed element with a debug overlay. */
private object NoPressOverlay : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0
}
