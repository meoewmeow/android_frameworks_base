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
import android.os.UserHandle;
import android.provider.Settings;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Hides ADB / developer options status from packages selected by the user.
 *
 * <p>Adapted from Infinity X (frameworks_base@16-QPR1,
 * com.android.internal.util.infinity.HideDeveloperStatusUtils). Only the package
 * name was changed to the crDroid util namespace.
 */
public class HideDeveloperStatusUtils {
    private static final Set<String> settingsToHide =
        new HashSet<>(
            Arrays.asList(
                Settings.Global.ADB_ENABLED,
                Settings.Global.ADB_WIFI_ENABLED,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED
            ));

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

        Set<String> apps = getApps(cr);
        if (apps.isEmpty()) {
            return false;
        }

        return apps.contains(packageName) && settingsToHide.contains(name);
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
