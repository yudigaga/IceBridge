package com.huanchengfly.icebridge.providers;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.huanchengfly.icebridge.SystemServerConfigContract;
import com.huanchengfly.icebridge.automation.LinkAutomationConfig;
import com.huanchengfly.icebridge.automation.RuleConfigRepository;
import com.huanchengfly.icebridge.databases.AppConfig;

import org.litepal.LitePal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.catchingnow.icebox.sdk_client.IceBox;

public class ConfigProvider extends ContentProvider {
    public static final String TAG = "ConfigProvider";

    private static final String AUTHORITY = "com.huanchengfly.icebridge.providers.ConfigProvider";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");
    private static final int MATCH_CODE = 200;
    private static UriMatcher uriMatcher;
    // HyperOS may deny full package enumeration even with QUERY_ALL_PACKAGES. These are the
    // high-confidence link targets handled by the module and can be queried one package at a time.
    private static final String[] LINK_TARGET_CANDIDATES = {
            "com.xingin.xhs", "com.sina.weibo", "com.zhihu.android", "com.twitter.android",
            "com.google.android.youtube", "com.instagram.android", "org.telegram.messenger",
            "com.eg.android.AlipayGphone", "tv.danmaku.bili"
    };

    static {
        uriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        uriMatcher.addURI(AUTHORITY, "config", MATCH_CODE);
    }

    private void notifyChange() {
        getContext().getContentResolver().notifyChange(URI, null);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        enforceSystemServerCaller();
        int match = uriMatcher.match(uri);
        if (match == MATCH_CODE) {
            Log.i(TAG, "query");
            try {
                return LitePal.findBySQL("select * from AppConfig");
            } catch (Exception error) {
                Log.w(TAG, "query failed: " + error.getClass().getSimpleName(), error);
            }
        }
        return null;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        enforceSystemServerCaller();
        if (!SystemServerConfigContract.METHOD_GET_SNAPSHOT.equals(method)) {
            return null;
        }
        Bundle snapshot = new Bundle();
        snapshot.putInt(SystemServerConfigContract.KEY_SCHEMA_VERSION, SystemServerConfigContract.SCHEMA_VERSION);
        try {
            // Read one database snapshot instead of issuing one query per switch. This reduces
            // startup Binder latency and prevents a config refresh from observing mixed values
            // while the UI is saving several related settings.
            Map<String, String> configValues = new HashMap<>();
            for (AppConfig config : LitePal.findAll(AppConfig.class)) {
                if (config != null && config.getKey() != null) {
                    configValues.put(config.getKey(), config.getValue());
                }
            }
            snapshot.putBoolean(
                    SystemServerConfigContract.KEY_XHS_RECOVERY,
                    configEnabled(configValues, SystemServerConfigContract.KEY_XHS_RECOVERY, false)
            );
            snapshot.putBoolean(
                    SystemServerConfigContract.KEY_AUTO_OPEN_LINKS,
                    configEnabled(configValues, SystemServerConfigContract.KEY_AUTO_OPEN_LINKS, false)
            );
            snapshot.putBoolean(
                    SystemServerConfigContract.KEY_NATIVE_LINK_CAPTURE,
                    configEnabled(configValues, SystemServerConfigContract.KEY_NATIVE_LINK_CAPTURE, false)
            );
            // Default-on: this bridge is restricted to known payment SDK Activities and only
            // restores the wallet before replaying the SDK's original request.
            snapshot.putBoolean(
                    SystemServerConfigContract.KEY_PAYMENT_SDK_BRIDGE,
                    configEnabled(configValues, SystemServerConfigContract.KEY_PAYMENT_SDK_BRIDGE, true)
            );
            snapshot.putBoolean(
                    SystemServerConfigContract.KEY_CLIPBOARD_AUTO_OPEN,
                    getContext().getApplicationContext()
                            .getSharedPreferences(LinkAutomationConfig.PREFS, Context.MODE_PRIVATE)
                            .getBoolean(LinkAutomationConfig.KEY_CLIPBOARD, false)
            );
            snapshot.putStringArray(
                    SystemServerConfigContract.KEY_FROZEN_PACKAGES,
                    queryFrozenPackages().toArray(new String[0])
            );
            snapshot.putStringArray(SystemServerConfigContract.KEY_ONLINE_HOST_RULES, RuleConfigRepository.systemServerEntries(getContext()));
            snapshot.putString(SystemServerConfigContract.KEY_SNAPSHOT_STATUS, SystemServerConfigContract.STATUS_OK);
        } catch (Throwable error) {
            String reason = error.getClass().getSimpleName();
            Log.w(TAG, "system-server snapshot unavailable: " + reason, error);
            snapshot.putString(SystemServerConfigContract.KEY_SNAPSHOT_STATUS, SystemServerConfigContract.STATUS_UNAVAILABLE);
            snapshot.putString(SystemServerConfigContract.KEY_SNAPSHOT_ERROR, reason);
        }
        return snapshot;
    }

