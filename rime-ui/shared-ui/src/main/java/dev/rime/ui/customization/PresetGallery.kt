package dev.rime.ui.customization

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rime.ui.ProvideRimeUi
import dev.rime.ui.backdrop.BackdropShape
import dev.rime.ui.backdrop.BackdropStyle
import dev.rime.ui.backdrop.BackdropSurface
import dev.rime.ui.backdrop.BackdropSurfaceRenderer
import dev.rime.ui.backdrop.ProvideBackdropSurfaceRenderer
import dev.rime.ui.backdrop.WallpaperCopyBackdropRenderer
import dev.rime.ui.collectUiSnapshot
import dev.rime.ui.components.uiPalette
import dev.rime.ui.lockscreen.DefaultLockscreenContentRenderer
import dev.rime.ui.lockscreen.LockscreenContentRenderer
import dev.rime.ui.lockscreen.LockscreenScene
import dev.rime.ui.lockscreen.LockscreenSceneState
import dev.rime.ui.lockscreen.WallpaperFrame
import dev.rime.ui.models.LockscreenPreset
import dev.rime.ui.models.UiSnapshot
import dev.rime.ui.models.UiWallpaperState
import dev.rime.ui.state.PresetLibrary
import dev.rime.ui.state.RimeUiHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Lock screen gallery. Every page is the real lockscreen scene at one uniform scale. It opens with
 * the active preset filling the screen and shrinks it into place, so it reads as the lockscreen
 * shrinking. Browsing applies nothing; a tap applies one preset and expands it back. Customize
 * and Add New open the draft editor over the gallery.
 *
 * @param fallbackRenderer backdrop for a page whose wallpaper the host cannot read, and for the controls.
 * @param activeSceneState the live lockscreen's state, so the active page starts in the same pose.
 * @param closeRequests each increase steps back: out of the editor, else out of the gallery.
 */
