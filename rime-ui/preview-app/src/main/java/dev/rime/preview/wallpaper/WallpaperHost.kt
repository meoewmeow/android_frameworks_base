package dev.rime.preview.wallpaper

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.Color
import dev.rime.ui.models.UiWallpaperState
import java.util.concurrent.Executors

/** Normal-app wallpaper bridge: colors only. The device wallpaper stays a window surface. */
class WallpaperHost(
    context: Context,
    private val onStateChanged: (UiWallpaperState) -> Unit,
) {
    private val appContext = context.applicationContext
    private val wallpaperManager = WallpaperManager.getInstance(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val colorsListener = WallpaperManager.OnColorsChangedListener { colors, which ->
        if ((which and WallpaperManager.FLAG_SYSTEM) != 0) publish(colors)
    }

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wallpaperManager.addOnColorsChangedListener(colorsListener, mainHandler)
        }
        refresh()
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wallpaperManager.removeOnColorsChangedListener(colorsListener)
        }
        worker.shutdown()
    }

    fun refresh() {
        worker.execute {
            val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) }.getOrNull()
            } else {
                null
            }
            mainHandler.post { publish(colors) }
        }
    }

    private fun publish(colors: WallpaperColors?) {
        val supportsDarkText = colors?.let {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                (it.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        } ?: false
        val primary = colors?.primaryColor?.toArgb() ?: AndroidColor.rgb(55, 63, 78)
        onStateChanged(
            UiWallpaperState(
                supportsDarkText = supportsDarkText,
                primaryColor = Color(primary),
            ),
        )
    }
}
