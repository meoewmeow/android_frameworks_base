package dev.rime.ui.notifications

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.rime.ui.components.NotificationSurface
import dev.rime.ui.components.UiPalette
import dev.rime.ui.lockscreen.LockscreenSceneState
import dev.rime.ui.settings.LocalCalibration
import dev.rime.ui.state.NotificationSource
import kotlin.math.roundToInt

/**
 * Fixed scene viewport. Closed, the deck is the front card with the lips of the two behind it.
 * Dragging peels it open: the front card follows the finger and each card behind stays put until
 * the one in front has cleared it. See [NotificationDeckGeometry.peel].
 */
@Composable
fun NotificationDeck(entries: List<NotificationStackEntry>, source: NotificationSource, palette: UiPalette,
    state: LockscreenSceneState, expandedTopDp: Float, bottomDp: Float, peekDp: Float,
    modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val calibration = LocalCalibration.current
    val scaleStep = calibration.rearCardScaleStep
    val cornerPx = with(density) { palette.cornerRadiusDp.dp.toPx() }
    val gap = with(density) { 8.dp.toPx() }
    val peek = with(density) { peekDp.dp.toPx() }
    val viewport = with(density) { (bottomDp - expandedTopDp).coerceAtLeast(1f).dp.toPx() }
    val fadeDistance = with(density) { 48.dp.toPx() }
    // The room above the viewport, up to the top of the scene. A scrolled list moves into it,
    // because the clock that normally sits there scrolls away with the list.
    val above = with(density) { expandedTopDp.dp.toPx() }
    val unmeasured = with(density) { 100.dp.toPx() }
    val heights = entries.map { state.heights[it.key] ?: unmeasured }
    // Animate structural reflow only. Pointer expansion/scroll remain synchronous.
    val listOffsets = mutableListOf<Float>()
    var targetOffset = 0f
    entries.forEachIndexed { index, entry ->
        key(entry.key) {
            val offset by animateFloatAsState(targetOffset,
                spring(dampingRatio = 1f, stiffness = 400f), label = "notification-reflow")
            listOffsets += offset
        }
        targetOffset += heights[index] + gap
    }
    val spacing = listOffsets.mapIndexed { index, offset -> if (index == 0) 0f else offset - listOffsets[index - 1] }
    val travel = NotificationDeckGeometry.restTop(heights, viewport, peek).coerceAtLeast(0f)
    val maxScroll = (heights.sum() + (heights.size - 1) * gap - viewport).coerceAtLeast(0f)
    val expansion = state.expansion
    val poses = NotificationDeckGeometry.peel(heights, viewport, expansion * travel + state.scrollOffsetPx.coerceIn(0f, maxScroll),
        peek, gap, scaleStep, spacing)
    // Layout outputs the gesture handling reads; they do not trigger layout themselves.
    SideEffect {
        state.maxScrollPx = maxScroll
        state.hitTopPx = (above + poses[0].top).coerceAtLeast(0f)
        state.hitBottomPx = with(density) { bottomDp.dp.toPx() }
        state.openStiffness = calibration.stackOpenStiffness
        state.autoOpenExpansion = if (travel <= 0f) 1f else NotificationDeckGeometry.autoOpenLift(heights, viewport, peek, gap) / travel
    }
    val hiddenCount = entries.size - (NotificationDeckGeometry.VISIBLE_CARDS - 1)
    Layout(content = {
        entries.forEachIndexed { index, entry ->
            key(entry.key) {
                val pose = poses[index]
                // Surfaces are translucent, so a card may only draw what is not behind the cards in front of it.
                // Held relative to this card and in a state the draw reads: cards that move together or
                // no longer overlap keep an equal value, so they neither recompose nor redraw.
                val ownTop = pose.top.roundToInt()
                val occlusion = remember { mutableStateOf<Occlusion?>(null) }
                occlusion.value = poses.subList((index - 2).coerceAtLeast(0), index).mapNotNull { card ->
                    if (card.bottom <= pose.bottom - pose.height * pose.scale) null
                    else Ahead(card.bottom - card.height * card.scale - ownTop, card.bottom - ownTop, card.scale, minOf(cornerPx, card.height / 2f) * card.scale)
                }.takeIf { it.isNotEmpty() }?.let { Occlusion(pose.scale, it) }
                val collapsed = expansion <= 0.001f
                val clip = remember {
                    Modifier.drawWithCache {
                        val outline = Path()
                        onDrawWithContent {
                            val covered = occlusion.value
                            if (covered == null) drawContent() else {
                                // The cards in front, moved into this card's own space: it is scaled around its bottom edge.
                                outline.reset()
                                covered.ahead.forEach { card ->
                                    val inset = size.width * (1f - card.scale) / 2f
                                    fun x(deck: Float) = (deck - size.width / 2f) / covered.scale + size.width / 2f
                                    fun y(fromTop: Float) = (fromTop - size.height) / covered.scale + size.height
                                    outline.addRoundRect(RoundRect(x(inset), y(card.top), x(size.width - inset), y(card.bottom),
                                        CornerRadius(card.radius / covered.scale)))
                                }
                                clipPath(outline, ClipOp.Difference) { this@onDrawWithContent.drawContent() }
                            }
                        }
                    }
                }
                // Cards fade only as they leave the top of the scene.
                val fade = NotificationDeckGeometry.edgeFade(pose.top + above, pose.bottom + above, viewport + above, fadeDistance)
                NotificationSurface(entry.notification, palette,
                    // The state is read when the tap happens. A captured value would change on every
                    // frame of a drag and recompose every card with it.
                    onActivate = {
                        // A tap on the closed stack opens it, as a swipe does.
                        if (state.expansion <= 0.001f && entries.size > 1) state.settle(scope, 1f)
                        else if (entry.collapsedGroup) state.expandGroup(entry.groupId, scope)
                        else if (entry.notification.canOpen) source.activateNotification(entry.notification.key)
                        else state.showDetails(entry.notification.key)
                    },
                    onExpand = { state.showDetails(entry.notification.key) },
                    onDismiss = {
                        if (entry.collapsedGroup) entry.groupKeys.forEach { source.dismissNotification(it) }
                        else source.dismissNotification(entry.notification.key)
                    },
                    groupCount = entry.groupCount,
                    showGroupLips = entry.collapsedGroup,
                    onAction = { source.performNotificationAction(entry.notification.key, it) },
                    onManage = { source.manageNotification(entry.notification.key); if (!entry.notification.canOpen) state.showDetails(entry.notification.key) },
                    // A lip shows one summary line; the card's own content takes over as it is uncovered.
                    contentAlpha = if (index == 0) 1f else ((pose.reveal - 0.1f) / 0.4f).coerceIn(0f, 1f),
                    lipAlpha = if (index == 0) 0f else 1f - (pose.reveal / 0.25f).coerceIn(0f, 1f),
                    lipHeight = peekDp.dp,
                    lipLabel = if (index == NotificationDeckGeometry.VISIBLE_CARDS - 1 && hiddenCount > 1) "+$hiddenCount more" else null,
                    interactive = index == 0 || pose.reveal >= 0.999f,
                    collapsedHeightPx = if (index == 0) null else minOf(heights[index], heights[0]),
                    presentationExpansion = pose.reveal,
                    onNaturalHeight = { state.recordHeight(entry.key, it.toFloat()) },
                    onCollapseGroup = if (entry.groupHeader) ({ state.collapseGroup(entry.groupId) }) else null,
                    // The lips are part of the closed stack: a tap on one opens it too.
                    onStackTap = if (index > 0 && collapsed) ({ state.settle(scope, 1f) }) else null,
                    modifier = (if (pose.alpha <= 0f) Modifier.clearAndSetSemantics {} else Modifier).then(clip),
                    surfaceAlpha = pose.alpha * fade, surfaceScale = pose.scale,
                )
            }
        }
    }, modifier = modifier.fillMaxWidth().height((bottomDp - expandedTopDp).coerceAtLeast(1f).dp).semantics {
        contentDescription = "Notifications"
        stateDescription = "${(expansion * 100f).toInt()}% expanded"
        customActions = listOf(
            CustomAccessibilityAction("Show all notifications") { state.settle(scope, 1f); true },
            CustomAccessibilityAction("Collapse notifications") { state.settle(scope, 0f); true },
        )
    }) { measurables, constraints ->
        val rows = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            rows.forEachIndexed { depth, row ->
                val pose = poses[depth]
                val fade = NotificationDeckGeometry.edgeFade(pose.top + above, pose.bottom + above, viewport + above, fadeDistance)
                // No layer clip: the surface clips its own content and its shadow falls outside it.
                row.placeWithLayer(0, pose.top.roundToInt(), zIndex = (rows.size - depth).toFloat()) {
                    // The bottom edge is what stays put while a card is uncovered.
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    scaleX = pose.scale; scaleY = pose.scale
                    alpha = pose.alpha * fade
                }
            }
        }
    }
}

/** A card in front, measured from the top of the card it covers. */
private data class Ahead(val top: Float, val bottom: Float, val scale: Float, val radius: Float)
/** What hides part of a card, and the card's own scale. Equal while the cards involved move together. */
private data class Occlusion(val scale: Float, val ahead: List<Ahead>)
