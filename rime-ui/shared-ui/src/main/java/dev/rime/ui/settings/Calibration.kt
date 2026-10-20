package dev.rime.ui.settings

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.math.roundToInt

/** Global tuning shared by every preset. Layout values are dp; scale and opacity are fractions. */
data class Calibration(
    val stackLipDp: Float = 30f,
    val rearCardScaleStep: Float = 0.06f,
    val stackOpenStiffness: Float = 120f,
    val shadowOpacity: Float = 0.22f,
    val shadowBlurDp: Float = 16f,
    val shadowOffsetDp: Float = 5f,
    val galleryScale: Float = 0.8f,
    val galleryPageGapDp: Float = 14f,
    val galleryCornerRadiusDp: Float = 34f,
) {
    fun validated(): Calibration {
        val defaults = Calibration()
        return copy(
            stackLipDp = bounded(stackLipDp, defaults.stackLipDp, CalibrationRanges.stackLipDp),
            rearCardScaleStep = bounded(rearCardScaleStep, defaults.rearCardScaleStep, CalibrationRanges.rearCardScaleStep),
            stackOpenStiffness = bounded(stackOpenStiffness, defaults.stackOpenStiffness, CalibrationRanges.stackOpenStiffness),
            shadowOpacity = bounded(shadowOpacity, defaults.shadowOpacity, CalibrationRanges.shadowOpacity),
            shadowBlurDp = bounded(shadowBlurDp, defaults.shadowBlurDp, CalibrationRanges.shadowBlurDp),
            shadowOffsetDp = bounded(shadowOffsetDp, defaults.shadowOffsetDp, CalibrationRanges.shadowOffsetDp),
            galleryScale = bounded(galleryScale, defaults.galleryScale, CalibrationRanges.galleryScale),
            galleryPageGapDp = bounded(galleryPageGapDp, defaults.galleryPageGapDp, CalibrationRanges.galleryPageGapDp),
            galleryCornerRadiusDp = bounded(galleryCornerRadiusDp, defaults.galleryCornerRadiusDp, CalibrationRanges.galleryCornerRadiusDp),
        )
    }
}

/** One definition of each permitted range, used by validation and by the panel's controls. */
object CalibrationRanges {
    val stackLipDp = 8f..44f
    val rearCardScaleStep = 0f..0.12f
    val stackOpenStiffness = 40f..400f
    val shadowOpacity = 0f..0.6f
    val shadowBlurDp = 0f..40f
    val shadowOffsetDp = 0f..16f
    val galleryScale = 0.6f..0.9f
    val galleryPageGapDp = 4f..32f
    val galleryCornerRadiusDp = 0f..56f
}

private fun bounded(value: Float, fallback: Float, range: ClosedFloatingPointRange<Float>) =
    finite(value, fallback).coerceIn(range.start, range.endInclusive)

/**
 * Versioned flat mapping. Hosts choose the storage; unknown or damaged data becomes the defaults.
 * Version 2 replaced the 8 dp stack gap with a lip tall enough for a line of text, under a new
 * key, so version 1 data keeps its other values and takes the new lip default.
 */
object CalibrationCodec {
    const val VERSION = 2
    fun encode(calibration: Calibration): Map<String, Float> = calibration.validated().let { c ->
        mapOf(
            "version" to VERSION.toFloat(),
            "stackLipDp" to c.stackLipDp,
            "rearCardScaleStep" to c.rearCardScaleStep,
            "stackOpenStiffness" to c.stackOpenStiffness,
            "shadowOpacity" to c.shadowOpacity,
            "shadowBlurDp" to c.shadowBlurDp,
            "shadowOffsetDp" to c.shadowOffsetDp,
            "galleryScale" to c.galleryScale,
            "galleryPageGapDp" to c.galleryPageGapDp,
            "galleryCornerRadiusDp" to c.galleryCornerRadiusDp,
        )
    }
    fun decode(values: Map<String, Float>): Calibration {
        val defaults = Calibration()
        if (values["version"]?.let { it == 1f || it == VERSION.toFloat() } != true) return defaults
        return Calibration(
            stackLipDp = values["stackLipDp"] ?: defaults.stackLipDp,
            rearCardScaleStep = values["rearCardScaleStep"] ?: defaults.rearCardScaleStep,
            stackOpenStiffness = values["stackOpenStiffness"] ?: defaults.stackOpenStiffness,
            shadowOpacity = values["shadowOpacity"] ?: defaults.shadowOpacity,
            shadowBlurDp = values["shadowBlurDp"] ?: defaults.shadowBlurDp,
            shadowOffsetDp = values["shadowOffsetDp"] ?: defaults.shadowOffsetDp,
            galleryScale = values["galleryScale"] ?: defaults.galleryScale,
            galleryPageGapDp = values["galleryPageGapDp"] ?: defaults.galleryPageGapDp,
            galleryCornerRadiusDp = values["galleryCornerRadiusDp"] ?: defaults.galleryCornerRadiusDp,
        ).validated()
    }

    /** The same values as text, for a file a person can read, keep and share. */
    fun toJson(calibration: Calibration): String = JSONObject().apply {
        encode(calibration).forEach { (key, value) -> put(key, (value * 10000f).roundToInt() / 10000.0) }
    }.toString(2)

    /**
     * Null when the text is not calibration data this version can read; nothing should change
     * then. Inside readable data, a missing or damaged value takes its default.
     */
    fun fromJson(text: String): Calibration? = runCatching {
        val json = JSONObject(text)
        if (json.optInt("version", 0) !in 1..VERSION) return null
        decode(json.keys().asSequence().filter { json.opt(it) is Number }.associateWith { json.getDouble(it).toFloat() })
    }.getOrNull()
}

/** Moves calibration in and out as versioned JSON text. The host decides where it goes. */
interface CalibrationTransfer {
    /** Returns where the text went, for the user to read, or null when it could not be written. */
    fun exportCalibration(json: String): String?
    /** The text to read, or null when there is nothing to import. */
    fun importCalibration(): String?
}

/** Candidates apply in memory; only save persists. A host without tuning keeps the defaults. */
interface CalibrationSource {
    val calibration: StateFlow<Calibration> get() = defaultCalibration
    fun previewCalibration(candidate: Calibration) {}
    fun saveCalibration(candidate: Calibration) {}
}

private val defaultCalibration = MutableStateFlow(Calibration()).asStateFlow()

val LocalCalibration = compositionLocalOf { Calibration() }