@Composable
fun PresetGallery(library: PresetLibrary, host: RimeUiHost, fallbackRenderer: BackdropSurfaceRenderer,
    onClosed: () -> Unit, modifier: Modifier = Modifier,
    contentRenderer: LockscreenContentRenderer = DefaultLockscreenContentRenderer,
    activeSceneState: LockscreenSceneState? = null, closeRequests: Int = 0) {
    val calibration by host.calibration.collectAsState()
    val collection by library.presets.collectAsState()
    val applying by library.applyingPresetId.collectAsState()
    val importing by library.importing.collectAsState()
    val draft by library.draft.collectAsState()
    val snapshot = collectUiSnapshot(host)
    val presets = collection.presets
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 0 = the centred page fills the screen, 1 = the gallery at rest.
    val progress = remember { Animatable(0f) }
    val pager = rememberPagerState(initialPage = presets.indexOfFirst { it.id == collection.activeId }.coerceAtLeast(0)) { presets.size + 1 }
    // How far the current page is pushed up to reveal deletion, in pixels (negative).
    val lift = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var addSheet by remember { mutableStateOf(false) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Pages that show the device wallpaper are windows through the black background.
    val windows = remember { mutableStateMapOf<String, LayoutCoordinates>() }
    var editor by remember { mutableStateOf<EditorSession?>(null) }
    var saving by remember { mutableStateOf(false) }
    // 1 = the gallery is shown, 0 = the editor has taken its place.
    val galleryAlpha = remember { Animatable(1f) }

    fun close(then: () -> Unit = onClosed) {
        if (closing) return
        closing = true
        scope.launch {
            lift.animateTo(0f)
            val current = library.presets.value
            val active = current.presets.indexOfFirst { it.id == current.activeId }
            if (active >= 0 && pager.currentPage != active) pager.animateScrollToPage(active)
            progress.animateTo(0f, spring(dampingRatio = 1f, stiffness = 320f))
            then()
        }
    }
    fun choose(id: String) {
        if (closing || choosing || editor != null) return
        choosing = true
        scope.launch {
            if (library.presets.value.activeId != id) {
                library.applyPreset(id)
                library.applyingPresetId.first { it != id }
            }
            choosing = false
            // A failed apply leaves the active preset as it was, and the gallery stays open.
            if (library.presets.value.activeId == id) close()
        }
    }
    /** The page grows to full screen and the editor takes its place. Nothing is applied yet. */
    fun customize(preset: LockscreenPreset) {
        if (closing || choosing || editor != null) return
        choosing = true
        scope.launch {
            lift.animateTo(0f)
            val image = library.wallpaperImage(preset)
            progress.animateTo(0f, spring(dampingRatio = 1f, stiffness = 320f))
            editor = EditorSession(preset, isNew = false, image)
            galleryAlpha.snapTo(0f)
            choosing = false
        }
    }
    fun cancelEditor() {
        val session = editor ?: return
        if (saving) return
        scope.launch {
            if (session.isNew) {
                library.discardDraft()
                galleryAlpha.animateTo(1f, tween(180))
                editor = null
            } else {
                galleryAlpha.snapTo(1f)
                editor = null
                progress.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 280f))
            }
        }
    }
    /** Done stores and applies the draft once, then returns to the lockscreen it now matches. */
    fun finishEditor(edited: LockscreenPreset) {
        if (saving) return
        saving = true
        scope.launch {
            library.savePreset(edited)
            library.applyingPresetId.first { it != edited.id }
            saving = false
            if (library.presets.value.activeId == edited.id) onClosed()
        }
    }

    // The first frames compose a whole scene. Starting the shrink after them keeps it from being
    // skipped, and the neighbours' scenes wait until it has finished.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        progress.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 280f))
        entered = true
    }
    val closeRequestsAtOpen = remember { closeRequests }
    LaunchedEffect(closeRequests) { if (closeRequests != closeRequestsAtOpen) { if (editor != null) cancelEditor() else close() } }
    // A new preset goes through the editor before anything is stored.
    LaunchedEffect(draft) {
        val pending = draft
        if (pending != null && editor == null && !closing) {
            addSheet = false
            editor = EditorSession(pending, isNew = true, library.wallpaperImage(pending))
            galleryAlpha.animateTo(0f, tween(180))
        }
    }
    LaunchedEffect(pager.currentPage) { if (lift.value != 0f) lift.animateTo(0f) }
    var knownSize by remember { mutableIntStateOf(presets.size) }
    LaunchedEffect(presets.size) {
        // A newly added preset is appended; bring it to the centre. After a deletion at the end,
        // rest on the last preset instead of the Add New page.
        if (presets.size > knownSize && !closing) pager.animateScrollToPage(presets.size - 1)
        else if (presets.size < knownSize && presets.isNotEmpty() && pager.currentPage >= presets.size) pager.animateScrollToPage(presets.size - 1)
        knownSize = presets.size
    }

    ProvideRimeUi(calibration, fallbackRenderer) {
        BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { root = it }) {
            val fullWidth = maxWidth; val fullHeight = maxHeight
            val scale = calibration.galleryScale
            val cardWidth = fullWidth * scale; val cardHeight = fullHeight * scale
            val margin = (fullHeight - cardHeight) / 2
            val radiusPx = with(density) { calibration.galleryCornerRadiusDp.dp.toPx() }
            val maxLift = with(density) { (fullHeight * 0.14f).toPx() }
            val zoom = { (1f / scale) + (1f - 1f / scale) * progress.value }
            val current = presets.getOrNull(pager.currentPage)

            Canvas(Modifier.fillMaxSize()) {
                // Redrawn while the pages move, because the windows move with them.
                pager.currentPageOffsetFraction; lift.value
                val holes = Path()
                val origin = root
                if (origin != null && origin.isAttached) windows.values.forEach { page ->
                    if (page.isAttached) holes.addRoundRect(RoundRect(origin.localBoundingBoxOf(page, clipBounds = false),
                        CornerRadius(radiusPx * progress.value * zoom())))
                }
                clipPath(holes, ClipOp.Difference) { drawRect(Color.Black, alpha = progress.value * galleryAlpha.value) }
            }

            HorizontalPager(pager, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom(); scaleY = zoom(); alpha = galleryAlpha.value },
                pageSize = PageSize.Fixed(cardWidth), contentPadding = PaddingValues(horizontal = (fullWidth - cardWidth) / 2),
                pageSpacing = calibration.galleryPageGapDp.dp, beyondViewportPageCount = 1, userScrollEnabled = !closing,
                key = { presets.getOrNull(it)?.id ?: "add-new" }) { page ->
                // The empty space around a page closes the gallery.
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { close() } }, contentAlignment = Alignment.Center) {
                    val preset = presets.getOrNull(page)
                    val shape = { RoundedCornerShape(radiusPx * progress.value) }
                    if (preset == null) AddNewPage(cardWidth, cardHeight, shape, busy = importing) {
                        if (page != pager.currentPage) scope.launch { pager.animateScrollToPage(page) } else addSheet = true
                    } else PresetPage(preset, snapshot, host, library, fallbackRenderer, contentRenderer,
                        fullWidth, fullHeight, scale, shape,
                        isActive = preset.id == collection.activeId, busy = applying == preset.id,
                        sceneAllowed = entered && !pager.isScrollInProgress,
                        sceneState = activeSceneState?.takeIf { preset.id == collection.activeId },
                        // Notifications leave first, so the page reads as wallpaper and clock like the others.
                        deckAlpha = { if (preset.id == collection.activeId) (1f - progress.value * 3f).coerceIn(0f, 1f) else 0f },
                        lift = { if (page == pager.currentPage) lift.value else 0f }, windows = windows,
                        onTap = {
                            when {
                                page != pager.currentPage -> scope.launch { pager.animateScrollToPage(page) }
                                lift.value < 0f -> scope.launch { lift.animateTo(0f) }
                                else -> choose(preset.id)
                            }
                        },
                        onLift = { delta -> if (page == pager.currentPage && !closing) scope.launch { lift.snapTo((lift.value + delta).coerceIn(-maxLift, 0f)) } },
                        onLiftEnd = { scope.launch { lift.animateTo(if (lift.value < -maxLift * 0.4f) -maxLift else 0f, spring(dampingRatio = 0.85f, stiffness = 380f)) } })
                }
            }

            // Controls sit in the margins the scaled page leaves, and arrive with it.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((progress.value - 0.5f) * 2f).coerceIn(0f, 1f) * galleryAlpha.value }) {
                BasicText((current?.name?.takeIf { it.isNotBlank() } ?: if (current == null) "Add New" else "Lock Screen").uppercase(),
                    style = TextStyle(color = Color.White, fontSize = 13.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium),
                    // A lifted page passes over the title.
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = margin / 2 - 8.dp)
                        .graphicsLayer { alpha = 1f - (-lift.value / maxLift).coerceIn(0f, 1f) })
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(margin).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (current != null) GalleryButton("Customize", snapshot.settings.surfaces.blurRadiusDp.dp) { customize(current) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(presets.size + 1) { index -> Box(Modifier.size(6.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = if (index == pager.currentPage) 1f else 0.35f))) }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        GalleryButton("+", snapshot.settings.surfaces.blurRadiusDp.dp) { addSheet = true }
                    }
                }
                // Revealed in the strip a lifted page leaves behind.
                if (current != null) Box(Modifier.align(Alignment.TopCenter)
                    .offset { IntOffset(0, ((fullHeight + cardHeight) / 2).roundToPx() - (maxLift / 2f).roundToInt() - 26.dp.roundToPx()) }
                    .graphicsLayer { val shown = (-lift.value / maxLift).coerceIn(0f, 1f); alpha = shown; scaleX = 0.6f + 0.4f * shown; scaleY = scaleX }
                    .size(52.dp).clip(CircleShape).background(Color(0xFFD83A4A))
                    .clickable(enabled = lift.targetValue < 0f, role = Role.Button, onClickLabel = "Delete ${current.name}") { deleteId = current.id },
                    contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(20.dp)) {
                        val stroke = 2.dp.toPx()
                        drawLine(Color.White, Offset(0f, size.height * 0.22f), Offset(size.width, size.height * 0.22f), stroke, StrokeCap.Round)
                        drawLine(Color.White, Offset(size.width * 0.36f, size.height * 0.06f), Offset(size.width * 0.64f, size.height * 0.06f), stroke, StrokeCap.Round)
                        drawRoundRect(Color.White, Offset(size.width * 0.16f, size.height * 0.36f), Size(size.width * 0.68f, size.height * 0.6f),
                            CornerRadius(2.dp.toPx()), style = Stroke(stroke))
                    }
                }
            }

            if (addSheet) Sheet("Add New Wallpaper", onDismiss = { addSheet = false }) {
                SheetAction("Photos") { addSheet = false; library.addPhotoPreset() }
                SheetAction("Current wallpaper") { addSheet = false; library.addCurrentWallpaperPreset() }
            }
            deleteId?.let { id ->
                Sheet("Delete this lock screen?", onDismiss = { deleteId = null }) {
                    SheetAction("Delete", Color(0xFFFF6B78)) {
                        deleteId = null
                        scope.launch { lift.snapTo(0f) }
                        library.deletePreset(id)
                    }
                }
            }
            editor?.let { session ->
                // Covers the gallery and takes every touch, also where the editor itself has no control.
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - galleryAlpha.value }.pointerInput(Unit) { }) {
                    key(session.preset.id) {
                        PresetEditor(session.preset, library, host, fallbackRenderer, onDone = ::finishEditor, onCancel = ::cancelEditor,
                            contentRenderer = contentRenderer, initialImage = session.image)
                    }
                    if (saving) BusyOverlay()
                }
            }
        }
    }
}

