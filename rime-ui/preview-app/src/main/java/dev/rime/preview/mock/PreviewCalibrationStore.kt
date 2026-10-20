package dev.rime.preview.mock

import android.content.Context
import dev.rime.ui.settings.Calibration
import dev.rime.ui.settings.CalibrationCodec
import dev.rime.ui.settings.CalibrationTransfer
import java.io.File

/** App-local persistence of the shared calibration codec. Damaged data reads as the defaults. */
class PreviewCalibrationStore(private val context: Context) : CalibrationTransfer {
    private val preferences = context.getSharedPreferences("rime_calibration", Context.MODE_PRIVATE)
    fun read(): Calibration = CalibrationCodec.fromJson(preferences.getString("calibration", "") ?: "") ?: Calibration()
    fun write(calibration: Calibration) {
        preferences.edit().putString("calibration", CalibrationCodec.toJson(calibration)).apply()
    }

    // The app's own folder on shared storage: readable with a file manager or adb, no permission needed.
    private val exchange: File? get() = context.getExternalFilesDir(null)?.let { File(it, "rime-calibration.json") }
    override fun exportCalibration(json: String): String? = runCatching {
        exchange?.also { it.writeText(json) }?.path
    }.getOrNull()
    override fun importCalibration(): String? = runCatching { exchange?.takeIf(File::exists)?.readText() }.getOrNull()
}
