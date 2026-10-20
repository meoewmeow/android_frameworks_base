package dev.rime.ui.customization

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rime.ui.ProvideRimeUi
import dev.rime.ui.backdrop.BackdropSurfaceRenderer
import dev.rime.ui.backdrop.WallpaperCopyBackdropRenderer
import dev.rime.ui.collectUiSnapshot
import dev.rime.ui.components.uiPalette
import dev.rime.ui.lockscreen.DefaultLockscreenContentRenderer
import dev.rime.ui.lockscreen.LockscreenContentRenderer
import dev.rime.ui.lockscreen.LockscreenScene
import dev.rime.ui.lockscreen.LockscreenSceneState
import dev.rime.ui.lockscreen.WallpaperFrame
import dev.rime.ui.models.LockShortcut
import dev.rime.ui.models.NotificationDisplayMode
import dev.rime.ui.models.UiNotification
import dev.rime.ui.models.UiNotificationProgress
import dev.rime.ui.settings.CalibrationTransfer
import dev.rime.ui.settings.LockscreenSettings
import dev.rime.ui.state.RimeUiHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The test notifications a calibration page shows, and the controls that change them. */
interface CalibrationFixtures {
    val count: Int
    val longText: Boolean
    val grouped: Boolean
    fun show(count: Int = this.count, longText: Boolean = this.longText, grouped: Boolean = this.grouped)
}

/** Deterministic stand-in notifications. Nothing here comes from, or reaches, the user's own. */
object CalibrationFixtureData {
    private val SAMPLES = listOf(
        Triple("Delivery", "On the way", "Your order will arrive soon."),
        Triple("Messages", "Sam", "Are we still meeting for coffee this afternoon?"),
        Triple("Messages", "Family", "The photos from the weekend are ready to share."),
        Triple("Mail", "Studio newsletter", "A few new releases are waiting in your reading list."),
        Triple("Calendar", "Design review", "Starts in 30 minutes · Project room"),
        Triple("Weather", "Local forecast", "Clear skies through the evening."),
        Triple("Photos", "Memories", "A look back at this day last year."),
    )
    private const val LONG_BODY = "This generated notification has enough text to inspect wrapping and expansion at narrow " +
        "display widths. It remains sample content, not a real message."

    fun notifications(count: Int, longText: Boolean, grouped: Boolean): List<UiNotification> = (0 until count.coerceIn(0, 40)).map { index ->
        val (app, title, text) = SAMPLES[index % SAMPLES.size]
        UiNotification(key = "fixture-$index", packageName = "fixture.${app.lowercase()}", appName = app, title = title,
            text = if (longText) LONG_BODY else text, timestamp = if (index == 0) "now" else "${index * 6} min",
            groupKey = if (grouped) "fixture.${app.lowercase()}:app" else null,
            progress = if (index == 0) UiNotificationProgress(0.62f) else null)
    }
}

/** The real host for everything on screen except notifications and actions, which are stand-ins. */
internal class FixtureHost(real: RimeUiHost) : RimeUiHost by real, CalibrationFixtures {
    override var count by mutableIntStateOf(5)
        private set
    override var longText by mutableStateOf(false)
        private set
    override var grouped by mutableStateOf(true)
        private set
    private val items = MutableStateFlow(CalibrationFixtureData.notifications(count, longText, grouped))
    override val notifications: StateFlow<List<UiNotification>> = items.asStateFlow()
    private val mode = MutableStateFlow(NotificationDisplayMode.Stack)
    override val displayMode: StateFlow<NotificationDisplayMode> = mode.asStateFlow()

    override fun show(count: Int, longText: Boolean, grouped: Boolean) {
        this.count = count; this.longText = longText; this.grouped = grouped
        items.value = CalibrationFixtureData.notifications(count, longText, grouped)
    }
    override fun setDisplayMode(mode: NotificationDisplayMode) { this.mode.value = mode }
    override fun dismissNotification(key: String) { items.value = items.value.filterNot { it.key == key } }
    override fun clearNotifications() { items.value = emptyList() }
    override fun activateNotification(key: String) { }
    override fun setNotificationExpanded(key: String, expanded: Boolean) { }
    override fun manageNotification(key: String) { }
    override fun performNotificationAction(key: String, actionId: String) { }
    override fun requestUnlock() { }
    override fun setLocked(locked: Boolean) { }
    override fun requestLockShortcut(shortcut: LockShortcut) { }
    override fun requestWallpaperGallery() { }
    override fun updateLockscreenSettings(settings: LockscreenSettings) { }
}

/**
 * Calibration with something to look at: the lockscreen scene over the active wallpaper, fed
 * with fixture notifications, and the tuning panel on top. The scene stays interactive, so a
 * value can be judged while the deck is dragged.
 */
@Composable
fun CalibrationPage(host: RimeUiHost, fallbackRenderer: BackdropSurfaceRenderer, onClose: () -> Unit,
    modifier: Modifier = Modifier, transfer: CalibrationTransfer? = null,
    contentRenderer: LockscreenContentRenderer = DefaultLockscreenContentRenderer) {
    val calibration by host.calibration.collectAsState()
    val fixtures = remember(host) { FixtureHost(host) }
    val snapshot = collectUiSnapshot(fixtures)
    val wallpaper = snapshot.wallpaper
    val crop by rememberUpdatedState(wallpaper.crop)
    val renderer = remember(wallpaper.blurredImage, fallbackRenderer) {
        wallpaper.blurredImage?.let { copy -> WallpaperCopyBackdropRenderer(copy) { crop } } ?: fallbackRenderer
    }
    val palette = uiPalette(wallpaper.supportsDarkText, wallpaper.primaryColor, snapshot.settings.surfaces, snapshot.theme.accent, snapshot.theme.surface, snapshot.theme.onSurface, snapshot.theme.onSurfaceVariant)
    val deck = remember { LockscreenSceneState() }
    var atTop by remember { mutableStateOf(true) }
    ProvideRimeUi(calibration, renderer) {
        WallpaperFrame(wallpaper.image, { crop }, modifier.fillMaxSize()) {
            LockscreenScene(snapshot.copy(keyguard = snapshot.keyguard.copy(showStatusRegion = false)), fixtures, palette,
                providedState = deck, contentRenderer = contentRenderer)
            Box(Modifier.align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter).fillMaxWidth()
                .statusBarsPadding().navigationBarsPadding().padding(8.dp)) {
                CalibrationPanel(host, onClose, Modifier.fillMaxWidth().heightIn(max = 360.dp), onMove = { atTop = !atTop },
                    deck = deck, transfer = transfer, fixtures = fixtures)
            }
        }
    }
}
