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
 * Hides selected packages from PackageManager queries made by other apps.
 *
 * <p>Adapted from Infinity X (frameworks_base@16-QPR1,
 * com.android.internal.util.infinity.HideAppListUtils).
 *
 * <p>crDroid adaptation: nothing runs before boot completes and the package
 * list is cached and refreshed through a content observer instead of reading
 * the provider on every PackageManager query.
 */
public class HideAppListUtils {
    private static final Object sCacheLock = new Object();

    /** Cached hidden-package set; null means "read it again from the provider". */
    private static volatile Set<String> sCachedApps = null;
    private static volatile boolean sObserverRegistered = false;

    enum Action {
        ADD,
        REMOVE,
        SET
    }

    private static boolean isBootCompleted() {
        return SystemProperties.getBoolean("sys.boot_completed", false);
    }

    public static boolean shouldHideAppList(Context context, String packageName) {
        if (context == null) {
            return false;
        }
        return shouldHideAppList(context.getContentResolver(), packageName);
    }

    public static boolean shouldHideAppList(ContentResolver cr, String packageName) {
        if (cr == null || packageName == null || !isBootCompleted()) {
            return false;
        }

        try {
            Set<String> apps = getAppsCached(cr);
            return !apps.isEmpty() && apps.contains(packageName);
        } catch (Throwable t) {
            // Never break a PackageManager query because of this feature.
            return false;
        }
    }

    public static Set<String> getApps(Context context) {
        if (context == null) {
            return new HashSet<>();
        }

        try {
            return new HashSet<>(getAppsCached(context.getContentResolver()));
        } catch (Throwable t) {
            return new HashSet<>();
        }
    }

    public static Set<String> getApps(ContentResolver cr) {
        if (cr == null) {
            return new HashSet<>();
        }

        try {
            return new HashSet<>(getAppsCached(cr));
        } catch (Throwable t) {
            return new HashSet<>();
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
                cached = readApps(cr);
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
                    Settings.Secure.getUriFor(Settings.Secure.HIDE_APPLIST),
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

    private static Set<String> readApps(ContentResolver cr) {
        String apps;
        try {
            apps = Settings.Secure.getString(cr, Settings.Secure.HIDE_APPLIST);
        } catch (IllegalStateException e) {
            return new HashSet<>();
        }
        if (apps != null && !apps.isEmpty() && !apps.equals(",")) {
            return new HashSet<>(Arrays.asList(apps.split(",")));
        }

        return new HashSet<>();
    }

    private static void putAppsForUser(
            Context context, String packageName, int userId, Action action) {
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

        Settings.Secure.putStringForUser(
                context.getContentResolver(),
                Settings.Secure.HIDE_APPLIST,
                String.join(",", apps),
                userId);
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
