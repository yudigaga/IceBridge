package com.huanchengfly.icebridge.providers;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Pure-Java boundary validation for ConfigProvider writes, kept testable without Android stubs. */
final class ConfigProviderValueValidator {
    private static final int MAX_INSERT_VALUES = 32;
    private static final int MAX_CONFIG_KEY_LENGTH = 64;
    private static final int MAX_CONFIG_VALUE_LENGTH = 32 * 1024;
    private static final Set<String> WRITABLE_CONFIG_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "xhs_resolve_recovery", "auto_open_links", "native_link_capture",
                    "clipboard_auto_open", "payment_sdk_bridge", "module_debug_logging",
                    "enhance_mode_enabled", "custom_mappings", "custom_url_mappings",
                    "bridge_config", "config", "hide_bridge_from_recents",
                    "only_frozen_link_targets", "link_browser_fallback", "autonomous_runtime_enabled"
            )));

    private ConfigProviderValueValidator() {
    }

    static Map<String, String> sanitizeEntries(@Nullable Set<Map.Entry<String, Object>> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("Config values must not be empty");
        }
        if (entries.size() > MAX_INSERT_VALUES) {
            throw new IllegalArgumentException("Too many config values");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            if (entry == null || !isAllowedKey(entry.getKey())) {
                throw new IllegalArgumentException("Unsupported config key");
            }
            String value = normalizeValue(entry.getValue());
            if (value.length() > MAX_CONFIG_VALUE_LENGTH) {
                throw new IllegalArgumentException("Config value is too large");
            }
            result.put(entry.getKey(), value);
        }
        return Collections.unmodifiableMap(result);
    }

    static boolean isAllowedKey(@Nullable String key) {
        return key != null && key.length() <= MAX_CONFIG_KEY_LENGTH
                && WRITABLE_CONFIG_KEYS.contains(key);
    }

    static String normalizeValue(@Nullable Object value) {
        if (value instanceof String) return (String) value;
        if (value instanceof Boolean) return (Boolean) value ? "1" : "0";
        throw new IllegalArgumentException("Unsupported config value type");
    }
}
