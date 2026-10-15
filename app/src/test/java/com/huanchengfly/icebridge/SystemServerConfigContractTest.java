package com.huanchengfly.icebridge;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemServerConfigContractTest {
    @Test public void unknownSnapshotUserIsRejected() {
        SystemServerConfigContract.Snapshot snapshot = SystemServerConfigContract.parseValues(
                SystemServerConfigContract.SCHEMA_VERSION, SystemServerConfigContract.STATUS_OK, null,
                true, true, true, true, true, null, null, -1);
        assertFalse(snapshot.available);
        assertEquals("snapshot-user-invalid", snapshot.status);
    }

    @Test public void scopedRulesAndUserSurviveSnapshotParsing() {
        SystemServerConfigContract.Snapshot snapshot = SystemServerConfigContract.parseValues(
                SystemServerConfigContract.SCHEMA_VERSION, SystemServerConfigContract.STATUS_OK, null,
                true, true, true, false, true, new String[]{"com.example.app"},
                new String[]{"com.tencent.mm|example.com|com.example.app|manual|fixed_deep_link"}, 10);
        assertTrue(snapshot.available);
        assertEquals(10, snapshot.userId);
        assertEquals("com.tencent.mm", snapshot.scopedRules.get(0).sourcePackage);
        assertEquals("fixed_deep_link", snapshot.scopedRules.get(0).urlConversion);
    }

    @Test public void oldHostOnlyProjectionCannotSilentlyLoseSource() {
        SystemServerConfigContract.Snapshot snapshot = SystemServerConfigContract.parseValues(
                SystemServerConfigContract.SCHEMA_VERSION, SystemServerConfigContract.STATUS_OK, null,
                true, true, true, false, true, null, new String[]{"example.com=com.example.app"}, 0);
        assertFalse(snapshot.available);
        assertEquals("scoped-rules-invalid", snapshot.status);
    }
    @Test
    public void nullSnapshotIsUnavailableWithSpecificStatus() {
        SystemServerConfigContract.Snapshot snapshot = parse(null, null, null,
                false, false, false, false, true, null, null);

        assertFalse(snapshot.available);
        assertEquals("null-snapshot", snapshot.status);
    }

    @Test
    public void invalidSchemaIsRejectedBeforeStatusParsing() {
        SystemServerConfigContract.Snapshot snapshot = parse(
                SystemServerConfigContract.SCHEMA_VERSION - 1,
                SystemServerConfigContract.STATUS_OK, null,
                false, false, false, false, true, null, null);

        assertFalse(snapshot.available);
        assertEquals("schema-invalid", snapshot.status);
    }

    @Test
    public void missingStatusIsUnavailable() {
        SystemServerConfigContract.Snapshot snapshot = parse(
                SystemServerConfigContract.SCHEMA_VERSION, null, null,
                false, false, false, false, true, null, null);

        assertFalse(snapshot.available);
        assertEquals("status-missing", snapshot.status);
    }

    @Test
    public void explicitSnapshotErrorOverridesStatus() {
        SystemServerConfigContract.Snapshot snapshot = parse(
                SystemServerConfigContract.SCHEMA_VERSION,
                SystemServerConfigContract.STATUS_UNAVAILABLE,
                "provider-call-NullPointerException",
                false, false, false, false, true, null, null);

        assertFalse(snapshot.available);
        assertEquals("provider-call-NullPointerException", snapshot.status);
    }

    @Test
    public void availableSnapshotPreservesPaymentBridgeDefault() {
        SystemServerConfigContract.Snapshot snapshot = parse(
                SystemServerConfigContract.SCHEMA_VERSION,
                SystemServerConfigContract.STATUS_OK, null,
                false, false, false, false, true, null, null);

        assertTrue(snapshot.available);
        assertTrue(snapshot.paymentSdkBridge);
        assertEquals(SystemServerConfigContract.STATUS_OK, snapshot.status);
    }

    @Test
    public void frozenPackagesAreFilteredAndDeduplicated() {
        SystemServerConfigContract.Snapshot snapshot = parse(
                SystemServerConfigContract.SCHEMA_VERSION,
                SystemServerConfigContract.STATUS_OK, null,
                false, false, false, false, true,
                new String[]{"com.example.one", "", null, "com.example.one", "com.example.two"},
                null);

        assertEquals(Arrays.asList("com.example.one", "com.example.two"),
                Arrays.asList(snapshot.frozenPackages.toArray(new String[0])));
    }

    @Test
    public void missingOnlineRulesBecomeEmptyArray() {
        SystemServerConfigContract.Snapshot snapshot = parse(
                SystemServerConfigContract.SCHEMA_VERSION,
                SystemServerConfigContract.STATUS_OK, null,
                false, false, false, false, true, null, null);

        assertArrayEquals(new String[0], snapshot.onlineHostRules);
        assertEquals(Collections.emptySet(), snapshot.frozenPackages);
    }

    @Test public void autonomousSnapshotPreservesRuntimePreferencesAndAcceptedRevision() throws Exception {
        String hash = repeat('a', 64);
        SystemServerConfigContract.Snapshot snapshot = SystemServerConfigContract.parseValues(
                SystemServerConfigContract.SCHEMA_VERSION, SystemServerConfigContract.STATUS_OK, null,
                true, true, true, false, true, null,
                new String[]{"com.tencent.mm|example.com|com.example.app|manual|fixed_deep_link"}, 10,
                true, false, false, false, 5, hash, repeat('b', 64));
        assertTrue(snapshot.available);
        assertTrue(snapshot.autonomousEnabled);
        assertFalse(snapshot.onlyFrozen);
        assertFalse(snapshot.browserFallback);
        assertFalse(snapshot.onlineUpdatesEnabled);
        assertEquals(5, snapshot.onlineRevision);
        assertEquals(hash, snapshot.onlineSha256);
        assertEquals(10, snapshot.toRuntimeState(123).userId);
        assertEquals("com.tencent.mm", snapshot.toRuntimeState(123).scopedRules.get(0).sourcePackage);
        assertEquals(5, snapshot.toRuntimeState(123).online.highestRevision);
        assertEquals("", snapshot.toRuntimeState(123).online.activeRaw);
    }

    @Test public void independentModeRequiresCompleteTypedRevisionAndConfigFingerprint() {
        assertRuntimeInvalid(0, "", "", "runtime-fingerprint-invalid");
        assertRuntimeInvalid(-1, "", repeat('b', 64), "online-metadata-invalid");
        assertRuntimeInvalid(5, "", repeat('b', 64), "online-metadata-invalid");
        assertRuntimeInvalid(0, repeat('a', 64), repeat('b', 64), "online-metadata-invalid");
        assertRuntimeInvalid(5, repeat('a', 64), "invalid", "runtime-fingerprint-invalid");
    }

    @Test public void olderJvmParseApiKeepsIndependentModeOptedOut() {
        SystemServerConfigContract.Snapshot snapshot = parse(SystemServerConfigContract.SCHEMA_VERSION,
                SystemServerConfigContract.STATUS_OK, null, true, true, true, false, true, null, null);
        assertTrue(snapshot.available);
        assertFalse(snapshot.autonomousEnabled);
        assertTrue(snapshot.onlyFrozen);
        assertTrue(snapshot.browserFallback);
        assertTrue(snapshot.onlineUpdatesEnabled);
        assertEquals(0, snapshot.onlineRevision);
    }

    private static void assertRuntimeInvalid(long revision, String hash, String fingerprint, String expected) {
        SystemServerConfigContract.Snapshot snapshot = SystemServerConfigContract.parseValues(
                SystemServerConfigContract.SCHEMA_VERSION, SystemServerConfigContract.STATUS_OK, null,
                true, true, true, false, true, null, null, 0,
                true, true, true, true, revision, hash, fingerprint);
        assertFalse(snapshot.available);
        assertEquals(expected, snapshot.status);
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        Arrays.fill(values, value);
        return new String(values);
    }

    private static SystemServerConfigContract.Snapshot parse(Integer schemaVersion, String status,
                                                              String error, boolean recoveryEnabled,
                                                              boolean autoOpenLinks, boolean nativeLinkCapture,
                                                              boolean clipboardAutoOpen, boolean paymentSdkBridge,
                                                              String[] frozenPackages, String[] onlineHostRules) {
        return SystemServerConfigContract.parseValues(schemaVersion, status, error, recoveryEnabled,
                autoOpenLinks, nativeLinkCapture, clipboardAutoOpen, paymentSdkBridge,
                frozenPackages, onlineHostRules, 0);
    }
}
