package com.huanchengfly.icebridge;

import android.os.Bundle;
import android.content.Context;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import com.huanchengfly.icebridge.automation.LinkRuleEntry;
import com.huanchengfly.icebridge.automation.ScopedRuleResolver;
import com.huanchengfly.icebridge.automation.SystemRuntimeConfig;
import com.huanchengfly.icebridge.ui.theme.ThemePolicy;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;

public final class SystemServerConfigContract {
    public static final String AUTHORITY = "io.github.yudigaga.icebridge.providers.ConfigProvider";
    public static final String METHOD_GET_SNAPSHOT = "get_system_server_snapshot";
    public static final String METHOD_REPORT_RUNTIME_STATUS = "report_autonomous_runtime_status";
    public static final String KEY_SCHEMA_VERSION = "schema_version";
    public static final String KEY_XHS_RECOVERY = "xhs_resolve_recovery";
    public static final String KEY_AUTO_OPEN_LINKS = "auto_open_links";
    public static final String KEY_NATIVE_LINK_CAPTURE = "native_link_capture";
    public static final String KEY_CLIPBOARD_AUTO_OPEN = "clipboard_auto_open";
    public static final String KEY_PAYMENT_SDK_BRIDGE = "payment_sdk_bridge";
    public static final String KEY_SNAPSHOT_STATUS = "snapshot_status";
    public static final String KEY_SNAPSHOT_ERROR = "snapshot_error";
    public static final String KEY_FROZEN_PACKAGES = "frozen_packages";
    public static final String KEY_ONLINE_HOST_RULES = "online_host_rules";
    public static final String KEY_USER_ID = "snapshot_user_id";
    public static final String KEY_AUTONOMOUS_RUNTIME = "autonomous_runtime_enabled";
    public static final String KEY_ONLY_FROZEN = "only_frozen_link_targets";
    public static final String KEY_BROWSER_FALLBACK = "link_browser_fallback";
    public static final String KEY_ONLINE_UPDATES = "online_link_rule_updates";
    public static final String KEY_ONLINE_REVISION = "online_rules_highest_revision";
    public static final String KEY_ONLINE_SHA256 = "online_rules_highest_sha256";
    public static final String KEY_TRANSIT_SEED_COLOR = "transit_seed_color";
    public static final String KEY_TRANSIT_APPEARANCE = "transit_appearance";
    public static final String KEY_CONFIG_FINGERPRINT = "runtime_config_fingerprint";
    public static final String KEY_RUNTIME_READY = "runtime_ready";
    public static final String KEY_RUNTIME_PERSISTED_ACK = "runtime_persisted_ack";
    public static final String KEY_RUNTIME_MESSAGE = "runtime_message";
    public static final String KEY_RUNTIME_REVISION = "runtime_revision";
    public static final String KEY_RUNTIME_STATUS_UPDATED_AT = "runtime_status_updated_at";
    public static final String KEY_RUNTIME_BOOT_COUNT = "runtime_boot_count";
    public static final String RUNTIME_STATUS_PREFS = "autonomous_runtime_status";
    public static final String KEY_RUNTIME_EXPECTED_FINGERPRINT = "expected_config_fingerprint";
    public static final String STATUS_OK = "ok";
    public static final String STATUS_UNAVAILABLE = "unavailable";
    public static final int SCHEMA_VERSION = 8;

    private SystemServerConfigContract() {
    }

    public static int currentUserId() {
        // UserHandle's public hashCode is the user handle value, also used by the IceBox SDK.
        int userId = Process.myUserHandle().hashCode();
        return userId >= 0 && userId <= 999 ? userId : -1;
    }

    public static void notifyChanged(Context context) {
        if (context == null) return;
        try { context.getContentResolver().notifyChange(Uri.parse("content://" + AUTHORITY + "/config"), null); }
        catch (RuntimeException error) { Log.w("IceBridgeConfig", "snapshot change notification unavailable", error); }
    }

    public static Snapshot parse(Bundle snapshot) {
        if (snapshot == null) {
            return parseValues(null, null, null, false, false, false, false, true, null, null, -1);
        }
        return parseValues(
                snapshot.getInt(KEY_SCHEMA_VERSION, 0),
                snapshot.getString(KEY_SNAPSHOT_STATUS),
                snapshot.getString(KEY_SNAPSHOT_ERROR),
                snapshot.getBoolean(KEY_XHS_RECOVERY, false),
                snapshot.getBoolean(KEY_AUTO_OPEN_LINKS, false),
                snapshot.getBoolean(KEY_NATIVE_LINK_CAPTURE, false),
                snapshot.getBoolean(KEY_CLIPBOARD_AUTO_OPEN, false),
                snapshot.getBoolean(KEY_PAYMENT_SDK_BRIDGE, true),
                snapshot.getStringArray(KEY_FROZEN_PACKAGES),
                snapshot.getStringArray(KEY_ONLINE_HOST_RULES),
                snapshot.getInt(KEY_USER_ID, -1),
                snapshot.getBoolean(KEY_AUTONOMOUS_RUNTIME, false),
                snapshot.getBoolean(KEY_ONLY_FROZEN, true),
                snapshot.getBoolean(KEY_BROWSER_FALLBACK, true),
                snapshot.getBoolean(KEY_ONLINE_UPDATES, true),
                snapshot.getLong(KEY_ONLINE_REVISION, 0L),
                snapshot.getString(KEY_ONLINE_SHA256, ""),
                snapshot.getString(KEY_CONFIG_FINGERPRINT, ""),
                snapshot.getInt(KEY_TRANSIT_SEED_COLOR, ThemePolicy.DEFAULT_SEED_COLOR),
                snapshot.getString(KEY_TRANSIT_APPEARANCE, ThemePolicy.APPEARANCE_SYSTEM));
    }

