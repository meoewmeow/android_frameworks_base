/* Copyright (C) 2026 Rime contributors. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.rime

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shade.ShadeDisplayAware
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** Circuit breaker for Rime only. Never swallows the process's global uncaught exceptions. */
@SysUISingleton
class RimeFailureGuard @Inject constructor(
    @ShadeDisplayAware context: Context,
    @Main private val main: Handler,
) {
    private val context = context.createDeviceProtectedStorageContext()
    private val preferences = this.context.getSharedPreferences("rime_lockscreen_guard", Context.MODE_PRIVATE)
    private val failed = AtomicBoolean(preferences.getBoolean("failed", false))
    private val active = AtomicBoolean(false)
    @Volatile private var acknowledgement = SystemClock.uptimeMillis()
    var onFailure: (() -> Unit)? = null
    val isFailed: Boolean get() = failed.get()
    private val watchdogThread = HandlerThread("RimeLockscreenWatchdog").apply { start() }
    private val watchdog = Handler(watchdogThread.looper)

    init {
        // Armed in this same boot means SystemUI died while Rime was up (native death/ANR,
        // interrupted session): retry requires an explicit reset. Armed in an earlier boot is a
        // reboot or power-off while the lock screen showed, which is not a failure.
        if (preferences.getBoolean("armed", false)) {
            // Uptime only grows within a boot, so a smaller value than at arming means a reboot.
            if (SystemClock.elapsedRealtime() >= preferences.getLong("armedAt", Long.MAX_VALUE)) trip("unclean previous activation", null)
            else preferences.edit().putBoolean("armed", false).commit()
        }
        if (failed.get()) disableRequest()
        watchdog.post(object : Runnable {
            override fun run() {
                if (active.get()) {
                    if (SystemClock.uptimeMillis() - acknowledgement > HEARTBEAT_TIMEOUT_MS) {
                        // Logcat rolls over within the hour, so keep what the main thread was stuck in.
                        val stuckIn = main.looper.thread.stackTrace.take(STACK_FRAMES).joinToString("\n")
                        val persisted = trip("main thread heartbeat timeout", null, stuckIn)
                        // A blocked main Looper cannot restore views or service input. Let the
                        // existing persistent-app supervisor restart SystemUI in native mode.
                        // Never restart unless the disable latch was durably recorded.
                        if (persisted) Process.killProcess(Process.myPid())
                    } else main.post { acknowledgement = SystemClock.uptimeMillis() }
                }
                watchdog.postDelayed(this, 1000L)
            }
        })
    }

    fun arm(): Boolean {
        if (isFailed) return false
        if (active.get()) return true
        if (!preferences.edit().putBoolean("armed", true).putLong("armedAt", SystemClock.elapsedRealtime()).commit()) {
            trip("cannot persist activation", null); return false
        }
        acknowledgement = SystemClock.uptimeMillis()
        active.set(true)
        return true
    }
    fun disarm() {
        val wasActive = active.getAndSet(false)
        if (wasActive || preferences.getBoolean("armed", false)) preferences.edit().putBoolean("armed", false).commit()
    }
    fun resetIfRequested() {
        val nonce = Settings.Secure.getIntForUser(context.contentResolver, RESET_KEY, 0, UserHandle.USER_CURRENT)
        // A trip always switches Rime off, so the switch reading on again means the user turned it
        // back on: that is an explicit reset too. Automatic retries cannot clear it.
        val reenabled = Settings.Secure.getIntForUser(context.contentResolver, ENABLE_KEY, 0, UserHandle.USER_CURRENT) != 0
        if (nonce <= preferences.getInt("resetNonce", 0) && !reenabled) return
        active.set(false)
        if (preferences.edit().putBoolean("failed", false).putBoolean("armed", false).putInt("resetNonce", maxOf(nonce, preferences.getInt("resetNonce", 0))).commit()) failed.set(false)
    }
    fun <T> run(stage: String, block: () -> T): T? {
        if (isFailed) return null
        return try { block() } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            trip(stage, failure)
            if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
            null
        }
    }
    fun trip(stage: String, failure: Throwable?, mainStack: String? = null): Boolean {
        active.set(false)
        if (!failed.compareAndSet(false, true)) return preferences.getBoolean("failed", false)
        val stack = mainStack ?: failure?.stackTrace?.take(STACK_FRAMES)?.joinToString("\n")
        val durable = preferences.edit().putBoolean("failed", true).putBoolean("armed", false)
            .putString("failureStage", stage).putString("failureType", failure?.javaClass?.name ?: "watchdog")
            .putString("failureStack", stack).putLong("failureAt", System.currentTimeMillis()).commit()
        disableRequest()
        // Do not log notification content or Throwable messages.
        Log.e(TAG, "Disabled Rime at $stage (${failure?.javaClass?.name ?: "watchdog"})")
        stack?.lineSequence()?.forEach { Log.e(TAG, it) }
        val recover = Runnable {
            try { onFailure?.invoke() } catch (_: RuntimeException) { Log.e(TAG, "Failure cleanup callback failed; circuit remains open") }
        }
        if (Looper.myLooper() == main.looper) recover.run() else main.post(recover)
        return durable
    }
    private fun disableRequest() {
        try { Settings.Secure.putIntForUser(context.contentResolver, ENABLE_KEY, 0, UserHandle.USER_CURRENT) }
        catch (_: RuntimeException) { Log.e(TAG, "Could not update enable setting; durable failure latch remains active") }
    }
    companion object {
        const val ENABLE_KEY = "rime_lockscreen_enabled"
        const val RESET_KEY = "rime_lockscreen_reset_failures"
        private const val TAG = "RimeLockscreenGuard"
        private const val HEARTBEAT_TIMEOUT_MS = 4000L
        private const val STACK_FRAMES = 25
    }
}
