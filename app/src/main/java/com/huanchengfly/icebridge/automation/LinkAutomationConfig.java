package com.huanchengfly.icebridge.automation;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Preferences used only by the app-side URL automation; never queried in system_server hooks. */
public final class LinkAutomationConfig {
    public static final String PREFS = "preferences";
    public static final String KEY_AUTO_OPEN = "auto_open_links";
    public static final String KEY_NATIVE_LINK_CAPTURE = "native_link_capture";
    public static final String KEY_PAYMENT_SDK_BRIDGE = "payment_sdk_bridge";
    public static final String KEY_ONLY_FROZEN = "only_frozen_link_targets";
    public static final String KEY_BROWSER_FALLBACK = "link_browser_fallback";
    public static final String KEY_CLIPBOARD = "clipboard_auto_open";
    public static final String KEY_CUSTOM_MAPPINGS = "custom_url_mappings";
    public static final String KEY_HIDE_BRIDGE_FROM_RECENTS = "hide_bridge_from_recents";

    private LinkAutomationConfig() {
    }

    public static boolean isNativeLinkCaptureEnabled(Context context) {
        return prefs(context).getBoolean(KEY_NATIVE_LINK_CAPTURE, false);
    }

    public static boolean isPaymentSdkBridgeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PAYMENT_SDK_BRIDGE, true);
    }

    public static boolean isAutoOpenEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_OPEN, false);
    }

    public static boolean isOnlyFrozenEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ONLY_FROZEN, true);
    }

    public static boolean isBrowserFallbackEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BROWSER_FALLBACK, true);
    }

    public static boolean isBridgeHiddenFromRecents(Context context) {
        return prefs(context).getBoolean(KEY_HIDE_BRIDGE_FROM_RECENTS, true);
    }

    public static boolean isClipboardEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CLIPBOARD, false);
    }

    public static Map<String, String> customMappings(Context context) {
        Map<String, String> result = new HashMap<>();
        String raw = prefs(context).getString(KEY_CUSTOM_MAPPINGS, "");
        if (TextUtils.isEmpty(raw)) return result;
        for (String item : raw.split("[,\\n;]")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length != 2) continue;
            String host = pair[0].trim().toLowerCase(Locale.ROOT);
            String pkg = pair[1].trim();
            if (isHost(host) && isPackage(pkg)) result.put(host, pkg);
        }
        return result;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean isHost(String value) {
        return value.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?");
    }

    public static boolean isPackage(String value) {
        return value != null && value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }
}

