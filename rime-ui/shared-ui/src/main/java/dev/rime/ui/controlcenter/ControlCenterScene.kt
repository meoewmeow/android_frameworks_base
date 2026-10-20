package dev.rime.ui.controlcenter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rime.ui.backdrop.BackdropShape
import dev.rime.ui.backdrop.BackdropStyle
import dev.rime.ui.backdrop.BackdropSurface
import dev.rime.ui.components.CircularControl
import dev.rime.ui.components.ConnectivityModule
import dev.rime.ui.components.MediaControl
import dev.rime.ui.components.NativeIcon
import dev.rime.ui.components.PillControl
import dev.rime.ui.components.RimeIcons
import dev.rime.ui.components.UiPalette
import dev.rime.ui.components.surfaceStyle
import dev.rime.ui.components.VerticalSliderControl
import dev.rime.ui.models.UiControlAction
import dev.rime.ui.models.UiSnapshot
import dev.rime.ui.state.RimeUiHost
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun ControlCenterScene(
    snapshot: UiSnapshot,
    host: RimeUiHost,
    palette: UiPalette,
    selectedPage: Int,
    onPageSelected: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = selectedPage.coerceIn(0, 2)) { 3 }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect(onPageSelected)
    }
    LaunchedEffect(selectedPage) {
        if (pagerState.currentPage != selectedPage) pagerState.animateScrollToPage(selectedPage.coerceIn(0, 2))
    }

    Box(Modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            userScrollEnabled = !editing,
            modifier = Modifier.fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(end = 38.dp),
            pageSpacing = 8.dp,
        ) { page ->
            when (page) {
                0 -> EditableControlGrid(snapshot, host, palette, editing) { editing = it }
                1 -> FocusControlsPage(snapshot, host, palette)
                else -> UtilityControlsPage(snapshot, host, palette)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.CenterEnd)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(end = 6.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(3) { page ->
                val selected = pagerState.currentPage == page
                Box(
                    Modifier.size(if (selected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (selected) palette.foreground else palette.secondary.copy(alpha = 0.48f))
                        .clickable { scope.launch { pagerState.animateScrollToPage(page) } },
                )
            }
        }
    }
}

@Composable
private fun FocusControlsPage(snapshot: UiSnapshot, host: RimeUiHost, palette: UiPalette) {
    Column(
        modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 9.dp, top = 22.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FocusModule(
            enabled = snapshot.connectivity.focusEnabled,
            palette = palette,
            host = host,
            modifier = Modifier.fillMaxWidth().height(188.dp),
        )
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            CircularControl(
                "Silent", RimeIcons.Notifications, snapshot.connectivity.silentMode, palette,
                { host.requestControl(UiControlAction.SilentMode) },
            )
            CircularControl(
                "Rotation", RimeIcons.Rotation, snapshot.connectivity.rotationLocked, palette,
                { host.requestControl(UiControlAction.RotationLock) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConnectivityModule(
                palette = palette,
                wifi = snapshot.connectivity.wifiEnabled,
                bluetooth = snapshot.connectivity.bluetoothEnabled,
                airplane = snapshot.connectivity.airplaneMode,
                mobileData = snapshot.connectivity.mobileDataEnabled,
                onAction = host::requestControl,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun UtilityControlsPage(snapshot: UiSnapshot, host: RimeUiHost, palette: UiPalette) {
    Column(
        modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 9.dp, top = 22.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            CircularControl(
                "Flashlight", RimeIcons.Flashlight, snapshot.connectivity.flashlightEnabled, palette,
                { host.requestControl(UiControlAction.Flashlight) },
            )
            CircularControl(
                "Timer", RimeIcons.Timer, null, palette,
                { host.requestControl(UiControlAction.Timer) },
            )
            CircularControl(
                "Calculator", RimeIcons.Calculator, null, palette,
                { host.requestControl(UiControlAction.Calculator) },
            )
            CircularControl(
                "Camera", RimeIcons.Camera, null, palette,
                { host.requestControl(UiControlAction.Camera) },
            )
        }
        PillControl(
            "Personal hotspot",
            RimeIcons.Hotspot,
            snapshot.connectivity.hotspotEnabled,
            palette,
            { host.requestControl(UiControlAction.Hotspot) },
            Modifier.fillMaxWidth(),
        )
        PillControl(
            "Focus",
            RimeIcons.Focus,
            snapshot.connectivity.focusEnabled,
            palette,
            { host.requestControl(UiControlAction.Focus) },
            Modifier.fillMaxWidth(),
        )
        VerticalSliderControl(
            "Brightness",
            RimeIcons.Brightness,
            snapshot.connectivity.brightness,
            palette,
            host::setBrightness,
            Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun FocusModule(
    enabled: Boolean?,
    palette: UiPalette,
    host: RimeUiHost,
    modifier: Modifier = Modifier,
) {
    BackdropSurface(
        style = palette.surfaceStyle(BackdropShape.RoundedRectangle, selected = enabled == true),
        modifier = modifier.clickable { host.requestControl(UiControlAction.Focus) },
    ) {
        Column(
            Modifier.fillMaxSize().padding(15.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            NativeIcon(
                RimeIcons.Focus,
                "Focus",
                if (enabled == true) Color.White else palette.foreground,
                Modifier.size(27.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                androidx.compose.foundation.text.BasicText(
                    "Focus",
                    style = androidx.compose.ui.text.TextStyle(
                        color = if (enabled == true) Color.White else palette.foreground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                androidx.compose.foundation.text.BasicText(
                    when (enabled) {
                        true -> "On"
                        false -> "Off"
                        null -> "Unavailable"
                    },
                    style = androidx.compose.ui.text.TextStyle(
                        color = if (enabled == true) Color.White.copy(alpha = 0.84f) else palette.secondary,
                        fontSize = 12.sp,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun EmptyMediaModule(palette: UiPalette, modifier: Modifier = Modifier) {
    BackdropSurface(
        style = palette.surfaceStyle(BackdropShape.RoundedRectangle, selected = false),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .background(palette.foreground.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                NativeIcon(RimeIcons.Music, "No active media", palette.foreground, Modifier.size(22.dp))
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.text.BasicText(
                "No active media",
                style = androidx.compose.ui.text.TextStyle(
                    color = palette.foreground,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}
