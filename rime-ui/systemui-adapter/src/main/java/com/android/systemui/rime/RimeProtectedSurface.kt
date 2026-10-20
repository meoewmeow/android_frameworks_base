/* Copyright (C) 2026 Rime contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.rime

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.AndroidUiDispatcher
import com.android.axion.compose.lifecycle.AxComposeInitializer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Owns its recomposer and catches synchronous failures before they leave the presentation subtree. */
internal class RimeProtectedSurface(context: Context, private val guard: RimeFailureGuard) : FrameLayout(context) {
    private val scope = CoroutineScope(AndroidUiDispatcher.Main + SupervisorJob() + CoroutineExceptionHandler { _, failure ->
        guard.trip("composition coroutine", failure)
        if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
    })
    private val recomposer = Recomposer(scope.coroutineContext)
    private val compose = object : AbstractComposeView(context) {
        var body: (@Composable () -> Unit)? = null
        override val shouldCreateCompositionOnAttachedToWindow: Boolean get() = true
        @Composable override fun Content() { body?.invoke() }
        override fun onAttachedToWindow() {
            guard.run("composition attach") { super.onAttachedToWindow() }
        }
    }
    private var passThrough = false
    var customizing = false
    /** Native widgets sit under this surface here; their touches go to them. */
    var widgetSlot: android.graphics.RectF? = null
    private var closed = false
    var hasRenderedFrame = false
        private set
    init {
        compose.setParentCompositionContext(recomposer)
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    fun setContent(content: @Composable () -> Unit) { compose.body = content }
    fun prepareToShow() { hasRenderedFrame = false }
    override fun onAttachedToWindow() {
        guard.run("surface attach") {
            AxComposeInitializer.onAttachedToWindow(this)
            super.onAttachedToWindow()
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (guard.run("measure") { super.onMeasure(widthMeasureSpec, heightMeasureSpec); true } != true)
            setMeasuredDimension(0, 0)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        guard.run("layout") { super.onLayout(changed, left, top, right, bottom) }
    }
    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        try {
            if (guard.run("draw") { super.dispatchDraw(canvas); compose.hasComposition && compose.childCount > 0 && width > 0 && height > 0 } == true) hasRenderedFrame = true
        }
        finally { canvas.restoreToCount(save) }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN)
            passThrough = !customizing && (event.y >= height - 118f * resources.displayMetrics.density ||
                widgetSlot?.contains(event.x, event.y) == true)
        return if (passThrough || guard.isFailed) false else guard.run("input") { super.dispatchTouchEvent(event) } ?: false
    }
    fun close() {
        if (closed) return
        closed = true
        // Recovery cleanup must run even though the circuit breaker is open.
        runCatching { compose.disposeComposition() }
        recomposer.cancel()
        scope.cancel()
    }
    override fun onDetachedFromWindow() {
        close()
        runCatching { AxComposeInitializer.onDetachedFromWindow(this) }
        super.onDetachedFromWindow()
    }
}
