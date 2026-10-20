/* Copyright (C) 2026 Rime contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.rime

import android.content.Context
import android.database.ContentObserver
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.UserHandle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.android.systemui.Dumpable
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.data.repository.KeyguardClockRepository
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shared.clocks.ClockSettingsRepository
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView
import com.android.systemui.statusbar.policy.KeyguardStateController
import dev.rime.ui.RimeUiRoot
import dev.rime.ui.UiScene
import dev.rime.ui.lockscreen.LockscreenSceneState
import java.io.PrintWriter
import javax.inject.Inject

/** Optional presentation subtree. A failed subtree never owns native authentication or unlock. */
@SysUISingleton
class RimeLockscreenController @Inject constructor(
    @ShadeDisplayAware private val context: Context,
    @Main private val handler: Handler,
    private val hostProvider: dagger.Lazy<RimeSystemUIHost>,
    private val status: StatusBarStateController,
    private val clocks: dagger.Lazy<KeyguardClockRepository>,
    private val keyguard: KeyguardStateController,
    private val primaryBouncer: PrimaryBouncerInteractor,
    private val alternateBouncer: AlternateBouncerInteractor,
    private val shadeController: dagger.Lazy<ShadeController>,
    private val guard: RimeFailureGuard,
    dumpManager: DumpManager,
) : Dumpable {
    private var root: ViewGroup? = null
    private var surface: RimeProtectedSurface? = null
    private var enabled = false
    private val hiddenViews = mutableMapOf<View, Rect?>()
    private var rowBlurSuppressed = false
    private var widgetSlot: RectF? = null
    private val widgetLocation = IntArray(2)
    private val widgetLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> guard.run("widget layout") { placeWidgets() } }
    private var widgetArea: View? = null
    // Axion's own clock faces: their real view is moved into the clock slot the scene leaves.
    private var hostedClock = false
    private var clockSlot: RectF? = null
    private var hostedClockView: View? = null
    private val clockLocation = IntArray(2)
    private val clockLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> guard.run("clock layout") { placeClock() } }
    private var lastReason = ""
    var isShowing = false
        private set
    private var ownsGesture = false
    private var dropGestureUntilUp = false
    private var scene by mutableStateOf(UiScene.Lockscreen)
    private var sceneState by mutableStateOf(LockscreenSceneState())
    private var customizing = false
    private var observing = false
    private var initializationStartedAt = 0L
    private val firstFrameToken = Any()
    private var firstFrameAwaitsWake = false
    /**
     * Native notifications: Android's own rows in its own lock screen deck instead of Rime's deck.
     * Secure rime_native_notifications, on by default; off restores Rime's deck.
     */
    private var nativeNotifications = true
    /** Rime's clock on the always-on display (Secure rime_aod_clock, on by default). */
    private var aodClock = true
    private var nativeStackOn = false
    private val nativeHitLocation = IntArray(2)
    // Spatial tilt. SystemUI owns it while the scene shows and sends it to the wallpaper, so the
    // subject and the clock's cut-out move by exactly the same amount. Mirrors CinematicService.
    private var spatialActive = false
    private var spatialStrength = 1f
    private var tiltX = 0f
    private var tiltY = 0f
    private var tiltTimestamp = 0L
    private var tiltRegistered = false
    private val tiltListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            if (!guard.isFailed) guard.run("spatial tilt") { onTilt(event) }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }
    /** True from the moment an unlock is accepted until the lock screen is gone. */
    private var unlocking = false
    private val keyguardCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardGoingAwayChanged() { if (!guard.isFailed) guard.run("going away callback") { updateVisibility() } }
        override fun onKeyguardFadingAwayChanged() { if (!guard.isFailed) guard.run("fading away callback") { updateVisibility() } }
        override fun onKeyguardShowingChanged() { if (!guard.isFailed) guard.run("showing callback") { updateVisibility() } }
    }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            guard.run("settings callback") { reloadSettings() }
            if (guard.isFailed) {
                // Recovery reset must remain available while the normal circuit is open.
                guard.resetIfRequested()
                if (!guard.isFailed) guard.run("rearmed settings") { reloadSettings() }
            }
        }
    }
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (!guard.isFailed) guard.run("visibility callback") { updateVisibility() }
        else releaseSkin()
        true
    }
    private val tick = object : Runnable {
        override fun run() {
            if (isShowing) guard.run("state refresh") { hostProvider.get().refresh() }
            if (observing) handler.postDelayed(this, 30_000L)
        }
    }
    private val attachmentListener = object : View.OnAttachStateChangeListener {
        // Not inside guard.run: that skips its block while the latch is tripped, and then the
        // settings observer that hears an explicit reset request would never be registered.
        override fun onViewAttachedToWindow(view: View) {
            try { beginObserving() } catch (failure: RuntimeException) { guard.trip("window attach", failure) }
        }
        override fun onViewDetachedFromWindow(view: View) {
            try { endObserving() } catch (failure: RuntimeException) { guard.trip("window detach", failure) }
        }
    }

    init {
        guard.onFailure = { handler.post { releaseSkin() } }
        dumpManager.registerDumpable("RimeLockscreen", this)
    }

    fun attach(window: ViewGroup) {
        if (root === window) return
        endObserving()
        root?.removeOnAttachStateChangeListener(attachmentListener)
        root = window
        // Observer is the small recovery/control path; no host or composition is initialized here.
        try {
            reloadSettings()
            window.addOnAttachStateChangeListener(attachmentListener)
            if (window.isAttachedToWindow) beginObserving()
        } catch (failure: RuntimeException) { guard.trip("controller attach", failure) }
    }
    private fun beginObserving() {
        if (observing) return
        observing = true
        context.contentResolver.registerContentObserver(Settings.Secure.CONTENT_URI, true, observer, UserHandle.USER_ALL)
        root?.viewTreeObserver?.addOnPreDrawListener(preDrawListener)
        keyguard.addCallback(keyguardCallback)
        handler.post(tick)
    }
    private fun endObserving() {
        if (observing) {
            observing = false
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
            root?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDrawListener)
            runCatching { keyguard.removeCallback(keyguardCallback) }
            handler.removeCallbacks(tick)
        }
        releaseSkin()
    }
    private fun reloadSettings() {
        enabled = Settings.Secure.getIntForUser(context.contentResolver, RimeFailureGuard.ENABLE_KEY, 0, UserHandle.USER_CURRENT) != 0
        val resolver = context.contentResolver
        spatialActive = Settings.Secure.getIntForUser(resolver, "ax_spatial_mode", 0, UserHandle.USER_CURRENT) == 1 &&
            Settings.Secure.getIntForUser(resolver, "rime_spatial_layers", 0, UserHandle.USER_CURRENT) == 1 &&
            runCatching { android.app.WallpaperManager.getInstance(context).getWallpaperInfo(android.app.WallpaperManager.FLAG_LOCK)
                ?.serviceName?.endsWith("CinematicService") == true }.getOrDefault(false)
        spatialStrength = Settings.Secure.getFloatForUser(resolver, "ax_spatial_strength", 0.25f, UserHandle.USER_CURRENT).coerceIn(0.25f, 2f)
        updateTilt()
        aodClock = Settings.Secure.getIntForUser(resolver, AOD_CLOCK_KEY, 1, UserHandle.USER_CURRENT) != 0
        val native = Settings.Secure.getIntForUser(resolver, NATIVE_NOTIFICATIONS_KEY, 1, UserHandle.USER_CURRENT) != 0
        if (native != nativeNotifications) {
            nativeNotifications = native
            // Re-show with the other notification owner: concealment and blur both depend on it.
            if (isShowing) { suspendSkin(); handler.post { if (!guard.isFailed) guard.run("notification owner switch") { updateVisibility() } } }
        }
        if (!enabled) releaseSkin()
        else if (isShowing) handler.post { guard.run("settings refresh") { hostProvider.get().refresh() } }
    }
    private fun createSurface(window: ViewGroup): Boolean {
        if (surface != null) return true
        if (!guard.arm()) return false
        return guard.run("presentation initialization") {
            val host = hostProvider.get()
            if (guard.isFailed) return@run false
            val view = RimeProtectedSurface(window.context, guard)
            // Assign before attach so recovery can dispose a partially initialized subtree.
            surface = view
            initializationStartedAt = SystemClock.uptimeMillis()
            view.visibility = View.GONE
            val renderer = SystemUIBackdropRenderer(guard)
            val contentRenderer = SystemUIContentRenderer(
                onWidgetSlot = { slot -> guard.run("widget slot") { widgetSlot = slot; surface?.widgetSlot = slot; placeWidgets() } },
                onClockSlot = { slot -> guard.run("clock slot") { clockSlot = slot; placeClock() } })
            view.setContent {
                RimeUiRoot(scene = scene, sceneState = sceneState, host = host, backdropRenderer = renderer,
                    lockscreenContentRenderer = contentRenderer,
                    onSceneChanged = { destination -> guard.run("scene action") { when (destination) {
                        UiScene.ControlCenter -> shadeController.get().animateExpandQs()
                        UiScene.NotificationCenter, UiScene.Lockscreen -> scene = destination
                        UiScene.Unlocked -> host.requestUnlock()
                    } } }, onDeveloperGesture = { host.openNativeEditor() },
                    modifier = Modifier.fillMaxSize())
            }
            val index = window.findViewById<View>(R.id.scrim_in_front)?.let(window::indexOfChild)?.takeIf { it >= 0 } ?: window.childCount
            window.addView(view, index, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            !guard.isFailed
        } == true
    }
    private fun updateVisibility() {
        val window = root ?: return
        val reason = when {
            !enabled -> "disabled"
            !keyguard.isShowing || keyguard.isOccluded -> "not keyguard"
            // Accepted unlock: leave together with the native unlock animation, not after it.
            keyguard.isKeyguardGoingAway || keyguard.isKeyguardFadingAway -> "unlocking"
            status.state != StatusBarState.KEYGUARD -> "native shade"
            status.isDozing && !aodClock -> "native AOD"
            // The fingerprint prompt and the unlock animation sit over the lock screen, so ours stays
            // under them; stepping aside there flashed the native clock and notifications.
            primaryBouncer.isBouncerShowing() -> "native authentication"
            else -> "shared lockscreen"
        }
        // Any face but Axion's wide big clock is Axion's own: shown inside the scene, not instead of it.
        val hosted = !ClockSettingsRepository.clockId.value.startsWith("OPLUS_BIG") ||
            ClockSettingsRepository.oplusBigFace.value != ClockSettingsRepository.OPLUS_BIG_FACE_WIDE
        if (hosted != hostedClock) {
            hostedClock = hosted
            if (isShowing) suspendSkin()
        }
        if (reason != lastReason) {
            Log.i("RimeLockscreen", reason)
            if (reason == "not keyguard") {
                scene = UiScene.Lockscreen
                sceneState = LockscreenSceneState()
            }
            lastReason = reason
        }
        if (reason == "unlocking") { fadeOutForUnlock(); return }
        if (reason != "shared lockscreen") {
            // Preserve the same presentation tree across authentication/AOD/native panels.
            // Disable, unsupported content and failure still dispose it completely.
            if (reason == "disabled") releaseSkin()
            else suspendSkin()
            return
        }
        if (!createSurface(window)) { releaseSkin(); return }
        if (!isShowing) {
            if (!guard.arm()) { releaseSkin(); return }
            hostProvider.get().refresh()
        }
        if (guard.isFailed) { releaseSkin(); return }
        val view = surface ?: return
        // An unlock that was abandoned (e.g. a cancelled swipe) brings the scene back.
        if (unlocking) { unlocking = false; resetUnlockFade(view) }
        if (view.visibility != View.VISIBLE) {
            // Decided before the first frame, so the two decks never show together.
            hostProvider.get().setNativeNotifications(nativeNotifications)
            hostProvider.get().setHostedClock(hostedClock, ClockSettingsRepository.clockId.value, null)
            initializationStartedAt = SystemClock.uptimeMillis()
            firstFrameAwaitsWake = false
            view.prepareToShow()
            view.visibility = View.VISIBLE
        }
        if (isShowing) { hostProvider.get().setDozing(status.isDozing); updateTilt() }
        // Do not conceal native content until a presentation frame has actually drawn.
        if (surface?.hasRenderedFrame != true) {
            checkFirstFrame(view)
            return
        }
        isShowing = true
        hostProvider.get().setDozing(status.isDozing)
        updateTilt()
        val clock = clocks.get().currentClock.value
        // Axion shows its small clock view on this ROM; a hosted face uses whichever is showing.
        val faceView = if (hostedClock) listOfNotNull(clock?.smallClock?.view, clock?.largeClock?.view)
            .firstOrNull { it.visibility == View.VISIBLE } else null
        // With native notifications Android's list stays visible and keeps its own glass.
        for (view in listOfNotNull(clock?.largeClock?.view, clock?.smallClock?.view,
                window.findViewById<View>(R.id.shared_notification_container).takeUnless { nativeNotifications })) {
            if (view === faceView) {
                // Concealed on an earlier frame, before it was the face showing: give it back.
                if (hiddenViews.containsKey(view)) view.clipBounds = hiddenViews.remove(view)
                continue
            }
            if (!hiddenViews.containsKey(view)) hiddenViews[view] = view.clipBounds?.let(::Rect)
            view.clipBounds = Rect()
        }
        setNativeRowBlurSuppressed(!nativeNotifications)
        setNativeStack(nativeNotifications)
        if (faceView !== hostedClockView) {
            hostedClockView?.let { it.removeOnLayoutChangeListener(clockLayoutListener); it.translationX = 0f; it.translationY = 0f }
            faceView?.addOnLayoutChangeListener(clockLayoutListener)
            hostedClockView = faceView
        }
        placeClock()
        placeWidgets()
    }
    /**
     * Moves the hosted Axion clock view into the scene's clock slot, top-centred, and tells the host
     * how tall it is so the scene keeps exactly that much room for it.
     */
    private fun placeClock() {
        val view = hostedClockView ?: return
        val slot = clockSlot
        if (surface != null) hostProvider.get().setHostedClock(true, ClockSettingsRepository.clockId.value,
            view.height / context.resources.displayMetrics.density)
        if (!isShowing || slot == null) { view.translationX = 0f; view.translationY = 0f; return }
        view.getLocationInWindow(clockLocation)
        val baseX = clockLocation[0] - view.translationX
        val baseY = clockLocation[1] - view.translationY
        view.translationX = slot.centerX() - view.width / 2f - baseX
        view.translationY = slot.top - baseY
    }
    /**
     * Moves Axion's native widget area into the slot the scene reserved under the clock, and tells
     * the host how tall it is so the scene reserves exactly that much.
     */
    private fun placeWidgets() {
        val area = root?.findViewById<View>(R.id.keyguard_widgets_area)
        if (area !== widgetArea) {
            widgetArea?.removeOnLayoutChangeListener(widgetLayoutListener)
            area?.addOnLayoutChangeListener(widgetLayoutListener)
            widgetArea = area
        }
        area ?: return
        val hasWidgets = ClockSettingsRepository.lockscreenWidgetLayoutState.value.hasWidgets
        // Only an existing surface has a host; creating one here would initialize it while disabled or tripped.
        if (surface != null) hostProvider.get().setWidgetHeightDp(if (hasWidgets && isShowing) area.height / context.resources.displayMetrics.density else 0f)
        val slot = widgetSlot
        if (!isShowing || !hasWidgets || slot == null) { area.translationX = 0f; area.translationY = 0f; return }
        area.getLocationInWindow(widgetLocation)
        val baseX = widgetLocation[0] - area.translationX
        val baseY = widgetLocation[1] - area.translationY
        area.translationX = slot.centerX() - area.width / 2f - baseX
        area.translationY = slot.top - baseY
    }
    private fun isInWidgetSlot(x: Float, y: Float) =
        ClockSettingsRepository.lockscreenWidgetLayoutState.value.hasWidgets && widgetSlot?.contains(x, y) == true
    /** Native deck and Rime's own list are exclusive: the host publishes no rows while native shows. */
    private fun setNativeStack(on: Boolean) {
        if (nativeStackOn == on) return
        nativeStackOn = on
        com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.setRimeStackEnabled(on)
    }
    /** Whether ([x], [y]) in window pixels lands on a visible native notification row. */
    private fun isOverNativeNotification(x: Float, y: Float): Boolean {
        val container = root?.findViewById<ViewGroup>(R.id.shared_notification_container) ?: return false
        fun hit(group: ViewGroup): Boolean {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child.visibility != View.VISIBLE || child.alpha <= 0f) continue
                if (child is com.android.systemui.statusbar.notification.row.ExpandableView) {
                    child.getLocationInWindow(nativeHitLocation)
                    val top = nativeHitLocation[1] + child.clipTopAmount * child.scaleY
                    val bottom = nativeHitLocation[1] + child.actualHeight * child.scaleY
                    if (x >= nativeHitLocation[0] && x <= nativeHitLocation[0] + child.width * child.scaleX && y in top..bottom) return true
                } else if (child is ViewGroup && hit(child)) return true
            }
            return false
        }
        return hit(container)
    }
    /** A clipped row still holds its SurfaceFlinger blur region, which shows as a second, empty card. */
    private fun setNativeRowBlurSuppressed(suppressed: Boolean) {
        if (rowBlurSuppressed == suppressed) return
        rowBlurSuppressed = suppressed
        ActivatableNotificationView.setBackdropBlurSuppressed(suppressed)
        fun refresh(view: View) {
            if (view is ActivatableNotificationView) view.updateIfNeeded()
            if (view is ViewGroup) for (i in 0 until view.childCount) refresh(view.getChildAt(i))
        }
        root?.findViewById<View>(R.id.shared_notification_container)?.let(::refresh)
    }
    /**
     * Fades the scene, its glass included, over the native unlock animation. Native clock and
     * notifications stay concealed: restoring them now is exactly the flash this avoids.
     */
    private fun fadeOutForUnlock() {
        val view = surface
        if (unlocking) return
        unlocking = true
        stopTilt()
        cancelGesture()
        if (view == null || !isShowing || view.visibility != View.VISIBLE) return
        isShowing = false
        val duration = keyguard.shortenedFadingAwayDuration.takeIf { it in 1..400 } ?: UNLOCK_FADE_MS
        view.animate().cancel()
        view.animate().alpha(0f).scaleX(UNLOCK_SCALE).scaleY(UNLOCK_SCALE).setDuration(duration)
            .setInterpolator(android.view.animation.PathInterpolator(0.3f, 0f, 0.2f, 1f))
            .setUpdateListener { guard.run("unlock fade") { refreshBlurRegions(view) } }
            .withEndAction { guard.run("unlock fade end") { refreshBlurRegions(view) } }
            .start()
    }
    private fun resetUnlockFade(view: View) {
        view.animate().setUpdateListener(null).cancel()
        view.alpha = 1f; view.scaleX = 1f; view.scaleY = 1f
        refreshBlurRegions(view)
    }
    /**
     * Trips the guard if the surface is shown but never draws. Time with the screen off or dozing
     * does not count: the keyguard is often created while the display is off (locking from an
     * app), and nothing draws until it wakes. No polling while dozing (it would wake the CPU all
     * night); waking redraws the window, whose pre-draw pass calls this again. Awake, it keeps
     * polling so the timeout still fires if the window stops redrawing.
     */
    private fun checkFirstFrame(view: RimeProtectedSurface) {
        handler.removeCallbacksAndMessages(firstFrameToken)
        if (surface !== view || view.visibility != View.VISIBLE || view.hasRenderedFrame || guard.isFailed) return
        val now = SystemClock.uptimeMillis()
        if (status.isDozing) { firstFrameAwaitsWake = true; return }
        // Count from the wake, not from when the surface was shown on a dark screen.
        if (firstFrameAwaitsWake) { firstFrameAwaitsWake = false; initializationStartedAt = now }
        if (now - initializationStartedAt > FIRST_FRAME_TIMEOUT_MS) { guard.trip("first frame timeout", null); return }
        handler.postAtTime({ checkFirstFrame(view) }, firstFrameToken, now + FIRST_FRAME_POLL_MS)
    }
    private fun suspendSkin() {
        unlocking = false
        runCatching { stopTilt() }
        surface?.let { runCatching { resetUnlockFade(it) } }
        isShowing = false
        cancelGesture()
        sceneState.closeDetails()
        runCatching { surface?.visibility = View.GONE }
        hiddenViews.forEach { (view, clip) -> runCatching { view.clipBounds = clip } }
        hiddenViews.clear()
        runCatching { setNativeRowBlurSuppressed(false) }
        runCatching { setNativeStack(false) }
        runCatching { placeWidgets() }
        hostedClockView?.let { view -> runCatching {
            view.removeOnLayoutChangeListener(clockLayoutListener); view.translationX = 0f; view.translationY = 0f
        } }
        hostedClockView = null
        guard.disarm()
    }
    private fun updateTilt() {
        // No gyro on the always-on display: nothing moves there, and it would cost battery all night.
        if (isShowing && spatialActive && !unlocking && !status.isDozing) startTilt() else stopTilt()
    }
    private fun startTilt() {
        if (tiltRegistered) return
        val sensors = context.getSystemService(android.hardware.SensorManager::class.java) ?: return
        val gyro = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) ?: return
        tiltRegistered = sensors.registerListener(tiltListener, gyro, android.hardware.SensorManager.SENSOR_DELAY_GAME, handler)
        tiltTimestamp = 0L
    }
    private fun stopTilt() {
        if (tiltRegistered) {
            tiltRegistered = false
            context.getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(tiltListener)
        }
        val moved = tiltX != 0f || tiltY != 0f
        tiltX = 0f; tiltY = 0f; tiltTimestamp = 0L
        if (moved) publishTilt()
    }
    private fun onTilt(event: android.hardware.SensorEvent) {
        val now = event.timestamp
        if (tiltTimestamp != 0L) {
            val dt = (now - tiltTimestamp) / 1_000_000_000f
            tiltX = ((tiltX + event.values[1] * dt * GYRO_SENSITIVITY).coerceIn(-GYRO_MAX_OFFSET, GYRO_MAX_OFFSET)) * GYRO_DECAY
            tiltY = ((tiltY + event.values[0] * dt * GYRO_SENSITIVITY).coerceIn(-GYRO_MAX_OFFSET, GYRO_MAX_OFFSET)) * GYRO_DECAY
            // At rest the decay never quite reaches zero; stop sending once it is invisible.
            if (kotlin.math.abs(tiltX) < 1e-4f && kotlin.math.abs(tiltY) < 1e-4f) {
                if (tiltX != 0f || tiltY != 0f) { tiltX = 0f; tiltY = 0f; publishTilt() }
            } else publishTilt()
        }
        tiltTimestamp = now
    }
    /** Sends the tilt to the wallpaper and moves the clock's cut-out with the subject. */
    private fun publishTilt() {
        val window = root ?: return
        window.windowToken?.let { token ->
            val extras = android.os.Bundle().apply { putFloat("rime_spatial_x", tiltX); putFloat("rime_spatial_y", tiltY) }
            runCatching { android.app.WallpaperManager.getInstance(context)
                .sendWallpaperCommand(token, LOCKSCREEN_LAYOUT_COMMAND, 0, 0, 0, extras) }
        }
        // The wallpaper's full-screen quad maps its photo onto the window, so a UV shift is a
        // fraction of the window size. Same formula as cinematic_frag.glsl's subjectUV.
        val travel = SPATIAL_PARALLAX * spatialStrength * SPATIAL_SUBJECT_GAIN
        if (surface != null) hostProvider.get().setDepthShift(tiltX * travel * window.width, tiltY * travel * window.height)
    }
    private fun cancelGesture() {
        if (!ownsGesture) return
        ownsGesture = false
        dropGestureUntilUp = true
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try { runCatching { surface?.dispatchTouchEvent(cancel) } } finally { cancel.recycle() }
        sceneState.jumpTo(sceneState.expansion)
    }
    private fun releaseSkin() {
        suspendSkin()
        customizing = false
        // Each cleanup operation is independent; one bad custom node cannot prevent native restore.
        val old = surface
        if (old == null) return
        surface = null
        runCatching { old.close() }
        runCatching { (old.parent as? ViewGroup)?.removeView(old) }
        scene = UiScene.Lockscreen
        sceneState = LockscreenSceneState()
    }
    /** Null leaves native routing intact; true owns an entire shared gesture stream. */
    fun dispatchTouch(event: MotionEvent): Boolean? {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) dropGestureUntilUp = false
        if (dropGestureUntilUp) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                dropGestureUntilUp = false
            return true
        }
        if (guard.isFailed) return null
        val eligible = isShowing && keyguard.isShowing && !keyguard.isOccluded &&
                status.state == StatusBarState.KEYGUARD && !status.isDozing &&
                !primaryBouncer.isBouncerShowing() && !alternateBouncer.isVisibleState()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) ownsGesture = eligible &&
            (customizing || event.y < (root?.height ?: 0) - 118f * context.resources.displayMetrics.density) &&
            !isInWidgetSlot(event.x, event.y) &&
            // Native rows take their own taps, swipes, replies and the deck's drag.
            !(nativeStackOn && !customizing && isOverNativeNotification(event.x, event.y))
        if (!ownsGesture) return null
        // A hold that asks for unlock brings up the prompt mid-gesture: end the gesture, and leave
        // showing or hiding the scene to updateVisibility.
        if (!eligible) { cancelGesture(); return true }
        guard.run("shared input dispatch") { surface?.dispatchTouchEvent(event) }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
            ownsGesture = false
        // A shared gesture must never fall through into native drag-down or unlock handlers.
        return true
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("hostedClock=$hostedClock view=${hostedClockView != null} slot=$clockSlot")
        pw.println("nativeNotifications=$nativeNotifications nativeStack=$nativeStackOn aodClock=$aodClock dozing=${status.isDozing}")
        pw.println("unlocking=$unlocking alpha=${surface?.alpha} spatial=$spatialActive tilt=$tiltRegistered ($tiltX, $tiltY)")
        pw.println("enabled=$enabled showing=$isShowing failed=${guard.isFailed} reason=$lastReason")
        pw.println("scene=$scene expansion=${sceneState.expansion} scroll=${sceneState.scrollOffsetPx}")
        pw.println("surface=${surface != null} firstFrame=${surface?.hasRenderedFrame == true} customizing=$customizing")
        pw.println("nativeClippedViews=${hiddenViews.size} rowBlurSuppressed=$rowBlurSuppressed ownsGesture=$ownsGesture dropGestureUntilUp=$dropGestureUntilUp")
        pw.println("deckHitTop=${sceneState.hitTopPx} deckHitBottom=${sceneState.hitBottomPx}")
        pw.println("notifications=${if (surface != null) hostProvider.get().notifications.value.size else 0}")
        pw.println("measuredCardHeights=${sceneState.heights.values.toList()}")
    }
}

private const val UNLOCK_FADE_MS = 200L
private const val FIRST_FRAME_TIMEOUT_MS = 3000L
private const val FIRST_FRAME_POLL_MS = 500L
private const val NATIVE_NOTIFICATIONS_KEY = "rime_native_notifications"
private const val AOD_CLOCK_KEY = "rime_aod_clock"
// Same values as AxWallpaperEffects' CinematicService / CinematicRenderer / cinematic_frag.glsl.
private const val GYRO_SENSITIVITY = 2.4f
private const val GYRO_MAX_OFFSET = 0.45f
private const val GYRO_DECAY = 0.92f
private const val SPATIAL_PARALLAX = 0.085f
private const val SPATIAL_SUBJECT_GAIN = 0.5f
private const val LOCKSCREEN_LAYOUT_COMMAND = "android.wallpaper.lockscreen_layout_changed"
private const val UNLOCK_SCALE = 0.96f