private class EditorSession(val preset: LockscreenPreset, val isNew: Boolean, val image: ImageBitmap?)

@Composable
private fun PresetPage(preset: LockscreenPreset, snapshot: UiSnapshot, host: RimeUiHost, library: PresetLibrary,
    fallbackRenderer: BackdropSurfaceRenderer, contentRenderer: LockscreenContentRenderer,
    fullWidth: Dp, fullHeight: Dp, scale: Float, shape: () -> RoundedCornerShape, isActive: Boolean, busy: Boolean,
    sceneAllowed: Boolean, sceneState: LockscreenSceneState?, deckAlpha: () -> Float, lift: () -> Float,
    windows: MutableMap<String, LayoutCoordinates>, onTap: () -> Unit, onLift: (Float) -> Unit, onLiftEnd: () -> Unit) {
    val wallpaper = preset.wallpaper
    // The active page already has its pixels on screen; the others decode as they come into view.
    val image by produceState<ImageBitmap?>(if (isActive) snapshot.wallpaper.image else null, wallpaper.assetId) { value = library.wallpaperImage(preset) }
    val blurred by produceState<ImageBitmap?>(if (isActive) snapshot.wallpaper.blurredImage else null, wallpaper.assetId) { value = library.blurredWallpaper(preset) }
    val primary = Color(wallpaper.primaryColorArgb)
    val pageSnapshot = snapshot.copy(
        settings = snapshot.settings.copy(clock = preset.clock, surfaces = preset.surfaces),
        wallpaper = UiWallpaperState(wallpaper.supportsDarkText, primary),
        keyguard = snapshot.keyguard.copy(showStatusRegion = false, showGestureHandle = false))
    val palette = uiPalette(wallpaper.supportsDarkText, primary, preset.surfaces, snapshot.theme.accent,
        snapshot.theme.surface, snapshot.theme.onSurface, snapshot.theme.onSurfaceVariant)
    val renderer = remember(blurred, wallpaper.crop, fallbackRenderer) {
        blurred?.let { copy -> WallpaperCopyBackdropRenderer(copy) { wallpaper.crop } } ?: fallbackRenderer
    }
    val ownState = remember { LockscreenSceneState() }
    // A page first shows its wallpaper alone. The scene is the costly part, so it is composed
    // once the gallery is at rest and then kept.
    var showScene by remember { mutableStateOf(isActive) }
    LaunchedEffect(sceneAllowed) { if (sceneAllowed) showScene = true }
    val currentTap by rememberUpdatedState(onTap)
    val currentLift by rememberUpdatedState(onLift)
    val currentLiftEnd by rememberUpdatedState(onLiftEnd)
    DisposableEffect(preset.id) { onDispose { windows.remove(preset.id) } }
    Box(Modifier.size(fullWidth * scale, fullHeight * scale).offset { IntOffset(0, lift().roundToInt()) }
        .graphicsLayer { clip = true; this.shape = shape() }
        // Without readable pixels the page is a window onto the device wallpaper.
        .then(if (wallpaper.assetId == null) Modifier.onGloballyPositioned { windows[preset.id] = it } else Modifier.background(primary))) {
        // The scene is laid out at full size and scaled as one piece, never re-laid out smaller.
        // Its semantics are cleared too: the scene's own actions must not be reachable from a page.
        Box(Modifier.requiredSize(fullWidth, fullHeight).graphicsLayer { scaleX = scale; scaleY = scale }.clearAndSetSemantics { }) {
            ProvideBackdropSurfaceRenderer(renderer) {
                WallpaperFrame(image, { wallpaper.crop }, Modifier.fillMaxSize()) {
                    if (showScene) LockscreenScene(pageSnapshot, host, palette, providedState = sceneState ?: ownState,
                        contentRenderer = contentRenderer, deckAlpha = deckAlpha)
                }
            }
        }
        // Covers the scene: a page is one object here, so nothing inside it takes input.
        Box(Modifier.matchParentSize()
            .pointerInput(Unit) { detectTapGestures { currentTap() } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = { currentLiftEnd() }, onDragCancel = { currentLiftEnd() }) { change, amount ->
                    change.consume(); currentLift(amount)
                }
            }
            .semantics { contentDescription = "Lock screen ${preset.name}"; role = Role.Button })
        if (busy) BusyOverlay()
    }
}

