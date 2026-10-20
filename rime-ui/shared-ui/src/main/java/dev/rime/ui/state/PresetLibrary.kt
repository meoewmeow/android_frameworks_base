package dev.rime.ui.state

import androidx.compose.ui.graphics.ImageBitmap
import dev.rime.ui.models.LockscreenPreset
import dev.rime.ui.models.PresetCollection
import kotlinx.coroutines.flow.StateFlow

/**
 * The customization page's view of the host's preset store. The host owns storage, asset files
 * and the act of applying a preset; browsing never applies one.
 */
interface PresetLibrary {
    val presets: StateFlow<PresetCollection>
    /** The preset being applied. Its card shows progress until this clears. */
    val applyingPresetId: StateFlow<String?>
    /** True while a newly chosen photo is being imported. */
    val importing: StateFlow<Boolean>
    /** A new preset waiting for the editor. Its photo is imported, but nothing is stored yet. */
    val draft: StateFlow<LockscreenPreset?>

    /**
     * Validates assets, applies, then makes the preset active. A failure leaves the active one
     * alone. [applyingPresetId] is set before this returns and cleared when the attempt ends.
     */
    fun applyPreset(id: String)
    fun deletePreset(id: String)
    /** Lets the user choose a photo. The result arrives in [draft]. */
    fun addPhotoPreset()
    /** Offers the device's current wallpaper and appearance as a [draft]. */
    fun addCurrentWallpaperPreset()
    /** Stores the edited preset, adding it if it is new, then applies it as [applyPreset] does. */
    fun savePreset(preset: LockscreenPreset)
    /** Drops [draft] and anything imported for it. */
    fun discardDraft()

    /** Both decode off the main thread. The gallery asks only for the pages it shows. */
    suspend fun wallpaperImage(preset: LockscreenPreset): ImageBitmap?
    suspend fun blurredWallpaper(preset: LockscreenPreset): ImageBitmap?
    /**
     * The subject outline of the preset's own photo with its depth options, in the encoded form
     * [dev.rime.ui.settings.SubjectOutline] reads, or null when the host cannot find one. Slow.
     */
    suspend fun subjectOutline(preset: LockscreenPreset): String? = null
}
