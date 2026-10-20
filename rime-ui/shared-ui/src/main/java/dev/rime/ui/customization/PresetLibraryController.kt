package dev.rime.ui.customization

import android.net.Uri
import android.os.Handler
import androidx.compose.ui.graphics.ImageBitmap
import dev.rime.ui.models.LockscreenPreset
import dev.rime.ui.models.PresetCollection
import dev.rime.ui.models.PresetWallpaper
import dev.rime.ui.settings.ClockAppearance
import dev.rime.ui.settings.SurfaceAppearance
import dev.rime.ui.state.PresetLibrary
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The preset library every host shares: storage, drafts, deletion and the order of applying.
 * Only applying itself differs by host, so the preview and the ROM page behave the same way.
 *
 * @param appearance the clock and surfaces showing now, for new drafts and to keep the active preset in step.
 * @param deviceWallpaper the colours of the wallpaper a preset without its own photo follows.
 * @param apply runs on [worker]. It validates and applies the preset, and returns false when it
 *   could not, in which case the previously active preset stays active.
 * @param onActiveChanged runs on the main thread once the active preset changed, or with null
 *   once there is none.
 */
class PresetLibraryController(
    private val store: PresetFileStore,
    private val worker: Executor,
    private val main: Handler,
    private val appearance: () -> Pair<ClockAppearance, SurfaceAppearance>,
    private val deviceWallpaper: () -> PresetWallpaper,
    private val pickPhoto: () -> Unit,
    private val apply: (LockscreenPreset) -> Boolean,
    private val onActiveChanged: (LockscreenPreset?) -> Unit = {},
    private val subjectOutline: ((LockscreenPreset) -> String?)? = null,
) : PresetLibrary {
    private val _presets = MutableStateFlow(PresetCollection())
    override val presets = _presets.asStateFlow()
    private val _applying = MutableStateFlow<String?>(null)
    override val applyingPresetId = _applying.asStateFlow()
    private val _importing = MutableStateFlow(false)
    override val importing = _importing.asStateFlow()
    private val _draft = MutableStateFlow<LockscreenPreset?>(null)
    override val draft = _draft.asStateFlow()

    /** Reads the stored collection; [firstRun] supplies one when nothing was ever stored. */
    fun load(firstRun: () -> PresetCollection = { PresetCollection() }) {
        val stored = store.read() ?: firstRun().also { if (it.presets.isNotEmpty()) store.write(it.validated()) }
        _presets.value = stored.validated()
        // A photo imported for a draft that was never finished would otherwise stay behind.
        val used = _presets.value.presets.mapNotNull { it.wallpaper.assetId }.toSet()
        worker.execute { store.pruneAssets(used) }
    }

    /** Keeps the active preset in step with an appearance changed outside the editor. */
    fun syncActiveAppearance() {
        val active = _presets.value.active ?: return
        val (clock, surfaces) = appearance()
        if (active.clock != clock || active.surfaces != surfaces) save(_presets.value.with(active.copy(clock = clock, surfaces = surfaces)))
    }

    /** The device wallpaper changed: presets that follow it take its new colours. */
    fun followDeviceWallpaper(wallpaper: PresetWallpaper) {
        val collection = _presets.value
        val followed = collection.copy(presets = collection.presets.map { preset ->
            if (preset.wallpaper.assetId != null) preset else preset.copy(wallpaper = preset.wallpaper.copy(
                supportsDarkText = wallpaper.supportsDarkText, primaryColorArgb = wallpaper.primaryColorArgb))
        })
        if (followed != collection) save(followed)
    }

    override fun applyPreset(id: String) {
        syncActiveAppearance()
        activate(id)
    }
    override fun savePreset(preset: LockscreenPreset) {
        if (_draft.value?.id == preset.id) _draft.value = null
        save(_presets.value.with(preset))
        activate(preset.id)
    }
    override fun discardDraft() {
        val dropped = _draft.value ?: return
        _draft.value = null
        dropped.wallpaper.assetId?.let(::deleteAssetsIfUnused)
    }
    override fun deletePreset(id: String) {
        val before = _presets.value
        val preset = before.presets.firstOrNull { it.id == id } ?: return
        val after = before.without(id)
        save(after)
        preset.wallpaper.assetId?.let(::deleteAssetsIfUnused)
        if (before.activeId != id) return
        val next = after.activeId
        if (next != null) activate(next) else onActiveChanged(null)
    }
    override fun addPhotoPreset() = pickPhoto()
    override fun addCurrentWallpaperPreset() {
        val (clock, surfaces) = appearance()
        _draft.value = LockscreenPreset(newId(), "Wallpaper", deviceWallpaper(), clock, surfaces)
    }
    /** Copies a chosen photo in and offers it as a draft. Nothing is stored until the editor's Done. */
    fun importPhoto(uri: Uri) {
        val id = newId()
        val (clock, surfaces) = appearance()
        _importing.value = true
        worker.execute {
            val wallpaper = runCatching { store.importPhoto(uri, id) }.getOrNull()
            main.post {
                _importing.value = false
                if (wallpaper != null) _draft.value = LockscreenPreset(id, "Photo", wallpaper, clock, surfaces)
            }
        }
    }
    override suspend fun wallpaperImage(preset: LockscreenPreset): ImageBitmap? = withContext(Dispatchers.IO) { store.wallpaper(preset) }
    override suspend fun blurredWallpaper(preset: LockscreenPreset): ImageBitmap? = withContext(Dispatchers.IO) { store.softened(preset) }
    override suspend fun subjectOutline(preset: LockscreenPreset): String? =
        subjectOutline?.let { find -> withContext(Dispatchers.IO) { runCatching { find(preset) }.getOrNull() } }

    private fun activate(id: String) {
        val preset = _presets.value.presets.firstOrNull { it.id == id } ?: return
        _applying.value = id
        worker.execute {
            val applied = runCatching { apply(preset) }.getOrDefault(false)
            main.post {
                if (_applying.value != id) return@post
                if (applied && _presets.value.presets.any { it.id == id }) {
                    save(_presets.value.copy(activeId = id))
                    onActiveChanged(preset)
                }
                _applying.value = null
            }
        }
    }
    private fun save(collection: PresetCollection) {
        val validated = collection.validated()
        _presets.value = validated
        worker.execute { store.write(validated) }
    }
    private fun deleteAssetsIfUnused(asset: String) {
        if (_presets.value.presets.none { it.wallpaper.assetId == asset }) worker.execute { store.deleteAssets(asset) }
    }
    private fun newId() = "preset_" + UUID.randomUUID().toString()
}
