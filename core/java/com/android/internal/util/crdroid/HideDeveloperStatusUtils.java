/*
 * Copyright (C) 2025-2026 AxionOS
 * Copyright (C) 2026 The Infinity X Project
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
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.SystemProperties;
import android.provider.Settings;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Hides ADB / developer options status from packages selected by the user.
 *
 * <p>Adapted from Infinity X (frameworks_base@16-QPR1,
 * com.android.internal.util.infinity.HideDeveloperStatusUtils).
 *
 * <p>crDroid adaptation: this is called from Settings.System/Secure/Global.getInt()
 * for every settings read in the system, so it must stay allocation- and I/O-free
 * for unrelated keys. The watched key is checked first, nothing runs before
 * boot completes, and the package list is cached and refreshed through a content
 * observer instead of reading the provider on every call.
 */
public class HideDeveloperStatusUtils {
    private static final Set<String> settingsToHide =
        new HashSet<>(
            Arrays.asList(
                Settings.Global.ADB_ENABLED,
                Settings.Global.ADB_WIFI_ENABLED,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
            ));

    private static final Object sCacheLock = new Object();

    /** Cached hidden-package set; null means "read it again from the provider". */
    private static volatile Set<String> sCachedApps = null;
    private static volatile boolean sObserverRegistered = false;

    enum Action {
        ADD,
        REMOVE,
        SET
    }

    public static boolean shouldHideDevStatus(
            ContentResolver cr, String packageName, String name) {
        if (cr == null || packageName == null || name == null) {
            return false;
        }

        // Cheap reject for every settings read that this feature does not care
        // about. Must stay the first check: this method is called from
        // Settings.*.getInt() all over the system, including during boot.
        if (!settingsToHide.contains(name)) {
            return false;
        }

        // The list can only be configured through the Settings UI, which cannot
        // exist before boot completes.
        if (!SystemProperties.getBoolean("sys.boot_completed", false)) {
            return false;
        }

        try {
            Set<String> apps = getAppsCached(cr);
            return !apps.isEmpty() && apps.contains(packageName);
        } catch (Throwable t) {
            // Never break a settings read because of this feature.
            return false;
        }
    }

    private static Set<String> getAppsCached(ContentResolver cr) {
        Set<String> cached = sCachedApps;
        if (cached != null) {
            return cached;
        }

        synchronized (sCacheLock) {
            cached = sCachedApps;
            if (cached == null) {
                cached = getApps(cr);
                sCachedApps = cached;
                registerObserverLocked(cr);
            }
            return cached;
        }
    }

    private static void registerObserverLocked(ContentResolver cr) {
        if (sObserverRegistered) {
            return;
        }
        try {
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.HIDE_DEVELOPER_STATUS),
                    false,
                    new ContentObserver(null) {
                        @Override
                        public void onChange(boolean selfChange, Uri uri) {
                            sCachedApps = null;
                        }
                    });
            sObserverRegistered = true;
        } catch (Throwable t) {
            // Caching simply stays for the lifetime of the process.
        }
    }

    private static Set<String> getApps(Context context) {
        if (context == null) {
            return new HashSet<>();
        }

        return getApps(context.getContentResolver());
    }

    private static Set<String> getApps(ContentResolver cr) {
        if (cr == null) {
            return new HashSet<>();
        }

        String apps = Settings.Secure.getString(cr, Settings.Secure.HIDE_DEVELOPER_STATUS);
        if (apps != null && !apps.isEmpty() && !apps.equals(",")) {
            return new HashSet<>(Arrays.asList(apps.split(",")));
        }

        return new HashSet<>();
    }

    private static void putAppsForUser(
            Context context, String packageName,
            int userId, Action action) {
        if (context == null || userId < 0) {
            return;
        }

        final Set<String> apps = getApps(context);
        switch (action) {
            case ADD:
                apps.add(packageName);
                break;
            case REMOVE:
                apps.remove(packageName);
                break;
            case SET:
                // Don't change
                break;
        }

        Settings.Secure.putStringForUser(context.getContentResolver(),
                Settings.Secure.HIDE_DEVELOPER_STATUS, String.join(",", apps), userId);
        sCachedApps = null;
    }

    public void addApp(Context mContext, String packageName, int userId) {
        if (mContext == null || packageName == null || userId < 0) {
            return;
        }

        putAppsForUser(mContext, packageName, userId, Action.ADD);
    }

    public void removeApp(Context mContext, String packageName, int userId) {
        if (mContext == null || packageName == null || userId < 0) {
            return;
        }

        putAppsForUser(mContext, packageName, userId, Action.REMOVE);
    }

    public void setApps(Context mContext, int userId) {
        if (mContext == null || userId < 0) {
            return;
        }

        putAppsForUser(mContext, null, userId, Action.SET);
    }
}
