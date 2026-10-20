package dev.rime.ui.customization

import androidx.compose.foundation.background
import kotlinx.coroutines.delay
import dev.rime.ui.settings.SubjectOutline
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.rime.ui.ProvideRimeUi
import dev.rime.ui.backdrop.BackdropSurfaceRenderer
import dev.rime.ui.collectUiSnapshot
import dev.rime.ui.components.uiPalette
import dev.rime.ui.lockscreen.DefaultLockscreenContentRenderer
import dev.rime.ui.lockscreen.LockscreenContentRenderer
import dev.rime.ui.lockscreen.LockscreenScene
import dev.rime.ui.lockscreen.LockscreenSceneState
import dev.rime.ui.lockscreen.WallpaperFrame
import dev.rime.ui.lockscreen.WallpaperGeometry
import dev.rime.ui.models.LockscreenPreset
import dev.rime.ui.models.UiWallpaperState
import dev.rime.ui.settings.LockscreenSettings
import dev.rime.ui.settings.LockscreenSettingsPanel
import dev.rime.ui.state.PresetLibrary
import dev.rime.ui.state.RimeUiHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A host whose appearance is a draft: edits stay here, and everything else is the real host. */
internal class DraftHost(real: RimeUiHost, initial: LockscreenSettings) : RimeUiHost by real {
    private val draft = MutableStateFlow(initial.validated())
    override val lockscreenSettings: StateFlow<LockscreenSettings> = draft.asStateFlow()
    override fun updateLockscreenSettings(settings: LockscreenSettings) { draft.value = settings.validated() }
    override fun requestWallpaperGallery() { }
}

/**
 * Editor for one preset, drawn with the same scene as the lockscreen. The date and the clock are
 * outlined regions: tap one to change it, drag the clock to move it, drag or pinch the photo to
 * reposition it. Detailed values sit behind the "more" button. Every change lands in a draft
 * held here, so nothing is written to the host until Done, which hands the finished preset over
 * once; Cancel drops the draft.
 */
