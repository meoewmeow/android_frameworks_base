package dev.rime.ui.customization

import android.app.WallpaperColors
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.rime.ui.models.LockscreenPreset
import dev.rime.ui.models.PresetCollection
import dev.rime.ui.models.PresetWallpaper
import dev.rime.ui.settings.PresetCodec
import dev.rime.ui.settings.StoredPresets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * Preset storage in one directory: an index file with each photo and its softened copy beside
 * it. Shared by every host that keeps presets. Never touches the device wallpaper. Callers keep
 * everything except [read] off the main thread.
 */
class PresetFileStore(private val context: Context, private val directory: File) {
    init { directory.mkdirs() }
    private val index = File(directory, "presets.json")
    private val decoded = LruCache<String, ImageBitmap>(8)

    /** Null when nothing was ever stored. Unreadable data reads as empty: the lockscreen works without presets. */
    fun read(): PresetCollection? {
        if (!index.exists()) return null
        return runCatching {
            val json = JSONObject(index.readText())
            val array = json.optJSONArray("presets") ?: JSONArray()
            PresetCodec.decode(StoredPresets(json.optInt("version", 0), json.optString("active").takeIf(String::isNotEmpty),
                (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)
                    item.keys().asSequence().associateWith(item::getString)
                }))
        }.getOrDefault(PresetCollection())
    }

    fun write(collection: PresetCollection) {
        val stored = PresetCodec.encode(collection)
        val json = JSONObject().put("version", stored.version).put("active", stored.activeId ?: "")
            .put("presets", JSONArray().apply { stored.presets.forEach { put(JSONObject(it)) } })
        // Replaced in one step, so an interrupted write cannot leave a half-written index.
        val pending = File(directory, "presets.json.tmp")
        pending.writeText(json.toString())
        if (!pending.renameTo(index)) { index.writeText(json.toString()); pending.delete() }
    }

    /** Stores the photo and its softened copy. Null when the image cannot be read. */
    fun importPhoto(uri: Uri, assetId: String): PresetWallpaper? = decodeScaled(uri)?.let { store(it, assetId) }

    /** Stores an already decoded photo, as [importPhoto] does. */
    fun importBitmap(bitmap: Bitmap, assetId: String): PresetWallpaper = store(bitmap, assetId)

    /** The stored photo itself, for a host that hands it to the system. */
    fun wallpaperFile(preset: LockscreenPreset): File? = preset.wallpaper.assetId?.let { File(directory, "$it.jpg") }?.takeIf(File::exists)

    fun wallpaper(preset: LockscreenPreset): ImageBitmap? = preset.wallpaper.assetId?.let { image("$it.jpg") }
    fun softened(preset: LockscreenPreset): ImageBitmap? = preset.wallpaper.assetId?.let { image("$it.soft.png") }

    fun deleteAssets(assetId: String) {
        for (name in listOf("$assetId.jpg", "$assetId.soft.png")) { File(directory, name).delete(); decoded.remove(name) }
    }

    /** Removes asset files no stored preset refers to. */
    fun pruneAssets(used: Set<String>) {
        directory.listFiles()?.forEach { file ->
            val asset = file.name.removeSuffix(".soft.png").removeSuffix(".jpg")
            if (asset != file.name && asset !in used) { file.delete(); decoded.remove(file.name) }
        }
    }

    private fun image(name: String): ImageBitmap? = decoded.get(name)
        ?: BitmapFactory.decodeFile(File(directory, name).path)?.asImageBitmap()?.also { decoded.put(name, it) }

    private fun store(bitmap: Bitmap, assetId: String): PresetWallpaper {
        File(directory, "$assetId.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val scale = WallpaperCopy.LONG_EDGE_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height).also { small.getPixels(it, 0, width, 0, 0, width, height) }
        val soft = Bitmap.createBitmap(WallpaperCopy.soften(pixels, width, height), width, height, Bitmap.Config.ARGB_8888)
        File(directory, "$assetId.soft.png").outputStream().use { soft.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) WallpaperColors.fromBitmap(small) else null
        return PresetWallpaper(assetId, bitmap.width, bitmap.height,
            supportsDarkText = Build.VERSION.SDK_INT >= 31 && colors != null &&
                colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0,
            primaryColorArgb = colors?.primaryColor?.toArgb() ?: PresetWallpaper().primaryColorArgb)
    }

    /** At most twice the screen's long edge, upright, and in memory the app can read back. */
    private fun decodeScaled(uri: Uri): Bitmap? {
        val metrics = context.resources.displayMetrics
        val limit = maxOf(metrics.widthPixels, metrics.heightPixels) * 2
        if (Build.VERSION.SDK_INT >= 28) return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            var sample = 1
            while (info.size.width / sample > limit || info.size.height / sample > limit) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > limit || bounds.outHeight / sample > limit) sample *= 2
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }
}
