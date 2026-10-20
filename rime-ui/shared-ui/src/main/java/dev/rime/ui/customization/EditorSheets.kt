package dev.rime.ui.customization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rime.ui.lockscreen.AxionClock
import dev.rime.ui.models.UiClockState
import dev.rime.ui.models.DepthOptions
import dev.rime.ui.settings.ClockAppearance
import dev.rime.ui.settings.ClockFace
import dev.rime.ui.settings.DateStyle
import dev.rime.ui.settings.LockscreenSettingsSource
import dev.rime.ui.settings.SettingSlider

private val SELECTED = Color(0xFF3B82F6)

/** A sheet over the bottom of the editor. The preset above it stays visible and undimmed. */
@Composable
internal fun BoxScope.EditorSheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
        .background(Color(0xF21C1D22)).pointerInput(Unit) { detectTapGestures { } }
        .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.fillMaxWidth().height(32.dp)) {
            BasicText(title, style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd).size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
                .clickable(role = Role.Button, onClickLabel = "Close", onClick = onClose), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(11.dp)) {
                    drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        content()
    }
}

/** Clock style tiles and a row of colours. A tap applies to the draft at once. */
@Composable
internal fun BoxScope.ClockStyleSheet(source: LockscreenSettingsSource, autoColor: Color,
    depth: Boolean = false, onDepth: ((Boolean) -> Unit)? = null, onDepthSettings: (() -> Unit)? = null,
    onAxionClocks: (() -> Unit)? = null, hostedClock: Boolean = false, onUseRimeClock: () -> Unit = {}, onClose: () -> Unit) {
    val settings by source.lockscreenSettings.collectAsState()
    val clock = settings.clock
    fun update(change: (ClockAppearance) -> ClockAppearance) = source.updateLockscreenSettings(settings.copy(clock = change(clock)))
    EditorSheet("Font & Color", onClose) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClockFace.entries.forEach { face ->
                // While an Axion face is in use none of these is; picking one switches back.
                val chosen = clock.face == face && !hostedClock
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(width = 76.dp, height = 68.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .then(if (chosen) Modifier.border(2.5.dp, SELECTED, RoundedCornerShape(14.dp)) else Modifier)
                        .clickable(role = Role.Button) { update { it.copy(face = face) }; if (hostedClock) onUseRimeClock() }
                        .semantics { contentDescription = "Clock style ${face.label}"; selected = chosen }, contentAlignment = Alignment.Center) {
                        // The real clock at tile size, so a tile shows exactly the face it selects.
                        AxionClock(UiClockState("12", ""), ClockAppearance(face = face, showDate = false, dualTone = false, translucentDigits = false),
                            Color.White, 0f, 40.dp, editing = false, onEdit = {}, onMove = { _, _ -> },
                            modifier = Modifier.width(60.dp))
                    }
                    BasicText(face.label, style = TextStyle(color = Color.White.copy(alpha = if (chosen) 1f else 0.7f), fontSize = 11.sp))
                }
            }
        }
        // Axion's own faces (analog, bitmap, Nothing-style...) are picked in Axion's clock picker.
        if (onAxionClocks != null) BasicText("Axion clock faces",
            style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f))
                .clickable(role = Role.Button, onClick = onAxionClocks).padding(vertical = 14.dp).wrapContentWidth())
        ChipRow("Layout", listOf("One line" to false, "Stacked" to true), clock.stacked) { value -> update { it.copy(stacked = value) } }
        if (clock.face.variableWeight) SettingSlider("Weight", (clock.fontWeight ?: clock.face.defaultWeight).toFloat(), 100f, 1000f) { value ->
            update { it.copy(fontWeight = (value / 20f).toInt() * 20) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            (listOf<Color?>(null) + CLOCK_COLORS).forEach { color ->
                val chosen = clock.colorArgb == color?.toArgb()
                Box(Modifier.size(44.dp).then(if (chosen) Modifier.border(2.5.dp, SELECTED, CircleShape) else Modifier).padding(5.dp)
                    .clip(CircleShape).background(color ?: autoColor).border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                    .clickable(role = Role.Button) { update { it.copy(colorArgb = color?.toArgb()) } }
                    .semantics { contentDescription = if (color == null) "Automatic colour" else "Clock colour"; selected = chosen },
                    contentAlignment = Alignment.Center) {
                    // The first swatch follows the wallpaper.
                    if (color == null) BasicText("A", style = TextStyle(color = if (autoColor.red + autoColor.green + autoColor.blue > 1.5f) Color.Black else Color.White,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
        // The photo's subject in front of the clock; the system finds the subject once the preset is applied.
        if (onDepth != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Depth off" to false, "Depth on" to true).forEach { (label, value) ->
                val chosen = depth == value
                BasicText(label, style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = if (chosen) 0.20f else 0.08f))
                        .then(if (chosen) Modifier.border(2.dp, SELECTED, RoundedCornerShape(16.dp)) else Modifier)
                        .clickable(role = Role.Button) { onDepth(value) }.semantics { selected = chosen }.padding(vertical = 14.dp).wrapContentWidth())
            }
        }
        if (depth && onDepthSettings != null) BasicText("Depth settings",
            style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f))
                .clickable(role = Role.Button, onClick = onDepthSettings).padding(vertical = 14.dp).wrapContentWidth())
        // Glass digits only; solid digits are opaque and unblurred. The opacity floor keeps the digits readable.
        if (clock.translucentDigits) {
            SettingSlider("Clock opacity", clock.digitOpacity.coerceAtLeast(0.3f), 0.3f, 1f) { value -> update { it.copy(digitOpacity = value) } }
        }
    }
}

