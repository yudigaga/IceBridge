package com.huanchengfly.icebridge.automation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.huanchengfly.icebridge.ui.theme.ThemePolicy;

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

    @Test public void unifiedRoutingChoiceSurvivesFrozenBodyRestoreAndOnlineRefreshWithDistinctAcknowledgements() throws Exception {
        String raw = bundle(5, BUNDLE_RULE);
        SystemRuntimeConfig.OnlineState online = new SystemRuntimeConfig.OnlineState(raw, hash(raw), "", "", 5, hash(raw), true);
        SystemRuntimeConfig.State baseline = state(0, new String[]{RULE, ONLINE}, online);
        java.util.Set<String> fingerprints = new java.util.HashSet<>();
        for (boolean onlyFrozen : new boolean[]{true, false}) {
            JsonObject json = JsonParser.parseString(new String(SystemRuntimeConfig.encode(baseline),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            json.addProperty("onlyFrozen", onlyFrozen);
            SystemRuntimeConfig.State restored = SystemRuntimeConfig.decode(
                    json.toString().getBytes(StandardCharsets.UTF_8), 0, SCHEMA);
            assertEquals(onlyFrozen, restored.onlyFrozen);
            assertArrayEquals(baseline.encodedRules(), restored.encodedRules());
            assertEquals(raw, restored.online.activeRaw);
            assertEquals(5, restored.online.highestRevision);
            SystemRuntimeConfig.State refreshed = restored.withOnline(restored.online, restored.encodedRules(), 99999);
            assertEquals(onlyFrozen, refreshed.onlyFrozen);
            assertEquals(SystemRuntimeConfig.fingerprint(restored), SystemRuntimeConfig.fingerprint(refreshed));
            fingerprints.add(SystemRuntimeConfig.fingerprint(restored));
        }
        assertEquals("Frozen-only and unified routing must never share a system acknowledgement", 2, fingerprints.size());
    }

    @Test public void oldVersionOneEnvelopeDefaultsThemeWithoutLosingRulesOrOnlineHighWater() throws Exception {
        String old = bundle(4, BUNDLE_RULE), latest = bundle(5, "");
        SystemRuntimeConfig.OnlineState online = new SystemRuntimeConfig.OnlineState(latest,
                hash(latest), old, hash(old), 5, hash(latest), true);
        SystemRuntimeConfig.State original = state(0, new String[]{RULE, ONLINE}, online);
        JsonObject legacy = JsonParser.parseString(new String(SystemRuntimeConfig.encode(original),
                StandardCharsets.UTF_8)).getAsJsonObject();
        legacy.remove("transitSeedColor");
        legacy.remove("transitAppearance");
        legacy.remove("transitCardEnabled");
        legacy.remove("openLinksInSmallWindow");
        legacy.remove("smallWindowPackages");
        legacy.remove("smallWindowLayout");
        assertFalse(legacy.has("transitSeedColor"));
        assertFalse(legacy.has("transitAppearance"));
        SystemRuntimeConfig.State restored = decode(legacy.toString());
        assertEquals(ThemePolicy.DEFAULT_SEED_COLOR, restored.transitSeedColor);
        assertEquals(ThemePolicy.APPEARANCE_SYSTEM, restored.transitAppearance);
        assertFalse(restored.transitCardEnabled);
        assertFalse(restored.openLinksInSmallWindow);
        assertTrue(restored.smallWindowPackages.isEmpty());
        assertEquals(SmallWindowLayout.DEFAULT, restored.smallWindowLayout);
        assertArrayEquals(original.encodedRules(), restored.encodedRules());
        assertEquals(5, restored.online.highestRevision);
        assertEquals(hash(latest), restored.online.highestSha256);
        assertEquals(latest, restored.online.activeRaw);
        assertEquals(old, restored.online.backupRaw);
    }

    @Test public void transitThemeRoundTripsAndSurvivesOnlineReplacement() throws Exception {
        SystemRuntimeConfig.State original = themed(0xff7e57c2, ThemePolicy.APPEARANCE_DARK);
        SystemRuntimeConfig.State restored = SystemRuntimeConfig.decode(SystemRuntimeConfig.encode(original), 0, SCHEMA);
        assertEquals(0xff7e57c2, restored.transitSeedColor);
        assertEquals(ThemePolicy.APPEARANCE_DARK, restored.transitAppearance);
        String raw = bundle(5, BUNDLE_RULE);
        SystemRuntimeConfig.State updated = restored.withOnline(new SystemRuntimeConfig.OnlineState(raw,
                hash(raw), "", "", 5, hash(raw), true), new String[]{RULE, ONLINE}, 45678);
        assertEquals(restored.transitSeedColor, updated.transitSeedColor);
        assertEquals(restored.transitAppearance, updated.transitAppearance);
        assertEquals(5, updated.online.highestRevision);
    }

    @Test public void transitThemeParticipatesInLogicalConfigFingerprint() throws Exception {
        SystemRuntimeConfig.State dark = themed(0xff7e57c2, ThemePolicy.APPEARANCE_DARK);
        assertFalse(SystemRuntimeConfig.fingerprint(dark).equals(SystemRuntimeConfig.fingerprint(
                themed(0xff2979ff, ThemePolicy.APPEARANCE_DARK))));
        assertFalse(SystemRuntimeConfig.fingerprint(dark).equals(SystemRuntimeConfig.fingerprint(
                themed(0xff7e57c2, ThemePolicy.APPEARANCE_LIGHT))));
        assertEquals(SystemRuntimeConfig.fingerprint(dark), SystemRuntimeConfig.fingerprint(
                dark.withOnline(dark.online, dark.encodedRules(), 55555)));
    }

    @Test public void optionalTransitCardDefaultsOffWithoutLosingThemeRulesOrOnlineBackup() throws Exception {
        String old = bundle(4, BUNDLE_RULE), latest = bundle(5, "");
        SystemRuntimeConfig.OnlineState online = new SystemRuntimeConfig.OnlineState(latest,
                hash(latest), old, hash(old), 5, hash(latest), true);
        SystemRuntimeConfig.State original = new SystemRuntimeConfig.State(SCHEMA, 0, 12345, true,
                true, true, false, true, false, true, false, true, new String[]{RULE, ONLINE},
                online, 0xff7e57c2, ThemePolicy.APPEARANCE_DARK, true);
        JsonObject legacy = JsonParser.parseString(new String(SystemRuntimeConfig.encode(original),
                StandardCharsets.UTF_8)).getAsJsonObject();
        legacy.remove("transitCardEnabled");
        SystemRuntimeConfig.State restored = decode(legacy.toString());
        assertFalse(restored.transitCardEnabled);
        assertEquals(original.transitSeedColor, restored.transitSeedColor);
        assertEquals(original.transitAppearance, restored.transitAppearance);
        assertArrayEquals(original.encodedRules(), restored.encodedRules());
        assertEquals(5, restored.online.highestRevision);
        assertEquals(hash(latest), restored.online.highestSha256);
        assertEquals(latest, restored.online.activeRaw);
        assertEquals(old, restored.online.backupRaw);
    }

    @Test public void transitCardChoiceRoundTripsAndChangesFingerprintButNotRoutingPolicy() throws Exception {
        SystemRuntimeConfig.State defaultState = themed(0xff7e57c2, ThemePolicy.APPEARANCE_DARK);
        assertFalse(defaultState.transitCardEnabled);
        for (boolean enabled : new boolean[]{false, true}) {
            SystemRuntimeConfig.State original = new SystemRuntimeConfig.State(SCHEMA, 0, 12345,
                    defaultState.autonomousEnabled, defaultState.recoveryEnabled, defaultState.autoOpenLinks,
                    defaultState.nativeLinkCapture, defaultState.clipboardAutoOpen, defaultState.paymentSdkBridge,
                    defaultState.onlyFrozen, defaultState.browserFallback, defaultState.onlineUpdatesEnabled,
                    defaultState.encodedRules(), defaultState.online, defaultState.transitSeedColor,
                    defaultState.transitAppearance, enabled);
            SystemRuntimeConfig.State restored = SystemRuntimeConfig.decode(SystemRuntimeConfig.encode(original), 0, SCHEMA);
            assertEquals(enabled, restored.transitCardEnabled);
            assertEquals(defaultState.nativeLinkCapture, restored.nativeLinkCapture);
            assertEquals(defaultState.recoveryEnabled, restored.recoveryEnabled);
            assertArrayEquals(defaultState.encodedRules(), restored.encodedRules());
            assertEquals(enabled, restored.withOnline(restored.online, restored.encodedRules(), 99999).transitCardEnabled);
            assertEquals(SystemRuntimeConfig.fingerprint(original), SystemRuntimeConfig.fingerprint(
                    restored.withOnline(restored.online, restored.encodedRules(), 99999)));
            assertEquals(enabled, !SystemRuntimeConfig.fingerprint(defaultState).equals(
                    SystemRuntimeConfig.fingerprint(original)));
        }
    }

    @Test public void optionalTransitCardNeverCoercesExplicitInvalidValuesIntoConsent() throws Exception {
        for (String invalid : new String[]{"\"true\"", "1", "null", "[]", "{}"}) {
            rejectsJson(json().replace("\"transitCardEnabled\":false", "\"transitCardEnabled\":" + invalid),
                    "runtime-boolean-transitCardEnabled");
        }
    }

    @Test public void optionalSmallWindowDefaultsOffPreservingCardThemeRulesAndOnlineBackup() throws Exception {
        String old = bundle(4, BUNDLE_RULE), latest = bundle(5, "");
        SystemRuntimeConfig.OnlineState online = new SystemRuntimeConfig.OnlineState(latest,
                hash(latest), old, hash(old), 5, hash(latest), true);
        SystemRuntimeConfig.State original = new SystemRuntimeConfig.State(SCHEMA, 0, 12345, true,
                true, true, false, true, false, true, false, true, new String[]{RULE, ONLINE},
                online, 0xff7e57c2, ThemePolicy.APPEARANCE_DARK, true, true);
        JsonObject legacy = JsonParser.parseString(new String(SystemRuntimeConfig.encode(original),
                StandardCharsets.UTF_8)).getAsJsonObject();
        legacy.remove("openLinksInSmallWindow");
        SystemRuntimeConfig.State restored = decode(legacy.toString());
        assertFalse(restored.openLinksInSmallWindow);
        assertTrue(restored.transitCardEnabled);
        assertEquals(original.transitSeedColor, restored.transitSeedColor);
        assertEquals(original.transitAppearance, restored.transitAppearance);
        assertArrayEquals(original.encodedRules(), restored.encodedRules());
        assertEquals(5, restored.online.highestRevision);
        assertEquals(hash(latest), restored.online.highestSha256);
        assertEquals(latest, restored.online.activeRaw);
        assertEquals(old, restored.online.backupRaw);
    }

    @Test public void smallWindowAndCardChoicesRoundTripAndHaveIndependentConfigIdentities() throws Exception {
        java.util.Set<String> fingerprints = new java.util.HashSet<>();
        for (boolean card : new boolean[]{false, true}) {
            SystemRuntimeConfig.State cardOnly = new SystemRuntimeConfig.State(SCHEMA, 0, 12345, true,
                    true, true, false, true, false, true, false, true, new String[]{RULE}, null,
                    0xff7e57c2, ThemePolicy.APPEARANCE_DARK, card);
            assertFalse("The card-only overload must not enable small windows", cardOnly.openLinksInSmallWindow);
            for (boolean smallWindow : new boolean[]{false, true}) {
                SystemRuntimeConfig.State original = new SystemRuntimeConfig.State(SCHEMA, 0, 12345, true,
                        true, true, false, true, false, true, false, true, new String[]{RULE}, null,
                        0xff7e57c2, ThemePolicy.APPEARANCE_DARK, card, smallWindow);
                SystemRuntimeConfig.State restored = decode(new String(SystemRuntimeConfig.encode(original),
                        StandardCharsets.UTF_8));
                assertEquals(card, restored.transitCardEnabled);
                assertEquals(smallWindow, restored.openLinksInSmallWindow);
                SystemRuntimeConfig.State replacement = restored.withOnline(restored.online, restored.encodedRules(), 99999);
                assertEquals(card, replacement.transitCardEnabled);
                assertEquals(smallWindow, replacement.openLinksInSmallWindow);
                assertEquals(SystemRuntimeConfig.fingerprint(original), SystemRuntimeConfig.fingerprint(replacement));
                fingerprints.add(SystemRuntimeConfig.fingerprint(original));
            }
        }
        assertEquals("Each pair of card/window choices needs its own acknowledgement", 4, fingerprints.size());
    }

    @Test public void optionalSmallWindowNeverCoercesExplicitInvalidValuesIntoConsent() throws Exception {
        for (String invalid : new String[]{"\"true\"", "1", "null", "[]", "{}"}) {
            rejectsJson(json().replace("\"openLinksInSmallWindow\":false", "\"openLinksInSmallWindow\":" + invalid),
                    "runtime-boolean-openLinksInSmallWindow");
        }
    }

    @Test public void selectedSmallWindowPackagesRoundTripAsAnImmutableSortedCopy() throws Exception {
        String[] packages = {"com.example.zebra", "com.example.reader", "com.example.reader"};
        SystemRuntimeConfig.State original = selected(packages, null);
        packages[0] = "com.example.changed";
        assertArrayEquals(new String[]{"com.example.reader", "com.example.zebra"},
                original.smallWindowPackages.toArray(new String[0]));
        try { original.smallWindowPackages.add("com.example.other"); fail("mutable selection"); }
        catch (UnsupportedOperationException expected) { }
        SystemRuntimeConfig.State restored = decode(new String(SystemRuntimeConfig.encode(original), StandardCharsets.UTF_8));
        assertEquals(original.smallWindowPackages, restored.smallWindowPackages);
        assertTrue(restored.openLinksInSmallWindow);
        assertTrue(restored.transitCardEnabled);
        assertArrayEquals(original.encodedRules(), restored.encodedRules());
    }

    @Test public void selectionFingerprintIgnoresOrderAndDuplicatesButTracksActualMembership() throws Exception {
        SystemRuntimeConfig.State one = selected(new String[]{"com.example.zebra", "com.example.reader"}, null);
        SystemRuntimeConfig.State same = selected(new String[]{"com.example.reader", "com.example.zebra", "com.example.reader"}, null);
        assertEquals(SystemRuntimeConfig.fingerprint(one), SystemRuntimeConfig.fingerprint(same));
        assertArrayEquals(SystemRuntimeConfig.encode(one), SystemRuntimeConfig.encode(same));
        assertFalse(SystemRuntimeConfig.fingerprint(one).equals(SystemRuntimeConfig.fingerprint(
                selected(new String[]{"com.example.reader"}, null))));
        assertEquals(SystemRuntimeConfig.fingerprint(selected(null, null)),
                SystemRuntimeConfig.fingerprint(selected(new String[0], null)));
    }

    @Test public void missingLegacySelectionStaysEmptyWithSwitchesThemeAndOnlineCachePreserved() throws Exception {
        String old = bundle(4, BUNDLE_RULE), latest = bundle(5, "");
        SystemRuntimeConfig.OnlineState online = new SystemRuntimeConfig.OnlineState(latest,
                hash(latest), old, hash(old), 5, hash(latest), true);
        SystemRuntimeConfig.State original = selected(new String[]{"com.example.reader"}, online);
        JsonObject legacy = JsonParser.parseString(new String(SystemRuntimeConfig.encode(original),
                StandardCharsets.UTF_8)).getAsJsonObject();
        legacy.remove("smallWindowPackages");
        SystemRuntimeConfig.State restored = decode(legacy.toString());
        assertTrue(restored.smallWindowPackages.isEmpty());
        assertTrue(restored.openLinksInSmallWindow);
        assertTrue(restored.transitCardEnabled);
        assertEquals(original.transitSeedColor, restored.transitSeedColor);
        assertEquals(original.transitAppearance, restored.transitAppearance);
        assertArrayEquals(original.encodedRules(), restored.encodedRules());
        assertEquals(latest, restored.online.activeRaw);
        assertEquals(old, restored.online.backupRaw);
        assertEquals(5, restored.online.highestRevision);
        assertEquals(hash(latest), restored.online.highestSha256);
    }

    @Test public void selectionRejectsWildcardsMalformedValuesAndOversizedListsWithoutTruncation() throws Exception {
        for (String invalid : new String[]{null, "", "*", "com.*", "com..app", "com.1app", "com.app$", " com.app", "com.app "}) {
            rejects(() -> selected(new String[]{invalid}, null), "runtime-small-window-packages");
        }
        String[] maximum = new String[SystemRuntimeConfig.MAX_SMALL_WINDOW_PACKAGES];
        for (int i = 0; i < maximum.length; i++) maximum[i] = "com.example.app" + i;
        assertEquals(512, decode(new String(SystemRuntimeConfig.encode(selected(maximum, null)),
                StandardCharsets.UTF_8)).smallWindowPackages.size());
        String[] oversized = new String[SystemRuntimeConfig.MAX_SMALL_WINDOW_PACKAGES + 1];
        Arrays.fill(oversized, "com.example.reader");
        rejects(() -> selected(oversized, null), "runtime-small-window-packages");
        for (String malformed : new String[]{"null", "true", "\"com.example.reader\"", "{}", "[null]", "[1]", "[true]", "[\"*\"]"}) {
            rejectsJson(json().replace("\"smallWindowPackages\":[]", "\"smallWindowPackages\":" + malformed),
                    "runtime-small-window-packages");
        }
        JsonObject encoded = JsonParser.parseString(json()).getAsJsonObject();
        com.google.gson.JsonArray tooMany = new com.google.gson.JsonArray();
        for (String packageName : oversized) tooMany.add(packageName);
        encoded.add("smallWindowPackages", tooMany);
        rejectsJson(encoded.toString(), "runtime-small-window-packages");
    }

    @Test public void missingWindowGeometryRetainsExistingSelectionRulesAndOnlineBackups() throws Exception {
        String old = bundle(4, BUNDLE_RULE), latest = bundle(5, "");
        SystemRuntimeConfig.OnlineState online = new SystemRuntimeConfig.OnlineState(latest,
                hash(latest), old, hash(old), 5, hash(latest), true);
        SystemRuntimeConfig.State original = selected(new String[]{"com.example.reader"}, online);
        JsonObject legacy = JsonParser.parseString(new String(SystemRuntimeConfig.encode(original),
                StandardCharsets.UTF_8)).getAsJsonObject();
        legacy.remove("smallWindowLayout");
        SystemRuntimeConfig.State restored = decode(legacy.toString());
        assertEquals(SmallWindowLayout.DEFAULT, restored.smallWindowLayout);
        assertEquals(original.smallWindowPackages, restored.smallWindowPackages);
        assertTrue(restored.openLinksInSmallWindow);
        assertTrue(restored.transitCardEnabled);
        assertArrayEquals(original.encodedRules(), restored.encodedRules());
        assertEquals(latest, restored.online.activeRaw);
        assertEquals(old, restored.online.backupRaw);
        assertEquals(5, restored.online.highestRevision);
        assertEquals(hash(latest), restored.online.highestSha256);
    }

    @Test public void eachWindowAdjustmentSurvivesPersistenceAndGetsItsOwnAcknowledgement() throws Exception {
        java.util.Set<String> fingerprints = new java.util.HashSet<>();
        for (String encoded : new String[]{"70,60,50,50", "71,60,50,50", "70,61,50,50", "70,60,51,50", "70,60,50,51"}) {
            SmallWindowLayout layout = SmallWindowLayout.parse(encoded);
            SystemRuntimeConfig.State selected = selected(new String[]{"com.example.reader"}, null);
            SystemRuntimeConfig.State original = withLayout(selected, layout);
            SystemRuntimeConfig.State restored = decode(new String(SystemRuntimeConfig.encode(original), StandardCharsets.UTF_8));
            assertEquals(layout, restored.smallWindowLayout);
            assertEquals(original.smallWindowPackages, restored.smallWindowPackages);
            assertArrayEquals(original.encodedRules(), restored.encodedRules());
            SystemRuntimeConfig.State updated = restored.withOnline(restored.online, restored.encodedRules(), 99999);
            assertEquals(layout, updated.smallWindowLayout);
            assertEquals(SystemRuntimeConfig.fingerprint(original), SystemRuntimeConfig.fingerprint(updated));
            fingerprints.add(SystemRuntimeConfig.fingerprint(original));
        }
        assertEquals(5, fingerprints.size());
    }

    @Test public void explicitWindowGeometryMustBeExactlyFourBoundedIntegers() throws Exception {
        for (String invalid : new String[]{"null", "true", "{}", "\"70,60,50,50\"", "[]", "[70,60,50]",
                "[70,60,50,50,0]", "[\"70\",60,50,50]", "[70,true,50,50]", "[70,60,null,50]",
                "[70,60,50,{}]", "[70.5,60,50,50]", "[2147483648,60,50,50]", "[29,60,50,50]",
                "[96,60,50,50]", "[70,24,50,50]", "[70,96,50,50]", "[70,60,-1,50]", "[70,60,50,101]"}) {
            rejectsJson(json().replace("\"smallWindowLayout\":[70,60,50,50]", "\"smallWindowLayout\":" + invalid),
                    "runtime-small-window-layout");
        }
        rejects(() -> withLayout(selected(null, null), null), "runtime-small-window-layout");
    }

    private static SystemRuntimeConfig.State withLayout(SystemRuntimeConfig.State state, SmallWindowLayout layout) throws IOException {
        return new SystemRuntimeConfig.State(state.providerSchemaVersion, state.userId, state.savedAtEpochMillis,
                state.autonomousEnabled, state.recoveryEnabled, state.autoOpenLinks, state.nativeLinkCapture,
                state.clipboardAutoOpen, state.paymentSdkBridge, state.onlyFrozen, state.browserFallback,
                state.onlineUpdatesEnabled, state.encodedRules(), state.online, state.transitSeedColor,
                state.transitAppearance, state.transitCardEnabled, state.openLinksInSmallWindow,
                state.smallWindowPackages.toArray(new String[0]), layout);
    }

    private static SystemRuntimeConfig.State selected(String[] packages, SystemRuntimeConfig.OnlineState online) throws IOException {
        return new SystemRuntimeConfig.State(SCHEMA, 0, 12345, true,
                true, true, false, true, false, true, false, true, new String[]{RULE}, online,
                0xff7e57c2, ThemePolicy.APPEARANCE_DARK, true, true, packages);
    }

    @Test public void explicitInvalidTransitThemeIsRejectedRatherThanTreatedAsLegacy() throws Exception {
        rejects(() -> themed(0x7f2979ff, ThemePolicy.APPEARANCE_SYSTEM), "runtime-transit-seed-color");
        rejects(() -> themed(0xff2979ff, "unknown"), "runtime-transit-appearance");
        rejects(() -> themed(0xff2979ff, null), "runtime-transit-appearance");
        String encoded = new String(SystemRuntimeConfig.encode(themed(0xff2979ff,
                ThemePolicy.APPEARANCE_SYSTEM)), StandardCharsets.UTF_8);
        rejectsJson(encoded.replace("\"transitAppearance\":\"system\"", "\"transitAppearance\":null"),
                "runtime-string-transitAppearance");
        rejectsJson(encoded.replace("\"transitSeedColor\":" + 0xff2979ff,
                "\"transitSeedColor\":4294967295"), "runtime-transit-seed-color");
        rejectsJson(encoded.replace("\"transitSeedColor\":" + 0xff2979ff,
                "\"transitSeedColor\":1.5"), "runtime-integer-transitSeedColor");
    }

    private static SystemRuntimeConfig.State themed(int seedColor, String appearance) throws IOException {
        return new SystemRuntimeConfig.State(SCHEMA, 0, 12345, true,
                true, true, false, true, false, true, false, true,
                new String[]{RULE}, null, seedColor, appearance);
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