@Composable
private fun AddNewPage(width: Dp, height: Dp, shape: () -> RoundedCornerShape, busy: Boolean, onTap: () -> Unit) {
    val currentTap by rememberUpdatedState(onTap)
    Box(Modifier.size(width, height).graphicsLayer { clip = true; this.shape = shape() }
        .background(Color.White.copy(alpha = 0.06f)).border(1.5.dp, Color.White.copy(alpha = 0.6f), shape())
        .pointerInput(Unit) { detectTapGestures { currentTap() } }
        .semantics { contentDescription = "Add a new lock screen"; role = Role.Button }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(96.dp)) {
            val stroke = 5.dp.toPx()
            drawLine(Color.White, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), stroke, StrokeCap.Round)
            drawLine(Color.White, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), stroke, StrokeCap.Round)
        }
        BasicText("Add New", style = TextStyle(color = Color.White, fontSize = 15.sp),
            modifier = Modifier.align(Alignment.Center).padding(top = 150.dp))
        if (busy) BusyOverlay()
    }
}

/** Shown on a page while its preset is applied or imported. */
@Composable
private fun BoxScope.BusyOverlay() {
    val turn by rememberInfiniteTransition(label = "preset-busy").animateFloat(0f, 360f,
        infiniteRepeatable(tween(900, easing = LinearEasing)), label = "preset-busy-turn")
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)).semantics { contentDescription = "Applying" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(44.dp)) {
            drawArc(Color.White, turn, 270f, useCenter = false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun GalleryButton(text: String, blurRadius: Dp, onClick: () -> Unit) {
    // One blur radius per window: the controls share the surfaces' radius.
    BackdropSurface(BackdropStyle(shape = BackdropShape.Capsule, tint = Color(0xFF2B2D33), opacity = 0.92f, blurRadius = blurRadius),
        Modifier.clickable(role = Role.Button, onClick = onClick)) {
        BasicText(text, style = TextStyle(color = Color.White, fontSize = 16.sp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

@Composable
private fun BoxScope.Sheet(title: String, onDismiss: () -> Unit, actions: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { onDismiss() } })
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).navigationBarsPadding()
        .clip(RoundedCornerShape(26.dp)).background(Color(0xFF1C1D22)).pointerInput(Unit) { detectTapGestures { } }
        .padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(title, style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(bottom = 6.dp))
        actions()
        SheetAction("Cancel", Color(0xFFBFC4CE), onDismiss)
    }
}

@Composable
private fun SheetAction(label: String, color: Color = Color.White, onClick: () -> Unit) {
    BasicText(label, style = TextStyle(color = color, fontSize = 16.sp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 15.dp))
}
