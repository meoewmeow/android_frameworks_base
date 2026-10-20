/* Copyright (C) 2026 AxionOS Project; extracted presentation under Apache-2.0. */
package dev.rime.ui.lockscreen

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import dev.rime.ui.backdrop.*
import dev.rime.ui.models.UiClockState
import dev.rime.ui.settings.ClockAppearance
import dev.rime.ui.settings.ClockFace
import dev.rime.ui.settings.DateStyle
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.text.font.FontWeight

/** Glyph geometry is shared with every host. No wallpaper capture or blur implementation lives here. */
private data class ClockGlyphs(val hours: Path, val minutes: Path) {
    val combined get() = Path().apply { addPath(hours); addPath(minutes) }
}

private fun clockGlyphs(time: String, typeface: Typeface, width: Float, height: Float, scale: Float, face: ClockFace,
    stacked: Boolean): ClockGlyphs {
    // A time is shown as four digits. A shorter sample, as on a style tile, is shown as given.
    val digits = time.filter(Char::isDigit).map { Character.digit(it, 10).toString() }.joinToString("").take(4)
        .let { if (it.length < 3) it.padStart(2, '0') else it.padStart(4, '0') }
    val twoLines = stacked && digits.length >= 3
    val text = if (!face.colon || twoLines || digits.length < 3) digits else digits.take(2) + ":" + digits.takeLast(2)
    val split = (text.length - 2).coerceAtLeast(1)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = 160f }
    val hours = android.graphics.Path()
    val minutes = android.graphics.Path()
    // Tight tracking suits the wide face; other fonts keep their own spacing.
    val tracking = if (face.stretch) paint.textSize * 0.06f else 0f
    fun line(chars: String, target: android.graphics.Path) {
        var x = 0f
        chars.forEach { char ->
            val glyph = android.graphics.Path()
            paint.getTextPath(char.toString(), 0, 1, x, 0f, glyph)
            target.addPath(glyph)
            x += paint.measureText(char.toString()) - tracking
        }
    }
    if (twoLines) {
        line(text.take(2), hours); line(text.takeLast(2), minutes)
        val top = RectF(); hours.computeBounds(top, true)
        val bottom = RectF(); minutes.computeBounds(bottom, true)
        // Minutes sit one line below, both lines centred on the wider one.
        val gap = paint.textSize * 0.08f
        val wide = maxOf(top.width(), bottom.width())
        hours.offset((wide - top.width()) / 2f - top.left, -top.top)
        minutes.offset((wide - bottom.width()) / 2f - bottom.left, top.height() + gap - bottom.top)
    } else {
        var x = 0f
        text.forEachIndexed { index, char ->
            val glyph = android.graphics.Path()
            paint.getTextPath(char.toString(), 0, 1, x, 0f, glyph)
            (if (index < split) hours else minutes).addPath(glyph)
            x += paint.measureText(char.toString()) - tracking
        }
    }
    val union = android.graphics.Path(hours).apply { addPath(minutes) }
    val bounds = RectF(); union.computeBounds(bounds, true)
    // The wide face's width follows its size setting; every other face is sized by the clock's
    // height (the resize handle) and only limited by the available width.
    val widthFit = width / bounds.width().coerceAtLeast(1f) * (if (face.stretch) (0.72f * scale).coerceIn(0.36f, 1f) else 1f)
    val heightFit = height / bounds.height().coerceAtLeast(1f)
    // The wide face stretches to fill its box; every other face keeps its proportions.
    val sx = if (face.stretch) widthFit else minOf(widthFit, heightFit)
    val sy = if (face.stretch) heightFit else sx
    val matrix = android.graphics.Matrix().apply {
        setScale(sx, sy)
        postTranslate((width - bounds.width() * sx) / 2f - bounds.left * sx,
            (height - bounds.height() * sy) / 2f - bounds.top * sy)
    }
    hours.transform(matrix); minutes.transform(matrix)
    return ClockGlyphs(hours.asComposePath(), minutes.asComposePath())
}

