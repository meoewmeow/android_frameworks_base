package dev.rime.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Same editor and validation for the APK and future ROM settings host. Persistence belongs to host. */
@Composable
fun LockscreenSettingsPanel(source: LockscreenSettingsSource, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val settings by source.lockscreenSettings.collectAsState()
    fun clock(update: (ClockAppearance) -> ClockAppearance) =
        source.updateLockscreenSettings(settings.copy(clock = update(settings.clock)).validated())
    fun surfaces(update: (SurfaceAppearance) -> SurfaceAppearance) =
        source.updateLockscreenSettings(settings.copy(surfaces = update(settings.surfaces)).validated())
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xF51B1D22))
        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Appearance details", style = TextStyle(color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            EditorButton("Done", onClose)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText("Drag the clock to move it. Changes apply immediately.", style = TextStyle(color = Color(0xFFBBC2CC), fontSize = 12.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClockFace.entries.forEach { face -> EditorButton(face.label) { clock { it.copy(face = face) } } }
        }
        SettingSlider("Clock height", settings.clock.heightScale, 0.5f, 1.5f) { clock { c -> c.copy(heightScale = it) } }
        SettingSlider("Size", settings.clock.sizeScale, 0.5f, 1.35f) { clock { c -> c.copy(sizeScale = it) } }
        SettingSlider("Horizontal position", settings.clock.horizontalOffsetDp, -160f, 160f) { clock { c -> c.copy(horizontalOffsetDp = it) } }
        SettingSlider("Vertical position", settings.clock.topPaddingDp, -170f, 240f) { clock { c -> c.copy(topPaddingDp = it) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorButton(if (settings.clock.dateBelow) "Date below" else "Date above") { clock { it.copy(dateBelow = !it.dateBelow) } }
            EditorButton(if (settings.clock.dualTone) "Two tones" else "One tone") { clock { it.copy(dualTone = !it.dualTone) } }
            EditorButton(if (settings.clock.showDate) "Hide date" else "Show date") { clock { it.copy(showDate = !it.showDate) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(null to "Auto", 0xFFF7F7F7.toInt() to "White", 0xFF181A1E.toInt() to "Black", 0xFFD2DBC7.toInt() to "Sage").forEach { (value, name) ->
                EditorButton(name) { clock { it.copy(colorArgb = value) } }
            }
        }
        EditorButton(if (settings.clock.translucentDigits) "Translucent digits" else "Solid digits") { clock { it.copy(translucentDigits = !it.translucentDigits) } }
        SettingSlider("Digit opacity", settings.clock.digitOpacity, 0.1f, 1f) { clock { c -> c.copy(digitOpacity = it) } }
        BasicText("Notifications + QS surfaces", style = TextStyle(color = Color.White, fontSize = 16.sp))
        SettingSlider("Corner radius", settings.surfaces.cornerRadiusDp, 8f, 48f) { surfaces { s -> s.copy(cornerRadiusDp = it) } }
        SettingSlider("Ordinary blur radius", settings.surfaces.blurRadiusDp, 0f, 64f) { surfaces { s -> s.copy(blurRadiusDp = it) } }
        SettingSlider("Surface opacity", settings.surfaces.opacity, 0.3f, 0.95f) { surfaces { s -> s.copy(opacity = it) } }
        EditorButton("Reset appearance") { source.updateLockscreenSettings(LockscreenSettings()) }
        }
    }
}

@Composable
internal fun EditorButton(label: String, action: () -> Unit) {
    BasicText(label, style = TextStyle(color = Color.White, fontSize = 12.sp),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = action).padding(horizontal = 12.dp, vertical = 12.dp))
}

/** Slider with a typed value. Typing commits on the keyboard's Done action and is clamped to the range. */
@Composable
internal fun SettingSlider(label: String, value: Float, min: Float, max: Float, onValue: (Float) -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }
    val currentCallback by rememberUpdatedState(onValue)
    var typed by remember(value) { mutableStateOf(String.format(Locale.ROOT, "%.2f", value)) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, style = TextStyle(color = Color.White, fontSize = 12.sp), modifier = Modifier.weight(1f))
            BasicTextField(typed, { typed = it }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 12.sp, textAlign = TextAlign.End),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    typed.replace(',', '.').toFloatOrNull()?.takeIf(Float::isFinite)?.let { currentCallback(it.coerceIn(min, max)) }
                }),
                modifier = Modifier.width(64.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 8.dp, vertical = 5.dp).semantics { contentDescription = "$label value" })
        }
        Box(Modifier.fillMaxWidth().height(32.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(min, max) { detectTapGestures { currentCallback(min + (it.x / width).coerceIn(0f, 1f) * (max - min)) } }
            .pointerInput(min, max) { detectHorizontalDragGestures { change, _ -> change.consume(); currentCallback(min + (change.position.x / width).coerceIn(0f, 1f) * (max - min)) } }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, min..max)
                setProgress { currentCallback(it.coerceIn(min, max)); true }
            }, contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f)))
            Box(Modifier.fillMaxWidth(((value - min) / (max - min)).coerceIn(0f, 1f)).height(4.dp).background(Color(0xFFBAD2E8)))
        }
    }
}
