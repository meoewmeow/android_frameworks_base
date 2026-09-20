/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.server.spoof;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.server.NtServiceInjector;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class AxSpoofManager implements IAxSpoofManager {
    private static final String TAG = "AxSpoofManager";

    private static final String[] WATCHED_KEYS = {
            Settings.Secure.SPOOF_PIF_CONFIG,
            Settings.Secure.SPOOF_PIF_PHOTOS,
            Settings.Secure.SPOOF_GAMEPROPS_CONFIG,
            Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
            Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX,
            Settings.Secure.SPOOF_TRICKYSTORE_PATCH,
    };

    // Master switches live in Settings.System; the services treat a missing
    // value as enabled so existing installs keep spoofing after this update.
    private static final String[] WATCHED_SYSTEM_KEYS = {
            Settings.System.SPOOF_PIF_ENABLED,
            Settings.System.SPOOF_TRICKYSTORE_ENABLED,
    };

    private final Map<String, String> mCache = new ConcurrentHashMap<>();
    private final Map<String, String> mSystemCache = new ConcurrentHashMap<>();
    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private Context mContext;
    private ContentResolver mResolver;
    private ContentObserver mObserver;
    private volatile boolean mReady = false;

    public AxSpoofManager() {
        mHandlerThread = new HandlerThread("AxSpoofManager");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    @Override
    public void systemReady() {
        mContext = NtServiceInjector.getCtx();
        if (mContext == null) {
            Log.w(TAG, "Context unavailable, deferring init");
            return;
        }
        mResolver = mContext.getContentResolver();

        for (String key : WATCHED_KEYS) {
            refreshKey(key);
        }
        for (String key : WATCHED_SYSTEM_KEYS) {
            refreshSystemKey(key);
        }

        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (uri == null) return;
                final String last = uri.getLastPathSegment();
                if (last == null) return;
                if (isSystemKey(last)) {
                    refreshSystemKey(last);
                } else {
                    refreshKey(last);
                }
                Log.i(TAG, "Spoof config refreshed: " + last);
            }
        };
        for (String key : WATCHED_KEYS) {
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mObserver, UserHandle.USER_ALL);
        }
        for (String key : WATCHED_SYSTEM_KEYS) {
            mResolver.registerContentObserver(
                    Settings.System.getUriFor(key), false, mObserver, UserHandle.USER_ALL);
        }

        mReady = true;
        Log.i(TAG, "AxSpoofManager ready");
    }

    private void refreshKey(String key) {
        if (mResolver == null) return;
        final String value = Settings.Secure.getStringForUser(
                mResolver, key, UserHandle.USER_CURRENT);
        if (value == null) {
            mCache.remove(key);
        } else {
            mCache.put(key, value);
        }
    }

    private void refreshSystemKey(String key) {
        if (mResolver == null) return;
        final String value = Settings.System.getStringForUser(
                mResolver, key, UserHandle.USER_CURRENT);
        if (value == null) {
            mSystemCache.remove(key);
        } else {
            mSystemCache.put(key, value);
        }
    }

    private static boolean isSystemKey(String key) {
        for (String systemKey : WATCHED_SYSTEM_KEYS) {
            if (systemKey.equals(key)) return true;
        }
        return false;
    }

    private String getCached(String key) {
        return mCache.get(key);
    }

    private String getCachedSystem(String key, String defaultValue) {
        final String value = mSystemCache.get(key);
        return value == null ? defaultValue : value;
    }

    @Override
    public String getPifConfig() {
        return getCached(Settings.Secure.SPOOF_PIF_CONFIG);
    }

    @Override
    public String getPifSpoofPhotos() {
        return getCached(Settings.Secure.SPOOF_PIF_PHOTOS);
    }

    @Override
    public String getPifEnabled() {
        return getCachedSystem(Settings.System.SPOOF_PIF_ENABLED, "1");
    }

    @Override
    public String getTrickyStoreEnabled() {
        return getCachedSystem(Settings.System.SPOOF_TRICKYSTORE_ENABLED, "1");
    }

    @Override
    public String getGamePropsConfig() {
        return getCached(Settings.Secure.SPOOF_GAMEPROPS_CONFIG);
    }

    @Override
    public String getTrickyStoreTarget() {
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
    }

    @Override
    public String getTrickyStoreKeyBox() {
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_KEYBOX);
    }

    @Override
    public String getTrickyStorePatch() {
        return getCached(Settings.Secure.SPOOF_TRICKYSTORE_PATCH);
    }
}
