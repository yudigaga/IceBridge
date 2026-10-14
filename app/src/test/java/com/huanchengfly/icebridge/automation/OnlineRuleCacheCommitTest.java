package com.huanchengfly.icebridge.automation;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class OnlineRuleCacheCommitTest {
    @Test public void failedFirstMigrationKeepsLegacyCacheAndMetadata() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.values.put("online_link_rule_hosts", "[{\"host\":\"example.com\",\"package\":\"com.example.reader\"}]");
        preferences.values.put("online_link_rule_updated_at", 123L);
        preferences.values.put("unrelated_manual_rules", "untouched");
        Map<String, Object> before = new HashMap<>(preferences.values);
        preferences.failedCommits = 1;
        commitFails(preferences, bundle(1, false));
        assertEquals(before, preferences.values);
    }

    @Test public void emptyUpdateReplacesActiveAndStoresValidatedBackupAndHighWaterMark() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.values.put("online_link_rule_hosts", "legacy-cache");
        OnlineRuleBundle initial = bundle(1, false);
        commit(preferences, initial);
        OnlineRuleBundle empty = bundle(2, true);
        commit(preferences, empty);
        assertEquals(empty.raw, preferences.values.get("online_owned_rules_v1"));
        assertEquals(initial.raw, preferences.values.get("online_owned_rules_backup_v1"));
        assertEquals(empty.checksum, preferences.values.get("online_owned_rules_highest_sha256"));
        assertEquals(2L, preferences.values.get("online_owned_rules_highest_revision"));
        assertEquals(true, preferences.values.get("online_owned_rules_migrated"));
        assertFalse(preferences.values.containsKey("online_link_rule_hosts"));
        OnlineRuleBundle.Cache selected = selected(preferences);
        assertTrue(selected.bundle.rules.isEmpty());
        assertFalse(selected.usingBackup);
        assertFalse(selected.allowLegacy);
    }

    @Test public void failedOwnedUpdateRestoresActiveBackupAndRevisionAsOneUnit() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        commit(preferences, bundle(1, false));
        commit(preferences, bundle(2, true));
        Map<String, Object> before = new HashMap<>(preferences.values);
        // Android commits change memory even if disk writes fail; even a failed rollback disk
        // write must restore the previously accepted in-memory routing snapshot.
        preferences.failedCommits = 2;
        commitFails(preferences, bundle(3, false));
        assertEquals(before, preferences.values);
        assertEquals(2, selected(preferences).bundle.revision);
        assertTrue(selected(preferences).bundle.rules.isEmpty());
    }

    private static OnlineRuleBundle bundle(long revision, boolean empty) throws Exception {
        String rule = "{\"sourcePackage\":\"*\",\"host\":\"example.com\",\"package\":\"com.example.reader\",\"conversion\":\"none\",\"note\":\"\"}";
        String raw = "{\"format\":\"icebridge-online-rules\",\"version\":1,\"revision\":"
                + revision + ",\"rules\":[" + (empty ? "" : rule) + "]}";
        return OnlineRuleBundle.parse(raw, OnlineLinkRuleRepository.sha256(raw));
    }

    private static void commit(MemoryPreferences preferences, OnlineRuleBundle bundle) {
        OnlineLinkRuleRepository.commitOwnedRules(preferences, bundle,
                OnlineLinkRuleRepository.Result.success(bundle.rules.size(), bundle.revision * 1000, "test-owned-source"));
    }

    private static void commitFails(MemoryPreferences preferences, OnlineRuleBundle bundle) {
        try { commit(preferences, bundle); fail("failed disk commit was accepted"); }
        catch (IllegalStateException expected) { assertEquals("rule-cache-commit-failed", expected.getMessage()); }
    }

    private static OnlineRuleBundle.Cache selected(MemoryPreferences preferences) {
        return OnlineRuleBundle.selectCache(preferences.getString("online_owned_rules_v1", ""),
                preferences.getString("online_owned_rules_sha256", ""),
                preferences.getString("online_owned_rules_backup_v1", ""),
                preferences.getString("online_owned_rules_backup_sha256", ""),
                preferences.getLong("online_owned_rules_highest_revision", 0),
                preferences.getString("online_owned_rules_highest_sha256", ""),
                preferences.getBoolean("online_owned_rules_migrated", false));
    }

    /** Models the material SharedPreferences behavior: memory changes before disk success. */
    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        int failedCommits;
        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String fallback) { return values.containsKey(key) ? (String) values.get(key) : fallback; }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String key, Set<String> fallback) { return values.containsKey(key) ? (Set<String>) values.get(key) : fallback; }
        @Override public int getInt(String key, int fallback) { return values.containsKey(key) ? (Integer) values.get(key) : fallback; }
        @Override public long getLong(String key, long fallback) { return values.containsKey(key) ? (Long) values.get(key) : fallback; }
        @Override public float getFloat(String key, float fallback) { return values.containsKey(key) ? (Float) values.get(key) : fallback; }
        @Override public boolean getBoolean(String key, boolean fallback) { return values.containsKey(key) ? (Boolean) values.get(key) : fallback; }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> updates = new HashMap<>();
                final Set<String> removals = new HashSet<>();
                boolean clear;
                @Override public Editor putString(String key, String value) { updates.put(key, value); return this; }
                @Override public Editor putStringSet(String key, Set<String> value) { updates.put(key, value == null ? null : new HashSet<>(value)); return this; }
                @Override public Editor putInt(String key, int value) { updates.put(key, value); return this; }
                @Override public Editor putLong(String key, long value) { updates.put(key, value); return this; }
                @Override public Editor putFloat(String key, float value) { updates.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { updates.put(key, value); return this; }
                @Override public Editor remove(String key) { removals.add(key); return this; }
                @Override public Editor clear() { clear = true; return this; }
                @Override public boolean commit() {
                    if (clear) values.clear();
                    for (String key : removals) values.remove(key);
                    values.putAll(updates);
                    if (failedCommits > 0) { failedCommits--; return false; }
                    return true;
                }
                @Override public void apply() { commit(); }
            };
        }
    }
}