/**
 * The scene's clock editor reads the drawn digits' width here (dp), so moving the clock sideways
 * is limited by what is drawn, not by the full-width box around it.
 */
val LocalClockContentWidth = staticCompositionLocalOf<MutableState<Float?>?> { null }

/** The face's font at [weight]; a font that fails to load falls back to the system's bold. */
internal fun clockTypeface(context: android.content.Context, face: ClockFace, weight: Int): Typeface = runCatching {
    if (face.asset == null) Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false)
    else {
        val axes = listOfNotNull("'wght' $weight".takeIf { face.variableWeight }, face.variation.takeIf { it.isNotEmpty() })
        Typeface.Builder(context.assets, face.asset).setWeight(weight)
            .apply { if (axes.isNotEmpty()) setFontVariationSettings(axes.joinToString(", ")) }.build()
    }
}.getOrDefault(Typeface.DEFAULT_BOLD)

/** The date in the chosen format, from the host's clock when it gives one. */
internal fun formatDate(clock: UiClockState, style: DateStyle): String {
    val text = if (clock.epochMillis <= 0L) clock.date else runCatching {
        val locale = java.util.Locale.getDefault()
        java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, style.format.skeleton), locale)
            .format(java.util.Date(clock.epochMillis))
    }.getOrDefault(clock.date)
    return if (style.uppercase) text.uppercase() else text
}

/** The date row: the date, then the chosen extra. In the editor a missing extra shows a sample. */
@Composable
private fun DateRow(clock: UiClockState, style: DateStyle, color: Color, editing: Boolean, placeholder: Boolean, modifier: Modifier) {
    val text = TextStyle(color = color, fontSize = style.sizeSp.sp, fontWeight = FontWeight(style.weight.value))
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BasicText(if (placeholder) "Date" else formatDate(clock, style), style = text)
        if (placeholder) return@Row
        val (icon, value) = when (style.extra) {
            DateStyle.Extra.None -> return@Row
            DateStyle.Extra.Weather -> Icons.Rounded.WbSunny to (clock.weather ?: if (editing) "22\u00B0" else null)
            DateStyle.Extra.Alarm -> Icons.Rounded.Alarm to (clock.nextAlarm ?: if (editing) "7:00" else null)
            DateStyle.Extra.Battery -> Icons.Rounded.BatteryStd to (clock.battery ?: if (editing) "80%" else null)
        }
        value ?: return@Row
        val iconSize = (style.sizeSp * 1.05f).dp
        Spacer(Modifier.width((style.sizeSp * 0.6f).dp))
        val weatherIcon = clock.weatherIcon.takeIf { style.extra == DateStyle.Extra.Weather }
        if (weatherIcon != null) Image(weatherIcon, null, Modifier.size(iconSize))
        else Image(icon, null, Modifier.size(iconSize), colorFilter = ColorFilter.tint(color))
        Spacer(Modifier.width((style.sizeSp * 0.25f).dp))
        BasicText(value, style = text)
    }
}

