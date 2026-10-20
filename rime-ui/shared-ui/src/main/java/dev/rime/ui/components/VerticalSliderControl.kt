package dev.rime.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rime.ui.backdrop.BackdropShape
import dev.rime.ui.backdrop.BackdropStyle
import dev.rime.ui.backdrop.BackdropSurface

@Composable
fun VerticalSliderControl(
    label: String,
    icon: ImageVector,
    value: Float,
    palette: UiPalette,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    var heightPx by remember { mutableFloatStateOf(1f) }
    val clamped = value.coerceIn(0f, 1f)
    BackdropSurface(
        style = palette.surfaceStyle(BackdropShape.Capsule, selected = false),
        modifier = modifier
            .width(67.dp)
            .height(188.dp)
            .onSizeChanged { heightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(heightPx) {
                detectTapGestures(onLongPress = { onLongClick?.invoke() }, onTap = { point ->
                    onValueChange((1f - point.y / heightPx).coerceIn(0f, 1f))
                })
            }
            .pointerInput(heightPx) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        onValueChange((1f - change.position.y / heightPx).coerceIn(0f, 1f))
                    },
                )
            }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
            },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(clamped)
                    .clip(RoundedCornerShape(40.dp))
                    .background(palette.accent.copy(alpha = 0.78f)),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NativeIcon(icon, label, palette.onSurface, Modifier.width(21.dp).height(22.dp))
                androidx.compose.foundation.text.BasicText(
                    text = ((clamped * 100).toInt()).toString() + "%",
                    style = androidx.compose.ui.text.TextStyle(
                        color = palette.onSurface,
                        fontSize = 9.sp,
                    ),
                )
            }
        }
    }
}
