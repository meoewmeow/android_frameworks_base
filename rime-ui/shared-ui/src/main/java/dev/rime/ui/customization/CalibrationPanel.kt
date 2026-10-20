package dev.rime.ui.customization

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rime.ui.lockscreen.LockscreenSceneState
import dev.rime.ui.settings.Calibration
import dev.rime.ui.settings.CalibrationCodec
import dev.rime.ui.settings.CalibrationTransfer
import dev.rime.ui.settings.CalibrationRanges
import dev.rime.ui.settings.CalibrationSource
import dev.rime.ui.settings.EditorButton
import dev.rime.ui.settings.SettingSlider

/**
 * Same tuning panel for every host. Candidates apply in memory as they change; Save persists
 * them and Cancel restores the values from when the panel opened. The host places the panel.
 * An imported file becomes a candidate like any other change, so it is kept only by Save.
 */
@Composable
fun CalibrationPanel(source: CalibrationSource, onClose: () -> Unit, modifier: Modifier = Modifier,
    onMove: (() -> Unit)? = null, deck: LockscreenSceneState? = null, transfer: CalibrationTransfer? = null,
    fixtures: CalibrationFixtures? = null) {
    var transferNote by remember { mutableStateOf<String?>(null) }
    val current by source.calibration.collectAsState()
    val saved = remember { current }
    val defaults = remember { Calibration() }
    fun preview(update: (Calibration) -> Calibration) = source.previewCalibration(update(current).validated())
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xF51B1D22)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText("Calibration", style = TextStyle(color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            if (onMove != null) EditorButton("Move", onMove)
            EditorButton("Cancel") { source.previewCalibration(saved); onClose() }
            EditorButton("Save") { source.saveCalibration(current); onClose() }
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (fixtures != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Test notifications", style = TextStyle(color = Color.White, fontSize = 12.sp))
                    listOf(0, 1, 5, 20).forEach { count -> EditorButton(if (fixtures.count == count) "[$count]" else "$count") { fixtures.show(count = count) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorButton(if (fixtures.longText) "Long text: on" else "Long text: off") { fixtures.show(longText = !fixtures.longText) }
                    EditorButton(if (fixtures.grouped) "Groups: on" else "Groups: off") { fixtures.show(grouped = !fixtures.grouped) }
                }
            }
            // Holds the deck at an intermediate pose while a value is tuned.
            if (deck != null) SettingSlider("Deck expansion", deck.expansion, 0f, 1f) { deck.jumpTo(it) }
            Section("Notification stack") { preview { it.copy(stackLipDp = defaults.stackLipDp, rearCardScaleStep = defaults.rearCardScaleStep, stackOpenStiffness = defaults.stackOpenStiffness) } }
            Slider("Lip height (dp)", current.stackLipDp, CalibrationRanges.stackLipDp) { v -> preview { it.copy(stackLipDp = v) } }
            Slider("Rear card scale step", current.rearCardScaleStep, CalibrationRanges.rearCardScaleStep) { v -> preview { it.copy(rearCardScaleStep = v) } }
            Slider("Opening speed (lower is calmer)", current.stackOpenStiffness, CalibrationRanges.stackOpenStiffness) { v -> preview { it.copy(stackOpenStiffness = v) } }
            Section("Card shadow") { preview { it.copy(shadowOpacity = defaults.shadowOpacity, shadowBlurDp = defaults.shadowBlurDp, shadowOffsetDp = defaults.shadowOffsetDp) } }
            Slider("Opacity", current.shadowOpacity, CalibrationRanges.shadowOpacity) { v -> preview { it.copy(shadowOpacity = v) } }
            Slider("Blur (dp)", current.shadowBlurDp, CalibrationRanges.shadowBlurDp) { v -> preview { it.copy(shadowBlurDp = v) } }
            Slider("Vertical offset (dp)", current.shadowOffsetDp, CalibrationRanges.shadowOffsetDp) { v -> preview { it.copy(shadowOffsetDp = v) } }
            Section("Gallery") { preview { it.copy(galleryScale = defaults.galleryScale, galleryPageGapDp = defaults.galleryPageGapDp, galleryCornerRadiusDp = defaults.galleryCornerRadiusDp) } }
            Slider("Preview scale", current.galleryScale, CalibrationRanges.galleryScale) { v -> preview { it.copy(galleryScale = v) } }
            Slider("Page gap (dp)", current.galleryPageGapDp, CalibrationRanges.galleryPageGapDp) { v -> preview { it.copy(galleryPageGapDp = v) } }
            Slider("Corner radius (dp)", current.galleryCornerRadiusDp, CalibrationRanges.galleryCornerRadiusDp) { v -> preview { it.copy(galleryCornerRadiusDp = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorButton("Reset all") { source.previewCalibration(defaults) }
                if (transfer != null) {
                    EditorButton("Export") {
                        transferNote = transfer.exportCalibration(CalibrationCodec.toJson(current))?.let { "Exported to $it" } ?: "Export failed"
                    }
                    EditorButton("Import") {
                        val text = transfer.importCalibration()
                        val imported = text?.let(CalibrationCodec::fromJson)
                        transferNote = when {
                            text == null -> "Nothing to import"
                            imported == null -> "Not calibration data for this version. Nothing changed."
                            else -> { source.previewCalibration(imported); "Imported. Save to keep it." }
                        }
                    }
                }
            }
            transferNote?.let { BasicText(it, style = TextStyle(color = Color(0xFFBBC2CC), fontSize = 12.sp)) }
        }
    }
}

@Composable
private fun Section(title: String, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = TextStyle(color = Color.White, fontSize = 16.sp), modifier = Modifier.weight(1f))
        EditorButton("Reset", onReset)
    }
}

@Composable
private fun Slider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) =
    SettingSlider(label, value, range.start, range.endInclusive, onValue)
