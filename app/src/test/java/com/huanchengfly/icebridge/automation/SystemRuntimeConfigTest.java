package com.huanchengfly.icebridge.automation;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.*;

public class SystemRuntimeConfigTest {
    private static final int SCHEMA = 8;
    private static final String RULE = "com.tencent.mm|example.com|com.example.reader|manual|fixed_deep_link";
    private static final String ONLINE = "*|online.example.com|com.example.reader|online|none";
    private static final String BUNDLE_RULE = "{\"sourcePackage\":\"*\",\"host\":\"online.example.com\","
            + "\"package\":\"com.example.reader\",\"conversion\":\"none\",\"note\":\"规则\"}";

    @Test public void roundTripPreservesSwitchesSourcesAndConversionsWithoutFrozenState() throws Exception {
        SystemRuntimeConfig.State original = state(10, new String[]{RULE, ONLINE}, SystemRuntimeConfig.OnlineState.empty());
        byte[] bytes = SystemRuntimeConfig.encode(original);
        SystemRuntimeConfig.State restored = SystemRuntimeConfig.decode(bytes, 10, SCHEMA);
        assertEquals(10, restored.userId);
        assertEquals(12345, restored.savedAtEpochMillis);
        assertTrue(restored.autonomousEnabled);
        assertTrue(restored.recoveryEnabled);
        assertTrue(restored.autoOpenLinks);
        assertFalse(restored.nativeLinkCapture);
        assertTrue(restored.clipboardAutoOpen);
        assertFalse(restored.paymentSdkBridge);
        assertTrue(restored.onlyFrozen);
        assertFalse(restored.browserFallback);
        assertTrue(restored.onlineUpdatesEnabled);
        assertArrayEquals(new String[]{RULE, ONLINE}, restored.encodedRules());
        assertEquals("com.tencent.mm", restored.scopedRules.get(0).sourcePackage);
        assertEquals("fixed_deep_link", restored.scopedRules.get(0).urlConversion);
        assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("frozenPackages"));
    }

    @Test public void immutableRulesCannotBeChangedThroughCallerArrayOrReturnedCollections() throws Exception {
        String[] rules = {RULE};
        SystemRuntimeConfig.State state = state(0, rules, null);
        rules[0] = ONLINE;
        assertArrayEquals(new String[]{RULE}, state.encodedRules());
        String[] returned = state.encodedRules();
        returned[0] = ONLINE;
        assertArrayEquals(new String[]{RULE}, state.encodedRules());
        try { state.scopedRules.clear(); fail("mutable rules"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void oneUsersEnvelopeCannotLoadForAnotherUser() throws Exception {
        byte[] bytes = SystemRuntimeConfig.encode(state(10, new String[]{RULE}, null));
        rejects(() -> SystemRuntimeConfig.decode(bytes, 0, SCHEMA), "runtime-user-mismatch");
        rejects(() -> SystemRuntimeConfig.decode(bytes, 10, SCHEMA + 1), "runtime-provider-schema-mismatch");
    }

    @Test public void rejectsInvalidUserAndSchemaBeforeTheyCanBecomePaths() throws Exception {
        rejects(() -> state(-1, new String[]{RULE}, null), "runtime-user-id");
        rejects(() -> state(1000, new String[]{RULE}, null), "runtime-user-id");
        rejects(() -> new SystemRuntimeConfig.State(0, 0, 0, true, false, false, false,
                false, false, true, true, true, null, null), "runtime-provider-schema");
    }

    @Test public void unsupportedEnvelopeVersionAndFractionalIntegersAreRejected() throws Exception {
        rejectsJson(json().replace("\"version\":1", "\"version\":2"), "runtime-version");
        rejectsJson(json().replace("\"savedAtEpochMillis\":12345", "\"savedAtEpochMillis\":1.5"), "runtime-integer-savedAtEpochMillis");
        rejectsJson(json().replace("\"savedAtEpochMillis\":12345", "\"savedAtEpochMillis\":-1"), "runtime-saved-at");
    }

    @Test public void strictJsonRejectsDuplicateKeysUnknownFieldsCommentsAndTrailingData() throws Exception {
        rejectsJson(json().replace("\"version\":1", "\"version\":1,\"version\":1"), "runtime-json-key");
        rejectsJson(json().replace("\"version\":1", "\"version\":1,\"frozenPackages\":[]"), "runtime-unknown-field-frozenPackages");
        rejectsAny(() -> decode(json().replace("\"version\":1", "/* comment */\"version\":1")));
        rejectsAny(() -> decode(json() + " {}"));
        rejectsAny(() -> decode(json().replace("\"version\"", "'version'")));
    }

    @Test public void booleansNeverCoerceMissingStringsOrNumbersIntoUserConsent() throws Exception {
        rejectsJson(json().replace("\"autonomousEnabled\":true", "\"autonomousEnabled\":\"true\""), "runtime-boolean-autonomousEnabled");
        rejectsJson(json().replace("\"autonomousEnabled\":true", "\"autonomousEnabled\":1"), "runtime-boolean-autonomousEnabled");
        rejectsJson(json().replace("\"autonomousEnabled\":true,", ""), "runtime-boolean-autonomousEnabled");
    }

    @Test public void malformedAndOversizedUtf8IsRejected() throws Exception {
        rejects(() -> SystemRuntimeConfig.decode(new byte[]{(byte) 0xc3, 0x28}, 0, SCHEMA), "runtime-utf8");
        rejects(() -> SystemRuntimeConfig.decode(new byte[SystemRuntimeConfig.MAX_BYTES + 1], 0, SCHEMA), "runtime-size");
        rejects(() -> SystemRuntimeConfig.readBounded(new ByteArrayInputStream(new byte[SystemRuntimeConfig.MAX_BYTES + 1])), "runtime-size");
        assertArrayEquals(new byte[]{1, 2, 3}, SystemRuntimeConfig.readBounded(new ByteArrayInputStream(new byte[]{1, 2, 3})));
    }

    @Test public void scopedRuleBoundsRejectWholeEnvelopeAndPreserveLastGoodAtCaller() throws Exception {
        String[] tooMany = new String[769];
        Arrays.fill(tooMany, RULE);
        rejects(() -> state(0, tooMany, null), "runtime-scoped-rules");
        rejects(() -> state(0, new String[]{RULE.replace("manual", "script")}, null), "runtime-scoped-rules");
        rejects(() -> state(0, new String[]{RULE.replace("com.tencent.mm", "")}, null), "runtime-scoped-rules");
        String[] tooManyHosts = new String[97];
        for (int i = 0; i < tooManyHosts.length; i++) tooManyHosts[i] = RULE.replace("example.com", "h" + i + ".example.com");
        rejects(() -> state(0, tooManyHosts, null), "runtime-scoped-rules");
    }

    @Test public void highWaterMarkCanExistWithoutRawAppBundleAndDoesNotEnableLegacyFallback() throws Exception {
        String hash = repeat('a', 64);
        SystemRuntimeConfig.OnlineState online = new SystemRuntimeConfig.OnlineState("", "", "", "", 5, hash, true);
        SystemRuntimeConfig.State loaded = SystemRuntimeConfig.decode(SystemRuntimeConfig.encode(state(0, new String[]{ONLINE}, online)), 0, SCHEMA);
        assertEquals(5, loaded.online.highestRevision);
        assertEquals(hash, loaded.online.highestSha256);
        assertTrue(loaded.online.migrated);
        assertEquals("online", loaded.scopedRules.get(0).source);
    }

    @Test public void validatedOnlineRawAndBackupRoundTripWithIndependentHighWaterMark() throws Exception {
        String old = bundle(4, BUNDLE_RULE), latest = bundle(5, "");
        SystemRuntimeConfig.OnlineState online = new SystemRuntimeConfig.OnlineState(latest, hash(latest), old, hash(old), 5, hash(latest), true);
        SystemRuntimeConfig.State loaded = SystemRuntimeConfig.decode(SystemRuntimeConfig.encode(state(0, new String[]{RULE}, online)), 0, SCHEMA);
        assertEquals(latest, loaded.online.activeRaw);
        assertEquals(old, loaded.online.backupRaw);
        assertEquals(5, loaded.online.highestRevision);
        // Explicit empty withdrawal stays active; old backup does not become the current rules.
        assertTrue(OnlineRuleBundle.parse(loaded.online.activeRaw, loaded.online.activeSha256).rules.isEmpty());
    }

    @Test public void onlineBackupMayBeOlderButCannotResetAcceptedRevision() throws Exception {
        String old = bundle(4, BUNDLE_RULE), newestHash = repeat('a', 64);
        SystemRuntimeConfig.OnlineState recovered = new SystemRuntimeConfig.OnlineState("", "", old, hash(old), 5, newestHash, true);
        assertEquals(5, recovered.highestRevision);
        rejects(() -> new SystemRuntimeConfig.OnlineState(old, hash(old), "", "", 5, newestHash, true), "runtime-online-cache-revision");
        String future = bundle(6, BUNDLE_RULE);
        rejects(() -> new SystemRuntimeConfig.OnlineState("", "", future, hash(future), 5, newestHash, true), "runtime-online-cache-revision");
    }

    @Test public void onlineChecksumsAndRevisionStateMustBeConsistent() throws Exception {
        String raw = bundle(5, BUNDLE_RULE);
        rejects(() -> new SystemRuntimeConfig.OnlineState(raw, repeat('0', 64), "", "", 5, hash(raw), true), "bundle-checksum-mismatch");
        rejects(() -> new SystemRuntimeConfig.OnlineState(raw, hash(raw), "", "", 5, repeat('0', 64), true), "runtime-online-cache-hash");
        rejects(() -> new SystemRuntimeConfig.OnlineState("", "", "", "", 5, "", true), "runtime-online-highest-hash");
        rejects(() -> new SystemRuntimeConfig.OnlineState("", repeat('a', 64), "", "", 5, repeat('a', 64), true), "runtime-online-orphan-hash");
        rejects(() -> new SystemRuntimeConfig.OnlineState(raw, hash(raw), "", "", 0, "", true), "runtime-online-empty-state");
    }

    @Test public void onlineReplacementPreservesUserSwitchesAndManualSourceScope() throws Exception {
        SystemRuntimeConfig.State original = state(0, new String[]{RULE}, null);
        String raw = bundle(5, BUNDLE_RULE);
        SystemRuntimeConfig.State updated = original.withOnline(new SystemRuntimeConfig.OnlineState(raw, hash(raw), "", "", 5, hash(raw), true),
                new String[]{RULE, ONLINE}, 12346);
        assertEquals(12346, updated.savedAtEpochMillis);
        assertTrue(updated.onlineUpdatesEnabled);
        assertTrue(updated.autonomousEnabled);
        assertFalse(updated.nativeLinkCapture);
        assertEquals(original.scopedRules.get(0), updated.scopedRules.get(0));
        assertEquals(1, original.scopedRules.size());
    }

    @Test public void disablingOnlineRefreshRetainsAlreadyAcceptedRulesAndRawBundle() throws Exception {
        String raw = bundle(5, BUNDLE_RULE);
        SystemRuntimeConfig.OnlineState online = new SystemRuntimeConfig.OnlineState(raw, hash(raw), "", "", 5, hash(raw), true);
        SystemRuntimeConfig.State disabledRefresh = new SystemRuntimeConfig.State(SCHEMA, 0, 12345, true,
                true, true, false, true, false, true, false, false, new String[]{RULE, ONLINE}, online);
        SystemRuntimeConfig.State restored = SystemRuntimeConfig.decode(SystemRuntimeConfig.encode(disabledRefresh), 0, SCHEMA);
        assertFalse(restored.onlineUpdatesEnabled);
        assertArrayEquals(new String[]{RULE, ONLINE}, restored.encodedRules());
        assertEquals(raw, restored.online.activeRaw);
    }

    @Test public void fingerprintIgnoresTimestampsAndRawCacheButTracksRulesAndUserSwitches() throws Exception {
        String raw = bundle(5, BUNDLE_RULE);
        SystemRuntimeConfig.OnlineState cached = new SystemRuntimeConfig.OnlineState(raw, hash(raw), "", "", 5, hash(raw), true);
        SystemRuntimeConfig.OnlineState metadata = new SystemRuntimeConfig.OnlineState("", "", "", "", 5, hash(raw), true);
        SystemRuntimeConfig.State one = state(0, new String[]{RULE, ONLINE}, cached);
        SystemRuntimeConfig.State same = one.withOnline(metadata, one.encodedRules(), 55555);
        assertEquals(SystemRuntimeConfig.fingerprint(one), SystemRuntimeConfig.fingerprint(same));
        assertFalse(SystemRuntimeConfig.fingerprint(one).equals(SystemRuntimeConfig.fingerprint(state(10, one.encodedRules(), cached))));
        assertFalse(SystemRuntimeConfig.fingerprint(one).equals(SystemRuntimeConfig.fingerprint(state(0, new String[]{RULE}, cached))));
        SystemRuntimeConfig.State differentSwitch = new SystemRuntimeConfig.State(SCHEMA, 0, 12345, true,
                false, true, false, true, false, true, false, true, one.encodedRules(), cached);
        assertFalse(SystemRuntimeConfig.fingerprint(one).equals(SystemRuntimeConfig.fingerprint(differentSwitch)));
    }

    private static SystemRuntimeConfig.State state(int userId, String[] rules, SystemRuntimeConfig.OnlineState online) throws IOException {
        return new SystemRuntimeConfig.State(SCHEMA, userId, 12345, true,
                true, true, false, true, false, true, false, true, rules, online);
    }

    private static String json() throws IOException { return new String(SystemRuntimeConfig.encode(state(0, new String[]{RULE}, null)), StandardCharsets.UTF_8); }
    private static SystemRuntimeConfig.State decode(String json) throws IOException { return SystemRuntimeConfig.decode(json.getBytes(StandardCharsets.UTF_8), 0, SCHEMA); }
    private static String bundle(int revision, String rules) { return "{\"format\":\"icebridge-online-rules\",\"version\":1,\"revision\":" + revision + ",\"rules\":[" + rules + "]}"; }
    private static String hash(String raw) { return OnlineLinkRuleRepository.sha256(raw); }
    private static String repeat(char value, int count) { char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars); }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String reason) throws Exception {
        try { action.run(); fail("accepted invalid data: " + reason); }
        catch (IOException expected) { assertEquals(reason, expected.getMessage()); }
    }
    private static void rejectsAny(Checked action) throws Exception {
        try { action.run(); fail("accepted invalid data"); }
        catch (IOException expected) { }
    }
    private static void rejectsJson(String json, String reason) throws Exception { rejects(() -> decode(json), reason); }
}