    /**
     * Runs in the IceBridge app process, never in ActivityTaskSupervisor.  The returned list is
     * a snapshot of packages IceBox currently hides or disables for the owner user.
     */
    private List<String> queryFrozenPackages() {
        Set<String> frozen = new HashSet<>();
        Context context = getContext();
        if (context == null) return new ArrayList<>(frozen);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS
                : PackageManager.GET_UNINSTALLED_PACKAGES | PackageManager.GET_DISABLED_COMPONENTS;
        boolean enumerationSucceeded = false;
        try {
            for (ApplicationInfo info : context.getPackageManager().getInstalledApplications(flags)) {
                addIfFrozen(frozen, info);
            }
            enumerationSucceeded = true;
        } catch (Throwable error) {
            Log.w(TAG, "frozen snapshot enumeration denied: " + error.getClass().getSimpleName());
        }
        // A denied enumeration must not turn the whole snapshot into an empty set on HyperOS.
        // Single-package queries avoid the MIUI getInstalledApplications restriction.
        if (!enumerationSucceeded) {
            for (String packageName : LINK_TARGET_CANDIDATES) {
                try {
                    int singleFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                            ? PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS
                            : PackageManager.GET_UNINSTALLED_PACKAGES | PackageManager.GET_DISABLED_COMPONENTS;
                    addIfFrozen(frozen, context.getPackageManager().getApplicationInfo(packageName, singleFlags));
                } catch (Throwable ignored) {
                    // App not installed or still inaccessible; leave it out rather than blocking system_server.
                }
            }
            Log.i(TAG, "frozen snapshot source=candidates count=" + frozen.size()
                    + " candidates=" + Arrays.toString(LINK_TARGET_CANDIDATES));
        } else {
            Log.i(TAG, "frozen snapshot source=enumeration count=" + frozen.size());
        }
        return new ArrayList<>(frozen);
    }

    private static boolean configEnabled(Map<String, String> values, String key, boolean defaultValue) {
        String value = values == null ? null : values.get(key);
        return value == null ? defaultValue : "1".equals(value);
    }

    private void addIfFrozen(Set<String> frozen, ApplicationInfo info) {
        if (info == null || info.packageName == null) return;
        // The query deliberately includes MATCH_UNINSTALLED_PACKAGES for compatibility with
        // frozen/disabled apps, but that flag also exposes stale package records. Do not publish
        // an uninstalled package as frozen: it can then win a shared-host candidate selection.
        if ((info.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
            Log.i(TAG, "frozen snapshot skipped uninstalled package=" + info.packageName);
            return;
        }
        if (IceBox.getAppEnabledSetting(info) != 0) {
            frozen.add(info.packageName);
        }
    }

    private boolean isSystemServerCaller() {
        return Binder.getCallingUid() == Process.SYSTEM_UID;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        enforceSystemServerCaller();
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        enforceSystemServerCaller();
        if (uriMatcher.match(uri) != MATCH_CODE) {
            throw new IllegalArgumentException("Unsupported config URI: " + uri);
        }
        Map<String, String> sanitized = sanitizeValues(values);
        boolean transactionStarted = false;
        SQLiteDatabase database = null;
        try {
            database = LitePal.getDatabase();
            database.beginTransaction();
            transactionStarted = true;
            for (Map.Entry<String, String> entry : sanitized.entrySet()) {
                new AppConfig(entry.getKey(), entry.getValue())
                        .saveOrUpdate("key = ?", entry.getKey());
            }
            database.setTransactionSuccessful();
        } catch (Throwable error) {
            Log.w(TAG, "insert failed: " + error.getClass().getSimpleName(), error);
            throw new IllegalStateException("Unable to persist config update", error);
        } finally {
            if (transactionStarted && database != null) {
                database.endTransaction();
            }
        }
        Log.i(TAG, "insert count=" + sanitized.size());
        notifyChange();
        return uri;
    }

    /** Validates the small, cross-process configuration surface before it reaches LitePal. */
    static Map<String, String> sanitizeValues(@Nullable ContentValues values) {
        if (values == null || values.size() == 0) {
            throw new IllegalArgumentException("Config values must not be empty");
        }
        return ConfigProviderValueValidator.sanitizeEntries(values.valueSet());
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        enforceSystemServerCaller();
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        enforceSystemServerCaller();
        return 0;
    }

    private void enforceSystemServerCaller() {
        if (!isSystemServerCaller()) {
            throw new SecurityException("ConfigProvider is restricted to system_server");
        }
    }
}