@Composable
fun AxionClock(
    clock: UiClockState,
    appearance: ClockAppearance,
    foreground: Color,
    blurRadiusDp: Float,
    height: Dp,
    referenceHeight: Dp = height,
    editing: Boolean,
    onEdit: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit = { _, _ -> },
    onGestureStarted: () -> Unit = {},
    onGestureFinished: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    canResize: Boolean = true,
    onEditDate: () -> Unit = {},
    /** Depth: the subject's outline in root pixels. The glyphs, their tint and their blur all skip it. */
    subject: Path? = null,
    includeDate: Boolean = true,
    keepReadable: Boolean = true,
    /** Spatial: the subject's current movement in window pixels; the cut-outs follow it. */
    subjectShift: State<Offset>? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val weight = appearance.fontWeight?.takeIf { appearance.face.variableWeight } ?: appearance.face.defaultWeight
    val typeface = remember(appearance.face, weight) { clockTypeface(context, appearance.face, weight) }
    val move by rememberUpdatedState(onMove)
    val resize by rememberUpdatedState(onResize)
    val start by rememberUpdatedState(onGestureStarted)
    val finish by rememberUpdatedState(onGestureFinished)
    val options by rememberUpdatedState(onEdit)
    val editDate by rememberUpdatedState(onEditDate)
    val clockCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val handleCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val tint = appearance.colorArgb?.let(::Color) ?: foreground
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // In the editor the date is an outlined, tappable region, and stays reachable while hidden.
        @Composable fun DateLine() {
            val style = appearance.date
            val color = when (style.color) {
                DateStyle.Tint.Clock -> tint
                DateStyle.Tint.White -> Color.White
                DateStyle.Tint.Accent -> clock.accent ?: tint
            }
            if (appearance.showDate || editing) DateRow(clock, style, color.copy(alpha = if (appearance.showDate) 1f else 0.5f),
                editing, placeholder = !appearance.showDate,
                modifier = (if (!editing) Modifier else Modifier.clip(RoundedCornerShape(14.dp))
                    .border(1.5.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(14.dp)).clickable { editDate() }
                    .padding(horizontal = 22.dp)).cutAround(if (includeDate) subject else null, subjectShift).padding(bottom = 5.dp, top = 5.dp))
        }
        if (!appearance.dateBelow) DateLine()
        var origin by remember { mutableStateOf(Offset.Zero) }
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(height)
                .onGloballyPositioned { clockCoordinates[0] = it; origin = it.positionInRoot() }
                // Outside the editor the clock takes no input, so a hold on it reaches the wallpaper.
                .then(if (!editing) Modifier else Modifier.border(1.5.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                    .clickable { options() }
                    .pointerInput(Unit) {
                        var last = Offset.Zero
                        detectDragGestures(onDragStart = { position ->
                            last = clockCoordinates[0]?.localToRoot(position) ?: position; start()
                        }, onDragEnd = { finish(false) }, onDragCancel = { finish(true) }) { change, _ ->
                            val current = clockCoordinates[0]?.localToRoot(change.position) ?: change.position
                            val delta = current - last; last = current
                            change.consume(); move(delta.x / density.density, delta.y / density.density)
                        }
                    })
                .semantics { contentDescription = "Clock ${clock.time}" },
        ) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { referenceHeight.toPx() }
            val glyphs = remember(clock.time, widthPx, heightPx, appearance.sizeScale, appearance.face, typeface, appearance.stacked) {
                clockGlyphs(clock.time, typeface, widthPx, heightPx, appearance.sizeScale, appearance.face, appearance.stacked)
            }
            val contentWidth = LocalClockContentWidth.current
            if (contentWidth != null) SideEffect {
                contentWidth.value = glyphs.combined.getBounds().width / density.density
            }
            // The subject in this box's own coordinates, or null without depth. When it would hide
            // most of the digits the clock stays whole, as Axion's own depth clock does.
            val boxHeightPx = with(density) { height.toPx() }
            val localSubject = remember(subject, origin, glyphs, boxHeightPx, keepReadable) {
                subject?.let { Path().apply { addPath(it, -origin) } }?.takeIf { local ->
                    !keepReadable || coverage(Path().apply {
                        addPath(glyphs.combined)
                        transform(Matrix().apply { scale(1f, boxHeightPx / heightPx.coerceAtLeast(1f), 1f) })
                    }, local) < READABLE_LIMIT
                }
            }
            // Read here, so a tilt redraws only the clock. Rounded to whole pixels: sub-pixel steps
            // would rebuild the glass region for nothing.
            val shift = subjectShift?.value?.let { Offset(kotlin.math.round(it.x), kotlin.math.round(it.y)) } ?: Offset.Zero
            val movedSubject = remember(localSubject, shift) {
                if (shift == Offset.Zero) localSubject else localSubject?.let { Path().apply { addPath(it, shift) } }
            }
            val shape = remember(glyphs, movedSubject) { object : Shape {
                override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
                    val scaled = Path().apply { addPath(glyphs.combined) }
                    scaled.transform(Matrix().apply { scale(1f, size.height / heightPx.coerceAtLeast(1f), 1f) })
                    return Outline.Generic(movedSubject?.let { Path.combine(PathOperation.Difference, scaled, it) } ?: scaled)
                }
            } }
            val targetOpacity = if (appearance.translucentDigits) appearance.digitOpacity else 1f
            val baseOpacity = targetOpacity * if (appearance.dualTone) 0.70f else 1f
            // Glyph backdrop request uses exactly the same abstraction as notifications and QS.
            BackdropSurface(
                BackdropStyle(tint = tint, opacity = baseOpacity,
                    blurRadius = if (appearance.translucentDigits) blurRadiusDp.dp else 0.dp, customShape = shape),
                Modifier.fillMaxSize(),
            ) {
                if (appearance.dualTone) Canvas(Modifier.fillMaxSize()) {
                    withTransform({
                        movedSubject?.let { clipPath(it, ClipOp.Difference) }
                        scale(1f, size.height / heightPx.coerceAtLeast(1f), pivot = Offset.Zero)
                    }) {
                        drawPath(glyphs.hours, tint.copy(alpha = ((targetOpacity - baseOpacity) / (1f - baseOpacity)).coerceIn(0f, 1f)))
                    }
                }
            }
            if (editing && canResize) Canvas(Modifier.align(Alignment.BottomEnd).size(44.dp)
                .onGloballyPositioned { handleCoordinates[0] = it }
                .pointerInput(Unit) {
                    var last = Offset.Zero
                    detectDragGestures(onDragStart = { position ->
                        last = handleCoordinates[0]?.localToRoot(position) ?: position; start()
                    }, onDragEnd = { finish(false) }, onDragCancel = { finish(true) }) { change, _ ->
                        val current = handleCoordinates[0]?.localToRoot(change.position) ?: change.position
                        val delta = current - last; last = current
                        change.consume(); resize(delta.x / density.density, delta.y / density.density)
                    }
                }.semantics { contentDescription = "Resize clock height" }) {
                drawRoundRect(Color.Black.copy(alpha = 0.6f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
                drawLine(Color.White, Offset(size.width * 0.35f, size.height * 0.7f), Offset(size.width * 0.7f, size.height * 0.7f), 2.dp.toPx())
                drawLine(Color.White, Offset(size.width * 0.7f, size.height * 0.7f), Offset(size.width * 0.7f, size.height * 0.35f), 2.dp.toPx())
            }
        }
        if (appearance.dateBelow) DateLine()
    }
}

