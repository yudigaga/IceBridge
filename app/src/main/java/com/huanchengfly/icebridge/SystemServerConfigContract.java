package com.huanchengfly.icebridge;

import android.os.Bundle;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class SystemServerConfigContract {
    public static final String AUTHORITY = "io.github.yudigaga.icebridge.providers.ConfigProvider";
    public static final String METHOD_GET_SNAPSHOT = "get_system_server_snapshot";
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
    public static final String STATUS_OK = "ok";
    public static final String STATUS_UNAVAILABLE = "unavailable";
    public static final int SCHEMA_VERSION = 6;

    private SystemServerConfigContract() {
    }

    public static Snapshot parse(Bundle snapshot) {
        if (snapshot == null) {
            return parseValues(null, null, null, false, false, false, false, true, null, null);
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
                snapshot.getStringArray(KEY_ONLINE_HOST_RULES));
    }

    // Kept independent of Android Bundle so the contract rules can be covered by local JVM tests.
    static Snapshot parseValues(Integer schemaVersion, String status, String error,
                                boolean recoveryEnabled, boolean autoOpenLinks,
                                boolean nativeLinkCapture, boolean clipboardAutoOpen,
                                boolean paymentSdkBridge, String[] frozenPackages,
                                String[] onlineHostRules) {
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
        return Snapshot.available(recoveryEnabled, autoOpenLinks, nativeLinkCapture, clipboardAutoOpen,
                paymentSdkBridge, frozenPackages, onlineHostRules);
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

        private Snapshot(boolean available, boolean recoveryEnabled, boolean autoOpenLinks,
                         boolean nativeLinkCapture, boolean clipboardAutoOpen, boolean paymentSdkBridge, String status,
                         Set<String> frozenPackages, String[] onlineHostRules) {
            this.available = available;
            this.recoveryEnabled = recoveryEnabled;
            this.autoOpenLinks = autoOpenLinks;
            this.nativeLinkCapture = nativeLinkCapture;
            this.clipboardAutoOpen = clipboardAutoOpen;
            this.paymentSdkBridge = paymentSdkBridge;
            this.status = status;
            this.frozenPackages = frozenPackages;
            this.onlineHostRules = onlineHostRules == null ? new String[0] : onlineHostRules;
        }

        private static Snapshot available(boolean recoveryEnabled, boolean autoOpenLinks,
                                          boolean nativeLinkCapture, boolean clipboardAutoOpen, boolean paymentSdkBridge,
                                          String[] frozenPackages, String[] onlineHostRules) {
            Set<String> values = new LinkedHashSet<>();
            if (frozenPackages != null) {
                values.addAll(Arrays.asList(frozenPackages));
                values.remove(null);
                values.remove("");
            }
            return new Snapshot(true, recoveryEnabled, autoOpenLinks, nativeLinkCapture, clipboardAutoOpen,
                    paymentSdkBridge, STATUS_OK, Collections.unmodifiableSet(values), onlineHostRules);
        }

        private static Snapshot unavailable(String status) {
            return new Snapshot(false, false, false, false, false, false, status, Collections.emptySet(), new String[0]);
        }
    }
}