    // Kept independent of Android Bundle so the contract rules can be covered by local JVM tests.
    static Snapshot parseValues(Integer schemaVersion, String status, String error,
                                boolean recoveryEnabled, boolean autoOpenLinks,
                                boolean nativeLinkCapture, boolean clipboardAutoOpen,
                                boolean paymentSdkBridge, String[] frozenPackages,
                                String[] onlineHostRules, int userId) {
        return parseValues(schemaVersion, status, error, recoveryEnabled, autoOpenLinks, nativeLinkCapture,
                clipboardAutoOpen, paymentSdkBridge, frozenPackages, onlineHostRules, userId,
                false, true, true, true, 0L, "", "");
    }

    static Snapshot parseValues(Integer schemaVersion, String status, String error,
                                boolean recoveryEnabled, boolean autoOpenLinks,
                                boolean nativeLinkCapture, boolean clipboardAutoOpen,
                                boolean paymentSdkBridge, String[] frozenPackages,
                                String[] onlineHostRules, int userId, boolean autonomousEnabled,
                                boolean onlyFrozen, boolean browserFallback, boolean onlineUpdatesEnabled,
                                long onlineRevision, String onlineSha256, String configFingerprint) {
        return parseValues(schemaVersion, status, error, recoveryEnabled, autoOpenLinks, nativeLinkCapture,
                clipboardAutoOpen, paymentSdkBridge, frozenPackages, onlineHostRules, userId, autonomousEnabled,
                onlyFrozen, browserFallback, onlineUpdatesEnabled, onlineRevision, onlineSha256, configFingerprint,
                ThemePolicy.DEFAULT_SEED_COLOR, ThemePolicy.APPEARANCE_SYSTEM);
    }

    static Snapshot parseValues(Integer schemaVersion, String status, String error,
                                boolean recoveryEnabled, boolean autoOpenLinks,
                                boolean nativeLinkCapture, boolean clipboardAutoOpen,
                                boolean paymentSdkBridge, String[] frozenPackages,
                                String[] onlineHostRules, int userId, boolean autonomousEnabled,
                                boolean onlyFrozen, boolean browserFallback, boolean onlineUpdatesEnabled,
                                long onlineRevision, String onlineSha256, String configFingerprint,
                                int transitSeedColor, String transitAppearance) {
        if (schemaVersion == null) {
            return Snapshot.unavailable("null-snapshot");
        }
        if (schemaVersion != SCHEMA_VERSION) {
            return Snapshot.unavailable("schema-invalid");
        }
        if (!STATUS_OK.equals(status)) {
            return Snapshot.unavailable(error == null || error.isEmpty()
                    ? (status == null ? "status-missing" : status)
                    : error);
        }
        if (userId < 0 || userId > 999) return Snapshot.unavailable("snapshot-user-invalid");
        if ((transitSeedColor >>> 24) != 0xff || !SystemRuntimeConfig.isTransitAppearance(transitAppearance)) {
            return Snapshot.unavailable("transit-theme-invalid");
        }
        if (onlineRevision < 0 || onlineSha256 == null
                || (onlineRevision == 0 && !onlineSha256.isEmpty())
                || (onlineRevision > 0 && !onlineSha256.matches("[0-9a-f]{64}"))) {
            return Snapshot.unavailable("online-metadata-invalid");
        }
        if (configFingerprint == null || (!configFingerprint.isEmpty()
                && !configFingerprint.matches("[0-9a-f]{64}"))
                || (autonomousEnabled && configFingerprint.isEmpty())) {
            return Snapshot.unavailable("runtime-fingerprint-invalid");
        }
        try {
            return Snapshot.available(recoveryEnabled, autoOpenLinks, nativeLinkCapture, clipboardAutoOpen,
                    paymentSdkBridge, frozenPackages, onlineHostRules, userId, autonomousEnabled,
                    onlyFrozen, browserFallback, onlineUpdatesEnabled, onlineRevision, onlineSha256, configFingerprint,
                    transitSeedColor, transitAppearance);
        } catch (IllegalArgumentException invalidRules) {
            return Snapshot.unavailable("scoped-rules-invalid");
        }
    }
    public static final class Snapshot {
        public final boolean available;
        public final boolean recoveryEnabled;
        public final boolean autoOpenLinks;
        public final boolean nativeLinkCapture;
        public final boolean clipboardAutoOpen;
        public final boolean paymentSdkBridge;
        public final String status;
        public final Set<String> frozenPackages;
        public final String[] onlineHostRules;
        public final List<LinkRuleEntry> scopedRules;
        public final int userId;
        public final boolean autonomousEnabled;
        public final boolean onlyFrozen;
        public final boolean browserFallback;
        public final boolean onlineUpdatesEnabled;
        public final long onlineRevision;
        public final String onlineSha256;
        public final String configFingerprint;
        public final int transitSeedColor;
        public final String transitAppearance;

