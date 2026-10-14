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
