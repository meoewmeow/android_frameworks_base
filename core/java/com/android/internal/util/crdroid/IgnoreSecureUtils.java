/*
 * Copyright (C) 2026 The crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util.crdroid;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.SystemProperties;
import android.provider.Settings;

/**
 * Cached accessor for {@link Settings.Global#WINDOW_IGNORE_SECURE}.
 *
 * <p>Donor: Evolution X frameworks_base@bka.
 *
 * <p>crDroid adaptation: the call sites for this flag are extremely hot and, in
 * the case of {@code WindowManagerGlobal.addView()}, run while holding
 * {@code WindowManagerGlobal.mLock}; {@code updateViewLayout()} runs on every
 * keyguard/notification-shade layout pass. SettingsProvider lives inside
 * system_server ({@code android:process="system"}), so a synchronous provider
 * read from those paths is an in-process re-entry under a held lock, and during
 * early boot it can block on the provider being published at all.
 *
 * <p>Therefore this class never reads the provider on the caller's thread. The
 * value is warmed on a background thread and kept fresh through a content
 * observer; until it is known the answer is {@code false}, which preserves the
 * stock FLAG_SECURE behaviour. The flag only affects windows as they are
 * created or relaid out, so a brief warm-up is not observable in practice.
 */
public final class IgnoreSecureUtils {

    private static final Object sLock = new Object();

    /** Cached value; {@code null} means "not known yet". */
    private static volatile Boolean sIgnoreSecure = null;
    private static boolean sRefreshScheduled = false;
    private static boolean sObserverRegistered = false;

    private IgnoreSecureUtils() {
    }

    /**
     * @return whether FLAG_SECURE should be ignored. Never performs I/O on the
     *         calling thread and never throws.
     */
    public static boolean shouldIgnoreSecure(ContentResolver cr) {
        if (cr == null) {
            return false;
        }

        final Boolean cached = sIgnoreSecure;
        if (cached != null) {
            return cached;
        }

        // The toggle lives in the Settings UI, which cannot have run before boot
        // completes. Skipping the warm-up here also keeps early boot free of any
        // settings traffic from the window paths.
        if (!SystemProperties.getBoolean("sys.boot_completed", false)) {
            return false;
        }

        scheduleRefresh(cr);
        return false;
    }

    private static void scheduleRefresh(ContentResolver cr) {
        synchronized (sLock) {
            if (sRefreshScheduled) {
                return;
            }
            sRefreshScheduled = true;
        }

        try {
            final Thread t = new Thread(() -> refresh(cr), "IgnoreSecureUtils");
            t.setDaemon(true);
            t.start();
        } catch (Throwable throwable) {
            // Could not spawn a warm-up thread; stay on the safe default and
            // allow a later call to try again.
            synchronized (sLock) {
                sRefreshScheduled = false;
            }
        }
    }

    private static void refresh(ContentResolver cr) {
        try {
            sIgnoreSecure = Settings.Global.getInt(
                    cr, Settings.Global.WINDOW_IGNORE_SECURE, 0) == 1;
            registerObserver(cr);
        } catch (Throwable throwable) {
            // Leave the value unknown so the safe default keeps applying, and
            // let a later call retry the warm-up.
            synchronized (sLock) {
                sRefreshScheduled = false;
            }
        }
    }

    private static void registerObserver(ContentResolver cr) {
        synchronized (sLock) {
            if (sObserverRegistered) {
                return;
            }
            try {
                cr.registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.WINDOW_IGNORE_SECURE),
                        false,
                        new ContentObserver(null) {
                            @Override
                            public void onChange(boolean selfChange, Uri uri) {
                                try {
                                    sIgnoreSecure = Settings.Global.getInt(
                                            cr, Settings.Global.WINDOW_IGNORE_SECURE, 0) == 1;
                                } catch (Throwable throwable) {
                                    // Keep the previous value.
                                }
                            }
                        });
                sObserverRegistered = true;
            } catch (Throwable throwable) {
                // Without an observer the cached value simply persists for the
                // lifetime of the process.
            }
        }
    }
}