        private Snapshot(boolean available, boolean recoveryEnabled, boolean autoOpenLinks,
                         boolean nativeLinkCapture, boolean clipboardAutoOpen, boolean paymentSdkBridge, String status,
                         Set<String> frozenPackages, String[] onlineHostRules, int userId,
                         boolean autonomousEnabled, boolean onlyFrozen, boolean browserFallback,
                         boolean onlineUpdatesEnabled, long onlineRevision, String onlineSha256,
                         String configFingerprint) {
            this(available, recoveryEnabled, autoOpenLinks, nativeLinkCapture, clipboardAutoOpen, paymentSdkBridge,
                    status, frozenPackages, onlineHostRules, userId, autonomousEnabled, onlyFrozen, browserFallback,
                    onlineUpdatesEnabled, onlineRevision, onlineSha256, configFingerprint,
                    ThemePolicy.DEFAULT_SEED_COLOR, ThemePolicy.APPEARANCE_SYSTEM);
        }

        private Snapshot(boolean available, boolean recoveryEnabled, boolean autoOpenLinks,
                         boolean nativeLinkCapture, boolean clipboardAutoOpen, boolean paymentSdkBridge, String status,
                         Set<String> frozenPackages, String[] onlineHostRules, int userId,
                         boolean autonomousEnabled, boolean onlyFrozen, boolean browserFallback,
                         boolean onlineUpdatesEnabled, long onlineRevision, String onlineSha256,
                         String configFingerprint, int transitSeedColor, String transitAppearance) {
            this.available = available;
            this.recoveryEnabled = recoveryEnabled;
            this.autoOpenLinks = autoOpenLinks;
            this.nativeLinkCapture = nativeLinkCapture;
            this.clipboardAutoOpen = clipboardAutoOpen;
            this.paymentSdkBridge = paymentSdkBridge;
            this.status = status;
            this.frozenPackages = frozenPackages;
            this.onlineHostRules = onlineHostRules == null ? new String[0] : onlineHostRules.clone();
            this.scopedRules = ScopedRuleResolver.decode(this.onlineHostRules);
            this.userId = userId;
            this.autonomousEnabled = autonomousEnabled;
            this.onlyFrozen = onlyFrozen;
            this.browserFallback = browserFallback;
            this.onlineUpdatesEnabled = onlineUpdatesEnabled;
            this.onlineRevision = onlineRevision;
            this.onlineSha256 = onlineSha256;
            this.configFingerprint = configFingerprint;
            this.transitSeedColor = transitSeedColor;
            this.transitAppearance = transitAppearance;
        }

        public SystemRuntimeConfig.State toRuntimeState(long savedAtEpochMillis) throws java.io.IOException {
            if (!available) throw new java.io.IOException("snapshot-unavailable");
            return new SystemRuntimeConfig.State(SCHEMA_VERSION, userId, savedAtEpochMillis,
                    autonomousEnabled, recoveryEnabled, autoOpenLinks, nativeLinkCapture, clipboardAutoOpen,
                    paymentSdkBridge, onlyFrozen, browserFallback, onlineUpdatesEnabled, onlineHostRules,
                    new SystemRuntimeConfig.OnlineState("", "", "", "", onlineRevision, onlineSha256, onlineRevision > 0),
                    transitSeedColor, transitAppearance);
        }

        private static Snapshot available(boolean recoveryEnabled, boolean autoOpenLinks,
                                          boolean nativeLinkCapture, boolean clipboardAutoOpen, boolean paymentSdkBridge,
                                          String[] frozenPackages, String[] onlineHostRules, int userId,
                                          boolean autonomousEnabled, boolean onlyFrozen, boolean browserFallback,
                                          boolean onlineUpdatesEnabled, long onlineRevision, String onlineSha256,
                                          String configFingerprint, int transitSeedColor, String transitAppearance) {
            Set<String> values = new LinkedHashSet<>();
            if (frozenPackages != null) {
                values.addAll(Arrays.asList(frozenPackages));
                values.remove(null);
                values.remove("");
            }
            return new Snapshot(true, recoveryEnabled, autoOpenLinks, nativeLinkCapture, clipboardAutoOpen,
                    paymentSdkBridge, STATUS_OK, Collections.unmodifiableSet(values), onlineHostRules, userId,
                    autonomousEnabled, onlyFrozen, browserFallback, onlineUpdatesEnabled, onlineRevision, onlineSha256, configFingerprint,
                    transitSeedColor, transitAppearance);
        }

        private static Snapshot unavailable(String status) {
            return new Snapshot(false, false, false, false, false, false, status, Collections.emptySet(), new String[0], -1,
                    false, true, true, true, 0, "", "");
        }
    }
}
