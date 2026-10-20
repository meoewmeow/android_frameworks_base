/* Copyright (C) 2026 Rime contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.rime

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import dev.rime.ui.backdrop.*
import android.graphics.RectF
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import dev.rime.ui.lockscreen.DefaultLockscreenContentRenderer
import dev.rime.ui.lockscreen.HostedClockSlot
import dev.rime.ui.lockscreen.LockscreenContentRenderer
import dev.rime.ui.models.UiClockState
import dev.rime.ui.models.UiLockscreenContent
import dev.rime.ui.settings.ClockAppearance

/** Native region blur; lifetime and drawable APIs are entirely inside the SystemUI host. */
class SystemUIBackdropRenderer(private val guard: RimeFailureGuard) : BackdropSurfaceRenderer {
    @Composable
    override fun Render(style: BackdropStyle, modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
        val shape = style.customShape ?: when (style.shape) {
            BackdropShape.Circle -> CircleShape
            BackdropShape.Capsule -> RoundedCornerShape(50)
            BackdropShape.RoundedRectangle -> RoundedCornerShape(style.cornerRadius)
        }
        Box(modifier) {
            AndroidView(factory = { BlurRegionView(it, guard) }, update = { it.style = style }, modifier = Modifier.matchParentSize())
            Box(Modifier.matchParentSize().clip(shape).background(style.tint.copy(alpha = style.opacity.coerceIn(0f, 1f))))
            // Content measures this surface. Only the backdrop follows the resulting bounds;
            // matchParentSize on both children collapses wrap-content notifications to height 0.
            Box(Modifier.clip(shape), content = content)
        }
    }
}

/** Re-reads every glass region under [root]; ancestor alpha changes do not redraw them. */
internal fun refreshBlurRegions(root: View) {
    when (root) {
        is BlurRegionView -> root.updateVisibility()
        is ViewGroup -> for (i in 0 until root.childCount) refreshBlurRegions(root.getChildAt(i))
    }
}

private class BlurRegionView(context: Context, private val guard: RimeFailureGuard) : View(context) {
    private var blur: BackgroundBlurDrawable? = null
    private val visibleRect = Rect()
    private var shown = false
    var style = BackdropStyle()
        set(value) { if (field != value) { field = value; guard.run("blur style") { updateBlur() } } }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        guard.run("blur attach") {
            blur = viewRootImpl?.createBackgroundBlurDrawable()
            background = blur
            updateBlur()
        }
    }
    override fun onDetachedFromWindow() {
        runCatching { blur?.setBlurRadius(0) }
        runCatching { blur?.setVisible(false, false) }
        background = null
        blur = null
        shown = false
        super.onDetachedFromWindow()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        guard.run("blur size") { updateBlur() }
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        guard.run("blur visibility") { updateVisibility() }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Position-only scrolling can change visibility without changing size or style.
        guard.run("blur draw") { updateVisibility() }
    }
    fun updateVisibility() {
        // A hidden or fully faded surface holds no region; the parent's layer alpha never reaches
        // SurfaceFlinger, so ancestors' alpha (the unlock fade) is folded in here.
        var inherited = alpha
        var ancestor = parent
        while (ancestor is View) { inherited *= ancestor.alpha; ancestor = ancestor.parent }
        val visible = style.requestsBlur && isShown && width > 0 && height > 0 && inherited > 0.01f && getGlobalVisibleRect(visibleRect)
        blur?.setBlurRadius(if (visible) with(Density(context)) { style.blurRadius.toPx().toInt().coerceAtLeast(0) } else 0)
        blur?.setAlpha(if (visible) (style.alpha.coerceIn(0f, 1f) * inherited.coerceIn(0f, 1f) * 255f).toInt() else 0)
        blur?.setVisible(visible, false)
        // The drawable empties its mask while hidden and only rebuilds it in setBounds, so the
        // glyph blur silently stopped drawing after the first screen-off.
        if (visible && !shown) { blur?.setBounds(0, 0, width, height); invalidate() }
        shown = visible
    }
    private fun updateBlur() {
        val drawable = blur ?: return
        val density = Density(context)
        // The region reports the mask's base size from the bounds, which the View would only set
        // during draw; a 0x0 base on the first frame made the system reject the region.
        drawable.setBounds(0, 0, width, height)
        val outline = style.customShape?.createOutline(Size(width.toFloat(), height.toFloat()), LayoutDirection.Ltr, density)
        drawable.setPath((outline as? Outline.Generic)?.path?.asAndroidPath())
        val radius = when (style.shape) {
            BackdropShape.Circle, BackdropShape.Capsule -> minOf(width, height) / 2f
            else -> with(density) { style.cornerRadius.toPx() }
        }
        // The region's rect follows the parent's layer scale; its corner radii have to be told.
        drawable.setCornerRadius(radius * style.scale.coerceIn(0f, 1f))
        drawable.setColor(AndroidColor.TRANSPARENT)
        updateVisibility()
        invalidate()
    }
}

/**
 * Rime's own clock. Widgets stay Axion's native AppWidget views: re-hosting them would fight the
 * native host over the same widget ids, so the scene only reports where they belong.
 */
internal class SystemUIContentRenderer(private val onWidgetSlot: (RectF?) -> Unit,
    private val onClockSlot: (RectF?) -> Unit = {}) : LockscreenContentRenderer {
    @Composable
    override fun Clock(content: UiLockscreenContent, clock: UiClockState, appearance: ClockAppearance,
        foreground: Color, blurRadiusDp: Float, height: Dp, referenceHeight: Dp,
        editing: Boolean, onEdit: () -> Unit, onGestureStarted: () -> Unit,
        onGestureFinished: (Boolean) -> Unit, onMove: (Float, Float) -> Unit,
        onResize: (Float, Float) -> Unit, onEditDate: () -> Unit, modifier: Modifier) {
        if (content.hostedClock) {
            // Axion's real clock view is moved into this slot by RimeLockscreenController.
            DisposableEffect(Unit) { onDispose { onClockSlot(null) } }
            HostedClockSlot(content, height, editing, modifier.onGloballyPositioned {
                val b = it.boundsInRoot(); onClockSlot(RectF(b.left, b.top, b.right, b.bottom))
            })
            return
        }
        DefaultLockscreenContentRenderer.Clock(content, clock, appearance, foreground, blurRadiusDp, height, referenceHeight,
            editing, onEdit, onGestureStarted, onGestureFinished, onMove, onResize, onEditDate, modifier)
    }

    @Composable
    override fun Widgets(content: UiLockscreenContent, modifier: Modifier) {
        DisposableEffect(Unit) { onDispose { onWidgetSlot(null) } }
        Box(modifier.onGloballyPositioned { val b = it.boundsInRoot(); onWidgetSlot(RectF(b.left, b.top, b.right, b.bottom)) })
    }
}