@Composable
fun PresetEditor(initial: LockscreenPreset, library: PresetLibrary, host: RimeUiHost, fallbackRenderer: BackdropSurfaceRenderer,
    onDone: (LockscreenPreset) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier,
    contentRenderer: LockscreenContentRenderer = DefaultLockscreenContentRenderer,
    initialImage: ImageBitmap? = null) {
    val calibration by host.calibration.collectAsState()
    val draftHost = remember(initial.id) {
        DraftHost(host, host.lockscreenSettings.value.copy(clock = initial.clock, surfaces = initial.surfaces))
    }
    var crop by remember(initial.id) { mutableStateOf(initial.wallpaper.crop) }
    var depth by remember(initial.id) { mutableStateOf(initial.depthEnabled) }
    var depthOptions by remember(initial.id) { mutableStateOf(initial.depth) }
    var sceneSize by remember { mutableStateOf(IntSize.Zero) }
    // The subject of this preset's own photo with these options, asked of the host while editing,
    // so depth shows here before anything is applied. Normalised to the whole photo.
    var findingSubject by remember { mutableStateOf(false) }
    val outline by produceState<Path?>(null, depth, depthOptions.outlineSpec, initial.wallpaper.assetId) {
        value = null
        if (!depth || initial.wallpaper.assetId == null) return@produceState
        delay(250)
        findingSubject = true
        value = try { SubjectOutline.decode(library.subjectOutline(initial.copy(depth = depthOptions))) } finally { findingSubject = false }
    }
    var sheet by remember { mutableStateOf<EditorPanel?>(null) }
    val wallpaper = initial.wallpaper
    val image by produceState(initialImage, wallpaper.assetId) { value = library.wallpaperImage(initial) }
    val snapshot = collectUiSnapshot(draftHost)
    val primary = Color(wallpaper.primaryColorArgb)
    // The outline placed on screen the way the photo is: through the crop, at the frame's aspect.
    val screenSubject = remember(outline, crop, sceneSize) {
        val path = outline ?: return@remember null
        if (sceneSize.width <= 0 || sceneSize.height <= 0 || wallpaper.sourceWidth <= 0 || wallpaper.sourceHeight <= 0) return@remember null
        val shown = WallpaperGeometry.sourceRect(wallpaper.sourceWidth, wallpaper.sourceHeight, crop, sceneSize.width.toFloat() / sceneSize.height)
        if (shown.width <= 0f || shown.height <= 0f) return@remember null
        val matrix = android.graphics.Matrix().apply {
            setScale(wallpaper.sourceWidth.toFloat(), wallpaper.sourceHeight.toFloat())
            postTranslate(-shown.left, -shown.top)
            postScale(sceneSize.width / shown.width, sceneSize.height / shown.height)
        }
        android.graphics.Path(path.asAndroidPath()).apply { transform(matrix) }.asComposePath()
    }
    val draftSnapshot = snapshot.copy(wallpaper = UiWallpaperState(wallpaper.supportsDarkText, primary),
        keyguard = snapshot.keyguard.copy(showStatusRegion = false, showGestureHandle = false),
        lockscreenContent = snapshot.lockscreenContent.copy(depthEnabled = depth && screenSubject != null,
            depthSubject = screenSubject, depthIncludeDate = depthOptions.includeDate, depthKeepReadable = depthOptions.keepClockReadable))
    val palette = uiPalette(wallpaper.supportsDarkText, primary, snapshot.settings.surfaces, snapshot.theme.accent,
        snapshot.theme.surface, snapshot.theme.onSurface, snapshot.theme.onSurfaceVariant)
    // No blur here: a copy blurred at one fixed strength never matched the lock screen's live blur.
    val renderer = fallbackRenderer
    val sceneState = remember { LockscreenSceneState() }
    val photo = image
    ProvideRimeUi(calibration, renderer) {
        WallpaperFrame(photo, { crop }, modifier.fillMaxSize().onSizeChanged { sceneSize = it }
            .then(if (wallpaper.assetId != null && photo == null) Modifier.background(primary) else Modifier)
            // Drag and pinch outside the clock reposition the photo. The scene's own controls take their touches first.
            .then(if (photo == null) Modifier else Modifier.pointerInput(photo) {
                detectTransformGestures { _, pan, zoom, _ ->
                    crop = WallpaperGeometry.adjustCrop(photo.width, photo.height, crop, size.width.toFloat() / size.height,
                        pan.x / size.width, pan.y / size.height, zoom)
                }
            })) {
            LockscreenScene(draftSnapshot, draftHost, palette, editing = true, onEdit = { sheet = EditorPanel.Style },
                onEditDate = { sheet = EditorPanel.Date }, onEditMore = { sheet = EditorPanel.Details },
                onFinishEdit = {
                    val edited = draftHost.lockscreenSettings.value
                    onDone(initial.copy(clock = edited.clock, surfaces = edited.surfaces, wallpaper = wallpaper.copy(crop = crop),
                        depthEnabled = depth, depth = depthOptions))
                },
                onCancelEdit = onCancel, providedState = sceneState, contentRenderer = contentRenderer)
            when (sheet) {
                EditorPanel.Style -> ClockStyleSheet(draftHost, snapshot.theme.clockColor ?: palette.foreground,
                    depth = depth, onDepth = if (wallpaper.assetId != null) { value -> depth = value } else null,
                    onDepthSettings = { sheet = EditorPanel.Depth }, onAxionClocks = { host.openClockPicker() },
                    hostedClock = snapshot.lockscreenContent.hostedClock, onUseRimeClock = { host.useRimeClock() }) { sheet = null }
                EditorPanel.Depth -> {
                    // Shows where depth applies: the subject that will stay in front of the clock.
                    // Photo point (0..1 of the whole photo) <-> screen, through the crop.
                    val shown = if (sceneSize.width > 0 && sceneSize.height > 0 && wallpaper.sourceWidth > 0 && wallpaper.sourceHeight > 0)
                        WallpaperGeometry.sourceRect(wallpaper.sourceWidth, wallpaper.sourceHeight, crop, sceneSize.width.toFloat() / sceneSize.height)
                        else null
                    // Tap the photo to choose what stays in front; tap a dot again to drop it.
                    Canvas(Modifier.fillMaxSize().then(if (shown == null || wallpaper.assetId == null) Modifier else Modifier.pointerInput(shown, depthOptions) {
                        detectTapGestures { tap ->
                            val x = (shown.left + tap.x / size.width * shown.width) / wallpaper.sourceWidth
                            val y = (shown.top + tap.y / size.height * shown.height) / wallpaper.sourceHeight
                            val near = depthOptions.picks.indexOfFirst { (px, py) -> kotlin.math.hypot(px - x, py - y) < 0.05f }
                            val picks = if (near >= 0) depthOptions.picks.filterIndexed { i, _ -> i != near }
                                else depthOptions.picks + (x to y)
                            depthOptions = depthOptions.copy(picks = picks).validated()
                        }
                    })) {
                        screenSubject?.let { subject ->
                            drawPath(subject, Color.White.copy(alpha = 0.16f))
                            drawPath(subject, Color.White, style = Stroke(2.dp.toPx()))
                        }
                        if (shown != null) depthOptions.picks.forEach { (x, y) ->
                            val center = Offset((x * wallpaper.sourceWidth - shown.left) / shown.width * size.width,
                                (y * wallpaper.sourceHeight - shown.top) / shown.height * size.height)
                            drawCircle(Color.Black.copy(alpha = 0.35f), 13.dp.toPx(), center)
                            drawCircle(Color.White, 9.dp.toPx(), center)
                            drawCircle(Color(0xFF3B82F6), 6.dp.toPx(), center)
                        }
                    }
                    DepthSheet(depthOptions, findingSubject, screenSubject != null, { depthOptions = it }) { sheet = EditorPanel.Style }
                }
                EditorPanel.Date -> DateSheet(draftHost) { sheet = null }
                EditorPanel.Details -> LockscreenSettingsPanel(draftHost, { sheet = null },
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(340.dp))
                null -> Unit
            }
        }
    }
}

private enum class EditorPanel { Style, Date, Details, Depth }
