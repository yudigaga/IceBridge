package com.huanchengfly.icebridge.providers;

import org.junit.Test;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigProviderValidationTest {
    @Test
    public void acceptsKnownKeysAndNormalizesStringAndBooleanValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("auto_open_links", true);
        values.put("native_link_capture", false);
        values.put("module_debug_logging", "3");
        values.put("enhance_mode_enabled", "4");
        values.put("xhs_resolve_recovery", "5");
        values.put("payment_sdk_bridge", "6");
        values.put("config", "{\"version\":1}");

        Map<String, String> sanitized = ConfigProviderValueValidator.sanitizeEntries(values.entrySet());

        assertEquals("1", sanitized.get("auto_open_links"));
        assertEquals("0", sanitized.get("native_link_capture"));
        assertEquals("3", sanitized.get("module_debug_logging"));
        assertEquals("4", sanitized.get("enhance_mode_enabled"));
        assertEquals("5", sanitized.get("xhs_resolve_recovery"));
        assertEquals("6", sanitized.get("payment_sdk_bridge"));
        assertEquals("{\"version\":1}", sanitized.get("config"));
    }

    @Test
    public void rejectsUnknownKeysNullsUnsupportedTypesAndOversizedValues() {
        assertRejected(single("unknown", "1"));
        assertRejected(single("auto_open_links", null));
        assertRejected(single(null, "1"));
        assertRejected(single("auto_open_links", 1.5d));
        assertRejected(single("module_debug_logging", 3));
        assertRejected(single("enhance_mode_enabled", 4L));
        assertRejected(single("xhs_resolve_recovery", (short) 5));
        assertRejected(single("payment_sdk_bridge", (byte) 6));
        assertRejected(single("config", repeat('a', 32 * 1024 + 1)));
        assertRejected(new LinkedHashMap<String, Object>());
    }

    @Test
    public void rejectsMoreThanThirtyTwoValuesAndReturnsAnImmutableSnapshot() {
        // ContentValues keys are unique; use entries directly to exercise the batch bound.
        java.util.LinkedHashSet<Map.Entry<String, Object>> entries = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 33; i++) {
            entries.add(new AbstractMap.SimpleEntry<>("auto_open_links", i));
        }
        try {
            ConfigProviderValueValidator.sanitizeEntries(entries);
            throw new AssertionError("Expected an IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }

        Map<String, String> snapshot = ConfigProviderValueValidator.sanitizeEntries(single("auto_open_links", "1").entrySet());
        try {
            snapshot.put("auto_open_links", "0");
            throw new AssertionError("Expected an UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
        assertFalse(ConfigProviderValueValidator.isAllowedKey("unknown"));
        assertTrue(ConfigProviderValueValidator.isAllowedKey("custom_mappings"));
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }

    private static void assertRejected(Map<String, Object> values) {
        try {
            ConfigProviderValueValidator.sanitizeEntries(values.entrySet());
            throw new AssertionError("Expected an IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) builder.append(value);
        return builder.toString();
    }
}
