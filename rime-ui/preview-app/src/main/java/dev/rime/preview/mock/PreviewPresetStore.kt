package dev.rime.preview.mock

import android.content.Context
import android.graphics.BitmapFactory
import dev.rime.ui.customization.PresetFileStore
import dev.rime.ui.models.LockscreenPreset
import dev.rime.ui.models.PresetCollection
import dev.rime.ui.models.PresetWallpaper
import org.json.JSONArray
import java.io.File

/** One-time move from the earlier profile list, so existing lock screens are not lost. */
fun importLegacyProfiles(context: Context, store: PresetFileStore): PresetCollection {
    val preferences = context.getSharedPreferences("rime_profiles", Context.MODE_PRIVATE)
    val array = JSONArray(preferences.getString("profiles", "[]") ?: "[]")
    val legacyPhotos = File(context.filesDir, "lockscreen_profiles")
    val presets = (0 until array.length()).mapNotNull { i ->
        val item = array.getJSONObject(i)
        val id = item.getString("id").takeIf(LockscreenPreset::isValidId) ?: return@mapNotNull null
        val settings = AppearanceCodec.decode(item.getJSONObject("settings"))
        val photo = if (item.optBoolean("photo")) BitmapFactory.decodeFile(File(legacyPhotos, "$id.png").path) else null
        val wallpaper = photo?.let { store.importBitmap(it, id) }
            ?: PresetWallpaper(supportsDarkText = item.optBoolean("darkText"), primaryColorArgb = item.optInt("color", PresetWallpaper().primaryColorArgb))
        LockscreenPreset(id, item.optString("name"), wallpaper, settings.clock, settings.surfaces)
    }
    return PresetCollection(presets, preferences.getString("selected", null)).validated()
}