private val CLOCK_COLORS = listOf(Color(0xFFF7F7F7), Color(0xFF181A1E), Color(0xFF9FE3F5), Color(0xFFB4C8FF), Color(0xFFD9C8FF),
    Color(0xFFD893F7), Color(0xFFFFB3C7), Color(0xFFFFD9A0), Color(0xFFD2DBC7))

/** Small labelled row of choices; scrolls sideways when the labels do not fit. */
@Composable
internal fun <T> ChipRow(label: String, values: List<Pair<String, T>>, current: T, pick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(label, style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { (name, value) ->
                val chosen = current == value
                BasicText(name, style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal),
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = if (chosen) 0.20f else 0.08f))
                        .then(if (chosen) Modifier.border(2.dp, SELECTED, RoundedCornerShape(14.dp)) else Modifier)
                        .clickable(role = Role.Button) { pick(value) }.semantics { selected = chosen }
                        .padding(horizontal = 16.dp, vertical = 11.dp))
            }
        }
    }
}

/** Where the date sits and how it looks, plus one optional item beside it. */
@Composable
internal fun BoxScope.DateSheet(source: LockscreenSettingsSource, onClose: () -> Unit) {
    val settings by source.lockscreenSettings.collectAsState()
    val clock = settings.clock
    val date = clock.date
    fun update(change: (ClockAppearance) -> ClockAppearance) = source.updateLockscreenSettings(settings.copy(clock = change(clock)))
    fun style(change: (DateStyle) -> DateStyle) = update { it.copy(date = change(it.date).validated()) }
    EditorSheet("Date", onClose) {
      // Scrolls inside a short sheet, so the date above it stays visible while it changes.
      Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChipRow("Position", listOf("Above" to 0, "Below" to 1, "Hidden" to 2),
            if (!clock.showDate) 2 else if (clock.dateBelow) 1 else 0) { value ->
            update { when (value) { 0 -> it.copy(showDate = true, dateBelow = false); 1 -> it.copy(showDate = true, dateBelow = true); else -> it.copy(showDate = false) } }
        }
        if (clock.showDate) {
            SettingSlider("Size", date.sizeSp, 12f, 40f) { value -> style { it.copy(sizeSp = value) } }
            ChipRow("Format", DateStyle.Format.entries.map { it.label to it }, date.format) { value -> style { it.copy(format = value) } }
            ChipRow("Weight", DateStyle.Weight.entries.map { it.label to it }, date.weight) { value -> style { it.copy(weight = value) } }
            ChipRow("Beside the date", DateStyle.Extra.entries.map { it.label to it }, date.extra) { value -> style { it.copy(extra = value) } }
            ChipRow("Colour", DateStyle.Tint.entries.map { it.label to it }, date.color) { value -> style { it.copy(color = value) } }
            ChipRow("Letters", listOf("Normal" to false, "Capitals" to true), date.uppercase) { value -> style { it.copy(uppercase = value) } }
        }
      }
    }
}

