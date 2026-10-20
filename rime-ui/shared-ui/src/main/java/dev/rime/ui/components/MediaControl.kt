package dev.rime.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rime.ui.backdrop.BackdropShape
import dev.rime.ui.backdrop.BackdropStyle
import dev.rime.ui.backdrop.BackdropSurface
import dev.rime.ui.models.UiMediaState

@Composable
fun MediaControl(
    media: UiMediaState,
    palette: UiPalette,
    onPlayingChanged: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    BackdropSurface(
        style = palette.surfaceStyle(BackdropShape.RoundedRectangle, selected = false),
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onLongClick),
    ) {
        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(media, Modifier.size(42.dp))
                Spacer(Modifier.width(9.dp))
                TrackText(media, palette, Modifier.weight(1f))
                TransportIcon(RimeIcons.Play, if (media.isPlaying) "Pause" else "Play", palette) {
                    onPlayingChanged(!media.isPlaying)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(media, Modifier.size(54.dp))
                    Spacer(Modifier.width(11.dp))
                    TrackText(media, palette, Modifier.weight(1f))
                }
                ProgressTrack(media.progress ?: 0.38f, palette, onSeek)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportIcon(RimeIcons.SkipPrevious, "Previous track", palette, onPrevious)
                    Spacer(Modifier.width(13.dp))
                    TransportIcon(
                        if (media.isPlaying) RimeIcons.Pause else RimeIcons.Play,
                        if (media.isPlaying) "Pause" else "Play",
                        palette,
                    ) { onPlayingChanged(!media.isPlaying) }
                    Spacer(Modifier.width(13.dp))
                    TransportIcon(RimeIcons.SkipNext, "Next track", palette, onNext)
                }
            }
        }
    }
}

@Composable
private fun TrackText(media: UiMediaState, palette: UiPalette, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        androidx.compose.foundation.text.BasicText(
            text = media.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.ui.text.TextStyle(
                color = palette.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        androidx.compose.foundation.text.BasicText(
            text = listOf(media.artist, media.source).filter(String::isNotBlank).joinToString(" · "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = androidx.compose.ui.text.TextStyle(color = palette.onSurfaceSecondary, fontSize = 11.sp),
        )
    }
}

@Composable
private fun ProgressTrack(value: Float, palette: UiPalette, onSeek: (Float) -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(18.dp)
            .pointerInput(Unit) {
                detectTapGestures { point ->
                    onSeek((point.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                .background(palette.onSurface.copy(alpha = 0.25f)),
        )
        Box(
            Modifier.fillMaxWidth(value.coerceIn(0f, 1f)).height(3.dp)
                .clip(RoundedCornerShape(2.dp)).background(palette.onSurface.copy(alpha = 0.86f)),
        )
    }
}

@Composable
private fun TransportIcon(
    image: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    palette: UiPalette,
    onClick: () -> Unit,
) {
    NativeIcon(
        image = image,
        description = description,
        tint = palette.onSurface,
        modifier = Modifier.size(38.dp).clip(RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(8.dp),
    )
}

@Composable
private fun Artwork(media: UiMediaState, modifier: Modifier = Modifier) {
    val artwork = media.artwork
    if (artwork != null) {
        Image(
            bitmap = artwork,
            contentDescription = media.title + " artwork",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(13.dp)),
        )
    } else {
        Box(
            modifier.clip(RoundedCornerShape(13.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF5366A8), Color(0xFFB36781)))),
            contentAlignment = Alignment.Center,
        ) {
            NativeIcon(RimeIcons.Music, "Media", Color.White, Modifier.size(23.dp))
        }
    }
}