/** Above this share of the digits behind the subject, depth is dropped so the time stays readable. */
private const val READABLE_LIMIT = 0.6f

/** The share of [shape]'s area that [cover] hides, measured on whole pixels. */
private fun coverage(shape: Path, cover: Path): Float {
    val bounds = shape.getBounds()
    if (bounds.isEmpty) return 0f
    val clip = android.graphics.Region(bounds.left.toInt() - 1, bounds.top.toInt() - 1, bounds.right.toInt() + 1, bounds.bottom.toInt() + 1)
    fun area(path: Path): Long {
        val region = android.graphics.Region().apply { setPath(path.asAndroidPath(), clip) }
        val iterator = android.graphics.RegionIterator(region)
        val rect = android.graphics.Rect()
        var sum = 0L
        while (iterator.next(rect)) sum += rect.width().toLong() * rect.height()
        return sum
    }
    val whole = area(shape)
    if (whole == 0L) return 0f
    return area(Path.combine(PathOperation.Intersect, shape, cover)) / whole.toFloat()
}

/** Leaves out whatever [subject] (root pixels) covers, so the subject appears in front. */
@Composable
private fun Modifier.cutAround(subject: Path?, shift: State<Offset>? = null): Modifier {
    if (subject == null) return this
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInRoot() }.drawWithContent {
        // Read during draw: a tilt only redraws the date.
        val local = Path().apply { addPath(subject, (shift?.value ?: Offset.Zero) - origin) }
        clipPath(local, ClipOp.Difference) { this@drawWithContent.drawContent() }
    }
}
