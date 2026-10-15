package com.huanchengfly.icebridge.providers;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
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
import com.huanchengfly.icebridge.automation.LinkRuleEntry;
import com.huanchengfly.icebridge.automation.OnlineLinkRuleRepository;
import com.huanchengfly.icebridge.automation.ScopedRuleResolver;
import com.huanchengfly.icebridge.automation.SystemRuntimeConfig;
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

    private static final String AUTHORITY = "io.github.yudigaga.icebridge.providers.ConfigProvider";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");
    private static final int MATCH_CODE = 200;
    private static UriMatcher uriMatcher;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChanged = (preferences, key) -> {
        if (key == null || !key.startsWith("rule_config_effective")) notifyChange();
    };
    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { notifyChange(); }
    };
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
        invalidateRuntimeConfirmation();
        SystemServerConfigContract.notifyChanged(getContext());
    }

    private SharedPreferences runtimeStatus() {
        return getContext().getSharedPreferences(SystemServerConfigContract.RUNTIME_STATUS_PREFS, Context.MODE_PRIVATE);
    }

    private void invalidateRuntimeConfirmation() {
        if (getContext() == null) return;
        SharedPreferences status = runtimeStatus();
        if (status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false)
                || !status.getString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, "").isEmpty()) {
            status.edit().putBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false)
                    .putBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, false)
                    .putString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, "")
                    .putString(SystemServerConfigContract.KEY_RUNTIME_MESSAGE, "配置已变化，等待系统重新确认").apply();
        }
    }

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context != null) {
            context.getSharedPreferences(LinkAutomationConfig.PREFS, Context.MODE_PRIVATE)
                    .registerOnSharedPreferenceChangeListener(preferencesChanged);
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addDataScheme("package");
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(packagesChanged, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(packagesChanged, filter);
        }
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
        if (SystemServerConfigContract.METHOD_REPORT_RUNTIME_STATUS.equals(method)) {
            return reportRuntimeStatus(extras);
        }
        if (!SystemServerConfigContract.METHOD_GET_SNAPSHOT.equals(method)) {
            return null;
        }
        return buildSnapshot();
    }

    private Bundle buildSnapshot() {
        Bundle snapshot = new Bundle();
        snapshot.putInt(SystemServerConfigContract.KEY_SCHEMA_VERSION, SystemServerConfigContract.SCHEMA_VERSION);
        snapshot.putInt(SystemServerConfigContract.KEY_USER_ID, SystemServerConfigContract.currentUserId());
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
            SharedPreferences preferences = getContext().getApplicationContext()
                    .getSharedPreferences(LinkAutomationConfig.PREFS, Context.MODE_PRIVATE);
            boolean autonomous = preferences.getBoolean(SystemServerConfigContract.KEY_AUTONOMOUS_RUNTIME, false);
            snapshot.putBoolean(SystemServerConfigContract.KEY_AUTONOMOUS_RUNTIME, autonomous);
            snapshot.putBoolean(SystemServerConfigContract.KEY_ONLY_FROZEN,
                    preferences.getBoolean(LinkAutomationConfig.KEY_ONLY_FROZEN, true));
            snapshot.putBoolean(SystemServerConfigContract.KEY_BROWSER_FALLBACK,
                    preferences.getBoolean(LinkAutomationConfig.KEY_BROWSER_FALLBACK, true));
            snapshot.putBoolean(SystemServerConfigContract.KEY_ONLINE_UPDATES, OnlineLinkRuleRepository.isEnabled(getContext()));
            OnlineLinkRuleRepository.AcceptedMetadata onlineMetadata = null;
            try { onlineMetadata = OnlineLinkRuleRepository.getAcceptedMetadata(getContext()); }
            catch (IllegalStateException invalidMetadata) {
                if (autonomous) throw invalidMetadata;
                // Broken online metadata must never prevent an explicit opt-out from reaching system_server.
                Log.w(TAG, "Online metadata unavailable while independent mode is disabled");
            }
            long onlineRevision = onlineMetadata == null ? 0 : onlineMetadata.highestRevision;
            String onlineSha256 = onlineMetadata == null ? "" : onlineMetadata.highestSha256;
            snapshot.putLong(SystemServerConfigContract.KEY_ONLINE_REVISION, onlineRevision);
            snapshot.putString(SystemServerConfigContract.KEY_ONLINE_SHA256, onlineSha256);
            snapshot.putBoolean(
                    SystemServerConfigContract.KEY_XHS_RECOVERY,
                    configEnabled(configValues, SystemServerConfigContract.KEY_XHS_RECOVERY, false)
                            && configEnabled(configValues, "enhance_mode_enabled", true)
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
                    preferences.getBoolean(LinkAutomationConfig.KEY_CLIPBOARD, false)
            );
            snapshot.putStringArray(
                    SystemServerConfigContract.KEY_FROZEN_PACKAGES,
                    autonomous ? new String[0] : queryFrozenPackages().toArray(new String[0])
            );
            // Independent operation must retain rules for hidden or later-installed targets.
            // system_server checks actual package state for each request rather than trusting this list.
            String[] rules = autonomous ? ScopedRuleResolver.encode(RuleConfigRepository.effectiveRuleEntries(getContext()))
                    : RuleConfigRepository.systemServerEntries(getContext());
            if (onlineMetadata != null) {
                OnlineLinkRuleRepository.AcceptedMetadata latestMetadata = OnlineLinkRuleRepository.getAcceptedMetadata(getContext());
                if (latestMetadata.highestRevision != onlineRevision || !latestMetadata.highestSha256.equals(onlineSha256)) {
                    throw new IllegalStateException("Online rules changed during snapshot");
                }
            }
            snapshot.putStringArray(SystemServerConfigContract.KEY_ONLINE_HOST_RULES, rules);
            SystemRuntimeConfig.State baseline = new SystemRuntimeConfig.State(SystemServerConfigContract.SCHEMA_VERSION,
                    SystemServerConfigContract.currentUserId(), 0, autonomous,
                    snapshot.getBoolean(SystemServerConfigContract.KEY_XHS_RECOVERY),
                    snapshot.getBoolean(SystemServerConfigContract.KEY_AUTO_OPEN_LINKS),
                    snapshot.getBoolean(SystemServerConfigContract.KEY_NATIVE_LINK_CAPTURE),
                    snapshot.getBoolean(SystemServerConfigContract.KEY_CLIPBOARD_AUTO_OPEN),
                    snapshot.getBoolean(SystemServerConfigContract.KEY_PAYMENT_SDK_BRIDGE),
                    snapshot.getBoolean(SystemServerConfigContract.KEY_ONLY_FROZEN),
                    snapshot.getBoolean(SystemServerConfigContract.KEY_BROWSER_FALLBACK),
                    snapshot.getBoolean(SystemServerConfigContract.KEY_ONLINE_UPDATES), rules,
                    new SystemRuntimeConfig.OnlineState("", "", "", "", onlineRevision, onlineSha256, onlineRevision > 0));
            String fingerprint = SystemRuntimeConfig.fingerprint(baseline);
            snapshot.putString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, fingerprint);
            SharedPreferences status = runtimeStatus();
            if (!fingerprint.equals(status.getString(SystemServerConfigContract.KEY_RUNTIME_EXPECTED_FINGERPRINT, ""))) {
                status.edit().putString(SystemServerConfigContract.KEY_RUNTIME_EXPECTED_FINGERPRINT, fingerprint)
                        .putBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false)
                        .putBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, false)
                        .putString(SystemServerConfigContract.KEY_RUNTIME_MESSAGE, "等待系统保存并确认当前配置").apply();
            }
            snapshot.putString(SystemServerConfigContract.KEY_SNAPSHOT_STATUS, SystemServerConfigContract.STATUS_OK);
        } catch (Throwable error) {
            String reason = error.getClass().getSimpleName();
            Log.w(TAG, "system-server snapshot unavailable: " + reason, error);
            snapshot.putString(SystemServerConfigContract.KEY_SNAPSHOT_STATUS, SystemServerConfigContract.STATUS_UNAVAILABLE);
            snapshot.putString(SystemServerConfigContract.KEY_SNAPSHOT_ERROR, reason);
        }
        return snapshot;
    }

    /** An acknowledgement is useful only for the exact current configuration and user. */
    private Bundle reportRuntimeStatus(@Nullable Bundle extras) {
        Bundle reply = new Bundle();
        if (extras == null) throw new IllegalArgumentException("Missing runtime status");
        int userId = extras.getInt(SystemServerConfigContract.KEY_USER_ID, -1);
        int schema = extras.getInt(SystemServerConfigContract.KEY_SCHEMA_VERSION, -1);
        String fingerprint = extras.getString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, "");
        long revision = extras.getLong(SystemServerConfigContract.KEY_RUNTIME_REVISION, 0);
        String message = extras.getString(SystemServerConfigContract.KEY_RUNTIME_MESSAGE, "");
        boolean ready = extras.getBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false);
        boolean persistedAck = extras.getBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, false);
        ConfigProviderRuntimeStatus.validate(userId, SystemServerConfigContract.currentUserId(), schema,
                SystemServerConfigContract.SCHEMA_VERSION, fingerprint, revision, message);
        Bundle current = buildSnapshot();
        boolean matches = SystemServerConfigContract.STATUS_OK.equals(current.getString(SystemServerConfigContract.KEY_SNAPSHOT_STATUS))
                && fingerprint.equals(current.getString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT));
        if (!matches) {
            invalidateRuntimeConfirmation();
            reply.putString(SystemServerConfigContract.KEY_SNAPSHOT_STATUS, "stale-config");
            reply.putBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false);
            return reply;
        }
        boolean confirmed = ready && persistedAck && current.getBoolean(SystemServerConfigContract.KEY_AUTONOMOUS_RUNTIME, false);
        if (confirmed && revision < current.getLong(SystemServerConfigContract.KEY_ONLINE_REVISION, 0)) {
            throw new IllegalArgumentException("Runtime revision is older than current rules");
        }
        int bootCount = android.provider.Settings.Global.getInt(getContext().getContentResolver(), "boot_count", -1);
        SharedPreferences status = runtimeStatus();
        // Separate preferences and sibling URI keep status acknowledgements out of config observers.
        boolean changed = status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false) != confirmed
                || status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, false) != persistedAck
                || !fingerprint.equals(status.getString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, ""))
                || revision != status.getLong(SystemServerConfigContract.KEY_RUNTIME_REVISION, 0)
                || bootCount != status.getInt(SystemServerConfigContract.KEY_RUNTIME_BOOT_COUNT, -1)
                || !message.equals(status.getString(SystemServerConfigContract.KEY_RUNTIME_MESSAGE, ""));
        if (changed) {
            boolean saved = status.edit().putBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, confirmed)
                    .putBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, persistedAck)
                    .putString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, fingerprint)
                    .putLong(SystemServerConfigContract.KEY_RUNTIME_REVISION, revision)
                    .putString(SystemServerConfigContract.KEY_RUNTIME_MESSAGE, message)
                    .putInt(SystemServerConfigContract.KEY_RUNTIME_BOOT_COUNT, bootCount)
                    .putLong(SystemServerConfigContract.KEY_RUNTIME_STATUS_UPDATED_AT, System.currentTimeMillis()).commit();
            if (!saved) throw new IllegalStateException("Runtime status persistence failed");
            getContext().getContentResolver().notifyChange(Uri.parse("content://" + AUTHORITY + "/runtime-status"), null);
        }
        reply.putString(SystemServerConfigContract.KEY_SNAPSHOT_STATUS, SystemServerConfigContract.STATUS_OK);
        reply.putBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, confirmed);
        reply.putBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, persistedAck);
        return reply;
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
            Set<String> fallbackCandidates = new HashSet<>(Arrays.asList(LINK_TARGET_CANDIDATES));
            for (LinkRuleEntry entry : RuleConfigRepository.effectiveRuleEntries(context)) {
                fallbackCandidates.add(entry.targetPackage);
            }
            for (String packageName : fallbackCandidates) {
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