/**
 * Depth options. The subject is still found automatically; these steer how. The outline the
 * editor draws over the photo while this sheet is open is the result of exactly these choices.
 */
@Composable
internal fun BoxScope.DepthSheet(options: DepthOptions, finding: Boolean, found: Boolean,
    onChange: (DepthOptions) -> Unit, onClose: () -> Unit) {
    EditorSheet("Depth", onClose) {
      // Scrolls inside a short sheet, so the photo and its outline stay visible above it.
      Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(when { finding -> "Finding the subject\u2026"; found -> "The outline shows what stays in front of the clock."
            else -> "No clear subject in this photo." },
            style = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp))
        @Composable fun <T> Choice(label: String, values: List<Pair<String, T>>, current: T, pick: (T) -> Unit) {
            BasicText(label, style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                values.forEach { (name, value) ->
                    val chosen = current == value
                    BasicText(name, style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = if (chosen) 0.20f else 0.08f))
                            .then(if (chosen) Modifier.border(2.dp, SELECTED, RoundedCornerShape(14.dp)) else Modifier)
                            .clickable(role = Role.Button) { pick(value) }.semantics { selected = chosen }.padding(vertical = 11.dp).wrapContentWidth())
                }
            }
        }
        Choice("Finding the subject", listOf("Fast" to DepthOptions.Quality.Fast, "Best (slower)" to DepthOptions.Quality.Best), options.quality) { onChange(options.copy(quality = it)) }
        if (options.quality == DepthOptions.Quality.Best) BasicText("A larger model: sharper hair and edges. Takes up to half a minute per photo.",
            style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp))
        BasicText(if (options.picks.isEmpty()) "Tap the photo to choose what stays in front of the clock."
            else "Only the tapped parts stay in front. Tap a dot to remove it.",
            style = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp))
        if (options.picks.isNotEmpty()) BasicText("Back to automatic",
            style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f))
                .clickable(role = Role.Button) { onChange(options.copy(picks = emptyList())) }.padding(horizontal = 16.dp, vertical = 11.dp))
        Choice("Subject", listOf("Main subject" to DepthOptions.Subject.Largest, "Everything found" to DepthOptions.Subject.All), options.subject) { onChange(options.copy(subject = it)) }
        Choice("Edge", listOf("Tight" to DepthOptions.Edge.Tight, "Normal" to DepthOptions.Edge.Normal, "Loose" to DepthOptions.Edge.Loose), options.edge) { onChange(options.copy(edge = it)) }
        Choice("Outline", listOf("Smooth" to DepthOptions.Detail.Smooth, "Normal" to DepthOptions.Detail.Normal, "Detailed" to DepthOptions.Detail.Fine), options.detail) { onChange(options.copy(detail = it)) }
        Choice("Date", listOf("Behind subject" to true, "Always on top" to false), options.includeDate) { onChange(options.copy(includeDate = it)) }
        Choice("Covered clock", listOf("Keep readable" to true, "Always cut" to false), options.keepClockReadable) { onChange(options.copy(keepClockReadable = it)) }
        Choice("Spatial scene", listOf("Off" to false, "On" to true), options.spatial) { onChange(options.copy(spatial = it)) }
        if (options.spatial) SettingSlider("Movement", options.spatialStrength, 0.25f, 2f) { onChange(options.copy(spatialStrength = it).validated()) }
      }
    }
}
