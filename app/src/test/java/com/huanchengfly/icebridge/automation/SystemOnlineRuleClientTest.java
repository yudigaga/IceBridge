package com.huanchengfly.icebridge.automation;

import com.huanchengfly.icebridge.ui.theme.ThemePolicy;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SystemOnlineRuleClientTest {
    @Test public void providerRemovalOfManualDuplicateRestoresFullRetainedOnlineRow() throws Exception {
        OnlineRuleBundle full = bundle(3, "example.com");
        SystemRuntimeConfig.State retained = state(true, rules(manual("example.com")), cache(full));
        SystemRuntimeConfig.State incoming = state(true, Collections.emptyList(), metadata(full));
        SystemRuntimeConfig.State merged = SystemOnlineRuleClient.mergeProvider(incoming, retained);
        assertEquals(1, merged.scopedRules.size());
        assertEquals(LinkRuleEntry.SOURCE_ONLINE, merged.scopedRules.get(0).source);
        assertEquals(full.raw, merged.online.activeRaw);
    }

    @Test public void equalRevisionMetadataOnlyUsesFreshProviderOnlineRows() throws Exception {
        OnlineRuleBundle full = bundle(3, "example.com");
        SystemRuntimeConfig.State retained = state(true, rules(manual("example.com")), metadata(full));
        SystemRuntimeConfig.State incoming = state(true, full.rules, metadata(full));
        SystemRuntimeConfig.State merged = SystemOnlineRuleClient.mergeProvider(incoming, retained);
        assertEquals(1, merged.scopedRules.size());
        assertEquals(LinkRuleEntry.SOURCE_ONLINE, merged.scopedRules.get(0).source);
    }

    @Test public void providerMergePrefersNewerSystemRawOverStaleAppOnlineRows() throws Exception {
        OnlineRuleBundle old = bundle(3, "old.example.com");
        OnlineRuleBundle latest = bundle(4, "new.example.com");
        SystemRuntimeConfig.State retained = state(true, latest.rules, cache(latest));
        SystemRuntimeConfig.State incoming = state(true, rules(manual("manual.example.com"),
                online("old.example.com")), metadata(old));
        SystemRuntimeConfig.State merged = SystemOnlineRuleClient.mergeProvider(incoming, retained);
        assertEquals(4, merged.online.highestRevision);
        assertEquals("manual.example.com", merged.scopedRules.get(0).host);
        assertEquals("new.example.com", merged.scopedRules.get(1).host);
    }

    @Test public void providerThemeChangeSurvivesMergeWithNewerSystemOnlineRules() throws Exception {
        OnlineRuleBundle old = bundle(3, "old.example.com"), latest = bundle(4, "new.example.com");
        SystemRuntimeConfig.State retained = themedState(state(true, latest.rules, cache(latest)),
                0xff2979ff, ThemePolicy.APPEARANCE_LIGHT);
        SystemRuntimeConfig.State incoming = themedState(state(true,
                rules(manual("manual.example.com"), online("old.example.com")), metadata(old)),
                0xff7e57c2, ThemePolicy.APPEARANCE_DARK);
        SystemRuntimeConfig.State merged = SystemOnlineRuleClient.mergeProvider(incoming, retained);
        assertEquals(4, merged.online.highestRevision);
        assertEquals("new.example.com", merged.scopedRules.get(1).host);
        assertEquals(incoming.transitSeedColor, merged.transitSeedColor);
        assertEquals(incoming.transitAppearance, merged.transitAppearance);
    }

    @Test public void onlineDownloadRebasesOntoLatestTransitTheme() throws Exception {
        OnlineRuleBundle old = bundle(3, "old.example.com"), latest = bundle(4, "new.example.com");
        SystemRuntimeConfig.State before = state(true, old.rules, cache(old));
        SystemOnlineRuleClient.Result downloaded = new SystemOnlineRuleClient.Result(before, latest);
        SystemRuntimeConfig.State edited = themedState(before, 0xff00796b, ThemePolicy.APPEARANCE_DARK);
        SystemRuntimeConfig.State after = downloaded.applyTo(edited, 200);
        assertEquals(4, after.online.highestRevision);
        assertEquals(edited.transitSeedColor, after.transitSeedColor);
        assertEquals(edited.transitAppearance, after.transitAppearance);
    }

    @Test public void providerCardSwitchWinsBothDirectionsWhileRetainingNewerOnlineRules() throws Exception {
        OnlineRuleBundle old = bundle(3, "old.example.com"), latest = bundle(4, "new.example.com");
        for (boolean enabled : new boolean[]{false, true}) {
            SystemRuntimeConfig.State retained = cardState(state(true, latest.rules, cache(latest)), !enabled);
            SystemRuntimeConfig.State incoming = cardState(state(true,
                    rules(manual("manual.example.com"), online("old.example.com")), metadata(old)), enabled);
            SystemRuntimeConfig.State merged = SystemOnlineRuleClient.mergeProvider(incoming, retained);
            assertEquals(enabled, merged.transitCardEnabled);
            assertEquals(4, merged.online.highestRevision);
            assertEquals(latest.raw, merged.online.activeRaw);
            assertEquals("manual.example.com", merged.scopedRules.get(0).host);
            assertEquals("new.example.com", merged.scopedRules.get(1).host);
        }
    }

    @Test public void onlineDownloadDoesNotOverwriteCardSwitchEditedDuringHttp() throws Exception {
        OnlineRuleBundle old = bundle(3, "old.example.com"), latest = bundle(4, "new.example.com");
        for (boolean enabled : new boolean[]{false, true}) {
            SystemRuntimeConfig.State before = cardState(state(true, old.rules, cache(old)), !enabled);
            SystemOnlineRuleClient.Result downloaded = new SystemOnlineRuleClient.Result(before, latest);
            SystemRuntimeConfig.State edited = cardState(before, enabled);
            SystemRuntimeConfig.State after = downloaded.applyTo(edited, 200);
            assertEquals(enabled, after.transitCardEnabled);
            assertEquals(4, after.online.highestRevision);
            assertEquals(latest.raw, after.online.activeRaw);
            assertEquals(old.raw, after.online.backupRaw);
        }
    }

    @Test public void providerSmallWindowChoiceWinsBothDirectionsWhileRetainingNewerOnlineRules() throws Exception {
        OnlineRuleBundle old = bundle(3, "old.example.com"), latest = bundle(4, "new.example.com");
        for (boolean enabled : new boolean[]{false, true}) {
            SystemRuntimeConfig.State retained = smallWindowState(cardState(
                    state(true, latest.rules, cache(latest)), true), !enabled);
            SystemRuntimeConfig.State incoming = smallWindowState(cardState(state(true,
                    rules(manual("manual.example.com"), online("old.example.com")), metadata(old)), true), enabled);
            SystemRuntimeConfig.State merged = SystemOnlineRuleClient.mergeProvider(incoming, retained);
            assertEquals(enabled, merged.openLinksInSmallWindow);
            assertTrue(merged.transitCardEnabled);
            assertEquals(4, merged.online.highestRevision);
            assertEquals(latest.raw, merged.online.activeRaw);
            assertEquals("manual.example.com", merged.scopedRules.get(0).host);
            assertEquals("new.example.com", merged.scopedRules.get(1).host);
        }
    }

    @Test public void onlineDownloadPreservesSmallWindowChoiceEditedDuringHttpAndOtherUiSettings() throws Exception {
        OnlineRuleBundle old = bundle(3, "old.example.com"), latest = bundle(4, "new.example.com");
        for (boolean enabled : new boolean[]{false, true}) {
            SystemRuntimeConfig.State before = smallWindowState(cardState(
                    state(true, old.rules, cache(old)), true), !enabled);
            SystemOnlineRuleClient.Result downloaded = new SystemOnlineRuleClient.Result(before, latest);
            SystemRuntimeConfig.State edited = themedState(smallWindowState(before, enabled),
                    0xff00796b, ThemePolicy.APPEARANCE_DARK);
            SystemRuntimeConfig.State after = downloaded.applyTo(edited, 200);
            assertEquals(enabled, after.openLinksInSmallWindow);
            assertTrue(after.transitCardEnabled);
            assertEquals(edited.transitSeedColor, after.transitSeedColor);
            assertEquals(edited.transitAppearance, after.transitAppearance);
            assertEquals(4, after.online.highestRevision);
            assertEquals(latest.raw, after.online.activeRaw);
            assertEquals(old.raw, after.online.backupRaw);
        }
    }

    @Test public void providerSelectedPackagesReplaceRetainedSelectionWithoutLosingNewerOnlineRules() throws Exception {
        OnlineRuleBundle old = bundle(3, "old.example.com"), latest = bundle(4, "new.example.com");
        for (String[] selection : new String[][]{{"com.example.updated"}, new String[0]}) {
            SystemRuntimeConfig.State retained = selectedState(state(true, latest.rules, cache(latest)),
                    new String[]{"com.example.old"});
            SystemRuntimeConfig.State incoming = selectedState(state(true,
                    rules(manual("manual.example.com"), online("old.example.com")), metadata(old)), selection);
            SystemRuntimeConfig.State merged = SystemOnlineRuleClient.mergeProvider(incoming, retained);
            assertEquals(incoming.smallWindowPackages, merged.smallWindowPackages);
            assertEquals(4, merged.online.highestRevision);
            assertEquals(latest.raw, merged.online.activeRaw);
            assertEquals("manual.example.com", merged.scopedRules.get(0).host);
            assertEquals("new.example.com", merged.scopedRules.get(1).host);
        }
    }

    @Test public void onlineDownloadUsesLatestSelectedPackagesIncludingExplicitEmptySelection() throws Exception {
        OnlineRuleBundle old = bundle(3, "old.example.com"), latest = bundle(4, "new.example.com");
        for (String[] selection : new String[][]{{"com.example.updated"}, new String[0]}) {
            SystemRuntimeConfig.State before = selectedState(smallWindowState(cardState(
                    state(true, old.rules, cache(old)), true), true), new String[]{"com.example.old"});
            SystemOnlineRuleClient.Result downloaded = new SystemOnlineRuleClient.Result(before, latest);
            SystemRuntimeConfig.State edited = themedState(selectedState(before, selection),
                    0xff00796b, ThemePolicy.APPEARANCE_DARK);
            SystemRuntimeConfig.State after = downloaded.applyTo(edited, 200);
            assertEquals(edited.smallWindowPackages, after.smallWindowPackages);
            assertTrue(after.openLinksInSmallWindow);
            assertTrue(after.transitCardEnabled);
            assertEquals(edited.transitSeedColor, after.transitSeedColor);
            assertEquals(edited.transitAppearance, after.transitAppearance);
            assertEquals(latest.raw, after.online.activeRaw);
            assertEquals(old.raw, after.online.backupRaw);
        }
    }

    private static SystemRuntimeConfig.State selectedState(SystemRuntimeConfig.State state,
                                                           String[] selected) throws IOException {
        return new SystemRuntimeConfig.State(state.providerSchemaVersion, state.userId, state.savedAtEpochMillis,
                state.autonomousEnabled, state.recoveryEnabled, state.autoOpenLinks, state.nativeLinkCapture,
                state.clipboardAutoOpen, state.paymentSdkBridge, state.onlyFrozen, state.browserFallback,
                state.onlineUpdatesEnabled, state.encodedRules(), state.online, state.transitSeedColor,
                state.transitAppearance, state.transitCardEnabled, state.openLinksInSmallWindow, selected);
    }

    private static SystemRuntimeConfig.State smallWindowState(SystemRuntimeConfig.State state,
                                                              boolean enabled) throws IOException {
        return new SystemRuntimeConfig.State(state.providerSchemaVersion, state.userId, state.savedAtEpochMillis,
                state.autonomousEnabled, state.recoveryEnabled, state.autoOpenLinks, state.nativeLinkCapture,
                state.clipboardAutoOpen, state.paymentSdkBridge, state.onlyFrozen, state.browserFallback,
                state.onlineUpdatesEnabled, state.encodedRules(), state.online, state.transitSeedColor,
                state.transitAppearance, state.transitCardEnabled, enabled, state.smallWindowPackages.toArray(new String[0]));
    }

    private static SystemRuntimeConfig.State cardState(SystemRuntimeConfig.State state,
                                                       boolean enabled) throws IOException {
        return new SystemRuntimeConfig.State(state.providerSchemaVersion, state.userId, state.savedAtEpochMillis,
                state.autonomousEnabled, state.recoveryEnabled, state.autoOpenLinks, state.nativeLinkCapture,
                state.clipboardAutoOpen, state.paymentSdkBridge, state.onlyFrozen, state.browserFallback,
                state.onlineUpdatesEnabled, state.encodedRules(), state.online, state.transitSeedColor,
                state.transitAppearance, enabled, state.openLinksInSmallWindow, state.smallWindowPackages.toArray(new String[0]));
    }

    private static SystemRuntimeConfig.State themedState(SystemRuntimeConfig.State state,
                                                         int seedColor, String appearance) throws IOException {
        return new SystemRuntimeConfig.State(state.providerSchemaVersion, state.userId, state.savedAtEpochMillis,
                state.autonomousEnabled, state.recoveryEnabled, state.autoOpenLinks, state.nativeLinkCapture,
                state.clipboardAutoOpen, state.paymentSdkBridge, state.onlyFrozen, state.browserFallback,
                state.onlineUpdatesEnabled, state.encodedRules(), state.online, seedColor, appearance,
                state.transitCardEnabled, state.openLinksInSmallWindow, state.smallWindowPackages.toArray(new String[0]));
    }

    @Test public void providerCurrentRevisionDoesNotGetReplacedByRetainedOlderBackup() throws Exception {
        OnlineRuleBundle old = bundle(3, "old.example.com");
        OnlineRuleBundle latest = bundle(4, "new.example.com");
        SystemRuntimeConfig.OnlineState damaged = new SystemRuntimeConfig.OnlineState("", "",
                old.raw, old.checksum, latest.revision, latest.checksum, true);
        SystemRuntimeConfig.State retained = state(true, old.rules, damaged);
        SystemRuntimeConfig.State incoming = state(true, latest.rules, metadata(latest));
        SystemRuntimeConfig.State merged = SystemOnlineRuleClient.mergeProvider(incoming, retained);
        assertEquals("new.example.com", merged.scopedRules.get(0).host);
        assertEquals(4, merged.online.highestRevision);
    }

    @Test public void providerMergeOverflowIsCheckedFailureAndRetainsOldSnapshot() throws Exception {
        List<LinkRuleEntry> manual = new ArrayList<>();
        for (int i = 0; i < 96; i++) manual.add(manual("h" + i + ".example.com"));
        OnlineRuleBundle full = bundle(2, "new.example.com");
        SystemRuntimeConfig.State retained = state(true, full.rules, cache(full));
        SystemRuntimeConfig.State incoming = state(true, manual, SystemRuntimeConfig.OnlineState.empty());
        rejects(() -> SystemOnlineRuleClient.mergeProvider(incoming, retained), "online-merged-rules");
        assertEquals(full.raw, retained.online.activeRaw);
        assertEquals(1, retained.scopedRules.size());
    }

    @Test public void providerMergeDeduplicatesManualAndOnlineWithoutInflatingLimit() throws Exception {
        OnlineRuleBundle full = bundle(2, "example.com");
        List<LinkRuleEntry> manual = new ArrayList<>();
        manual.add(manual("example.com"));
        for (int i = 1; i < 768; i++) manual.add(new LinkRuleEntry("com.source.p" + i,
                "example.com", "com.example.reader", LinkRuleEntry.SOURCE_MANUAL,
                LinkRuleEntry.CONVERSION_NONE, ""));
        SystemRuntimeConfig.State retained = state(true, full.rules, cache(full));
        SystemRuntimeConfig.State incoming = state(true, manual, metadata(full));
        SystemRuntimeConfig.State merged = SystemOnlineRuleClient.mergeProvider(incoming, retained);
        assertEquals(768, merged.scopedRules.size());
        assertEquals(LinkRuleEntry.SOURCE_MANUAL, merged.scopedRules.get(0).source);
    }

    @Test public void metadataOnlyBaselineDownloadsFullSnapshotWithoutLosingManualRules() throws Exception {
        OnlineRuleBundle online = bundle(4, "new.example.com");
        SystemRuntimeConfig.OnlineState metadata = metadata(online);
        SystemRuntimeConfig.State before = state(true, rules(manual("manual.example.com"),
                online("old.example.com")), metadata);
        SystemRuntimeConfig.State after = SystemOnlineRuleClient.applyDownloaded(before, online, 200);
        assertEquals(2, after.scopedRules.size());
        assertEquals(LinkRuleEntry.SOURCE_MANUAL, after.scopedRules.get(0).source);
        assertEquals("new.example.com", after.scopedRules.get(1).host);
        assertEquals(online.raw, after.online.activeRaw);
        assertEquals(4, after.online.highestRevision);
        assertTrue(after.online.migrated);
    }

    @Test public void explicitEmptyRevisionWithdrawsAllOnlineRulesAndKeepsManual() throws Exception {
        OnlineRuleBundle previous = bundle(4, "old.example.com");
        SystemRuntimeConfig.State before = state(true, rules(manual("manual.example.com"),
                online("old.example.com")), cache(previous));
        OnlineRuleBundle empty = bundle(5);
        SystemRuntimeConfig.State after = SystemOnlineRuleClient.applyDownloaded(before, empty, 200);
        assertEquals(1, after.scopedRules.size());
        assertEquals("manual.example.com", after.scopedRules.get(0).host);
        assertEquals(empty.raw, after.online.activeRaw);
        assertEquals(previous.raw, after.online.backupRaw);
        assertEquals(5, after.online.highestRevision);
    }

    @Test public void metadataPreventsRollbackAndMutationAtSameRevision() throws Exception {
        OnlineRuleBundle previous = bundle(8, "current.example.com");
        SystemRuntimeConfig.State before = state(true, Collections.emptyList(), metadata(previous));
        rejects(() -> SystemOnlineRuleClient.applyDownloaded(before, bundle(7), 200), "revision-rollback");
        rejects(() -> SystemOnlineRuleClient.applyDownloaded(before, bundle(8), 200), "revision-content-changed");
        assertEquals(8, before.online.highestRevision);
        assertTrue(before.online.activeRaw.isEmpty());
    }

    @Test public void recoveredBackupCannotResetHighWaterMark() throws Exception {
        OnlineRuleBundle previous = bundle(7, "old.example.com");
        OnlineRuleBundle latest = bundle(8, "new.example.com");
        SystemRuntimeConfig.OnlineState recovered = new SystemRuntimeConfig.OnlineState("", "",
                previous.raw, previous.checksum, latest.revision, latest.checksum, true);
        SystemRuntimeConfig.State before = state(true, rules(online("old.example.com")), recovered);
        rejects(() -> SystemOnlineRuleClient.applyDownloaded(before, previous, 200), "revision-rollback");
        SystemRuntimeConfig.State repaired = SystemOnlineRuleClient.applyDownloaded(before, latest, 200);
        assertEquals(latest.raw, repaired.online.activeRaw);
        assertEquals(previous.raw, repaired.online.backupRaw);
        assertEquals(8, repaired.online.highestRevision);
    }

    @Test public void unchangedSnapshotKeepsPreviousBackupAndAvoidsWritingStateAgain() throws Exception {
        OnlineRuleBundle older = bundle(3, "older.example.com");
        OnlineRuleBundle current = bundle(4, "current.example.com");
        SystemRuntimeConfig.OnlineState cache = new SystemRuntimeConfig.OnlineState(current.raw,
                current.checksum, older.raw, older.checksum, 4, current.checksum, true);
        SystemRuntimeConfig.State before = state(true, current.rules, cache);
        SystemRuntimeConfig.State after = SystemOnlineRuleClient.applyDownloaded(before, current, 200);
        assertSame(before, after);
        assertEquals(older.raw, after.online.backupRaw);
    }

    @Test public void resultRebasesManualEditsAndRejectsNewerAcceptanceDuringHttp() throws Exception {
        OnlineRuleBundle first = bundle(1, "old.example.com");
        OnlineRuleBundle second = bundle(2, "new.example.com");
        SystemRuntimeConfig.State starting = state(true, first.rules, cache(first));
        SystemOnlineRuleClient.Result result = new SystemOnlineRuleClient.Result(starting, second);
        assertTrue(result.changed);
        SystemRuntimeConfig.State edited = state(true, rules(manual("edited.example.com"),
                online("old.example.com")), cache(first));
        SystemRuntimeConfig.State applied = result.applyTo(edited, 200);
        assertEquals("edited.example.com", applied.scopedRules.get(0).host);
        assertEquals("new.example.com", applied.scopedRules.get(1).host);
        OnlineRuleBundle third = bundle(3, "latest.example.com");
        SystemRuntimeConfig.State advanced = state(true, third.rules, cache(third));
        rejects(() -> result.applyTo(advanced, 200), "revision-rollback");
        SystemRuntimeConfig.State switchedOff = state(false, first.rules, cache(first));
        assertSame(switchedOff, result.applyTo(switchedOff, 200));
    }

    @Test public void aggregateHostOverflowRejectsWholeUpdateInsteadOfDroppingManualOrOnline() throws Exception {
        List<LinkRuleEntry> manual = new ArrayList<>();
        for (int i = 0; i < 96; i++) manual.add(manual("h" + i + ".example.com"));
        SystemRuntimeConfig.State before = state(true, manual, SystemRuntimeConfig.OnlineState.empty());
        rejects(() -> SystemOnlineRuleClient.applyDownloaded(before, bundle(1, "new.example.com"), 200),
                "online-merged-rules");
        assertEquals(96, before.scopedRules.size());
        assertEquals(0, before.online.highestRevision);
    }

    @Test public void aggregateRuleOverflowRejectsWholeUpdate() throws Exception {
        List<LinkRuleEntry> manual = new ArrayList<>();
        for (int i = 0; i < 768; i++) manual.add(new LinkRuleEntry("com.source.p" + i,
                "example.com", "com.example.reader", LinkRuleEntry.SOURCE_MANUAL,
                LinkRuleEntry.CONVERSION_NONE, ""));
        SystemRuntimeConfig.State before = state(true, manual, SystemRuntimeConfig.OnlineState.empty());
        rejects(() -> SystemOnlineRuleClient.applyDownloaded(before, bundle(1, "example.com"), 200),
                "online-merged-rules");
        assertEquals(768, before.scopedRules.size());
        assertEquals(0, before.online.highestRevision);
    }

    @Test public void sourceAndConversionSurviveMergeAndExactManualDuplicateWins() throws Exception {
        LinkRuleEntry manual = new LinkRuleEntry("com.tencent.mm", "example.com", "com.example.reader",
                LinkRuleEntry.SOURCE_MANUAL, LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK, "");
        LinkRuleEntry duplicate = new LinkRuleEntry("com.tencent.mm", "example.com", "com.example.reader",
                LinkRuleEntry.SOURCE_ONLINE, LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK, "");
        LinkRuleEntry otherSource = new LinkRuleEntry("com.tencent.mobileqq", "example.com", "com.example.reader",
                LinkRuleEntry.SOURCE_ONLINE, LinkRuleEntry.CONVERSION_NONE, "");
        List<LinkRuleEntry> merged = ScopedRuleResolver.decode(SystemOnlineRuleClient.mergeRules(
                rules(manual), rules(duplicate, otherSource)));
        assertEquals(2, merged.size());
        assertEquals(LinkRuleEntry.SOURCE_MANUAL, merged.get(0).source);
        assertTrue(merged.get(0).hasFixedUrlConversion());
        assertEquals("com.tencent.mobileqq", merged.get(1).sourcePackage);
    }

    private static SystemRuntimeConfig.State state(boolean onlineEnabled, List<LinkRuleEntry> rules,
                                                    SystemRuntimeConfig.OnlineState online) throws IOException {
        return new SystemRuntimeConfig.State(4, 0, 100, true, true, true, true,
                true, true, true, true, onlineEnabled, ScopedRuleResolver.encode(rules), online);
    }

    private static SystemRuntimeConfig.OnlineState metadata(OnlineRuleBundle bundle) throws IOException {
        return new SystemRuntimeConfig.OnlineState("", "", "", "", bundle.revision, bundle.checksum, true);
    }

    private static SystemRuntimeConfig.OnlineState cache(OnlineRuleBundle bundle) throws IOException {
        return new SystemRuntimeConfig.OnlineState(bundle.raw, bundle.checksum, bundle.raw,
                bundle.checksum, bundle.revision, bundle.checksum, true);
    }

    private static OnlineRuleBundle bundle(long revision, String... hosts) throws IOException {
        StringBuilder entries = new StringBuilder();
        for (String host : hosts) {
            if (entries.length() > 0) entries.append(',');
            entries.append("{\"sourcePackage\":\"*\",\"host\":\"").append(host)
                    .append("\",\"package\":\"com.example.reader\",\"conversion\":\"none\",\"note\":\"\"}");
        }
        String raw = "{\"format\":\"icebridge-online-rules\",\"version\":1,\"revision\":"
                + revision + ",\"rules\":[" + entries + "]}";
        return OnlineRuleBundle.parse(raw, OnlineRuleBundle.sha256(raw));
    }

    private static List<LinkRuleEntry> rules(LinkRuleEntry... rules) { return Arrays.asList(rules); }
    private static LinkRuleEntry manual(String host) {
        return new LinkRuleEntry(host, "com.example.reader", LinkRuleEntry.SOURCE_MANUAL, "none", "");
    }
    private static LinkRuleEntry online(String host) {
        return new LinkRuleEntry(host, "com.example.reader", LinkRuleEntry.SOURCE_ONLINE, "none", "");
    }
    private interface Checked { void run() throws IOException; }
    private static void rejects(Checked action, String message) throws Exception {
        try { action.run(); fail("accepted " + message); }
        catch (IOException expected) { assertEquals(message, expected.getMessage()); }
    }
}
