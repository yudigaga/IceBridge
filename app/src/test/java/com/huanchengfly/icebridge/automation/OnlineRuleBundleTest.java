package com.huanchengfly.icebridge.automation;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;

import static org.junit.Assert.*;

public class OnlineRuleBundleTest {
    private static final String RULE = "{\"sourcePackage\":\"com.tencent.mm\",\"host\":\"example.com\","
            + "\"package\":\"com.example.reader\",\"conversion\":\"fixed_deep_link\",\"note\":\"reviewed\"}";

    @Test public void repositoryPublishedSnapshotMatchesActualClientContract() throws Exception {
        File root = new File(".").getCanonicalFile();
        while (root != null && !(new File(root, "version.properties").isFile()
                && new File(root, "online-rules/manifest.json").isFile())) root = root.getParentFile();
        assertNotNull("repository root containing version.properties and online-rules is required", root);
        File source = new File(root, "online-rules").getCanonicalFile();
        OnlineRuleBundle.Manifest manifest;
        try (InputStream input = Files.newInputStream(new File(source, "manifest.json").toPath())) {
            manifest = OnlineRuleBundle.parseManifest(OnlineLinkRuleRepository.readUtf8Bounded(
                    input, OnlineRuleBundle.MAX_MANIFEST_BYTES));
        }
        File snapshot = new File(source, manifest.file).getCanonicalFile();
        assertTrue("manifest must refer to this rules directory", snapshot.toPath().startsWith(source.toPath()));
        String raw;
        try (InputStream input = Files.newInputStream(snapshot.toPath())) {
            raw = OnlineLinkRuleRepository.readUtf8Bounded(input, OnlineRuleBundle.MAX_BUNDLE_BYTES);
        }
        // Independently hash file bytes, then exercise the exact decode/hash/parser path used by HTTP.
        assertEquals(manifest.checksum, hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(snapshot.toPath()))));
        OnlineRuleBundle bundle = OnlineRuleBundle.parse(raw, manifest.checksum);
        assertEquals(manifest.revision, bundle.revision);
        assertEquals(manifest.checksum, bundle.checksum);
        OnlineRuleBundle.checkRevision(manifest, bundle.revision, bundle.checksum);
        assertTrue(OnlineRuleBundle.canReuse(manifest, cache(bundle, bundle, bundle.revision, bundle.checksum)));
        // No count is hard-coded: publishing an empty withdrawal is a valid repository change.
    }

    @Test public void utf8ByteChecksumPreservesNonAsciiAndLineEndings() throws Exception {
        String raw = payload(1, RULE.replace("reviewed", "规则\ud83c\udf0a")).replace("{", "{\r\n") + "\r\n";
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        String decoded = OnlineLinkRuleRepository.readUtf8Bounded(new ByteArrayInputStream(bytes), bytes.length);
        String hash = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(raw, decoded);
        assertEquals(hash, OnlineLinkRuleRepository.sha256(decoded));
        assertEquals("规则\ud83c\udf0a", OnlineRuleBundle.parse(decoded, hash).rules.get(0).note);
        assertFalse(OnlineLinkRuleRepository.checksumMatches(decoded.replace("\r\n", "\n"), hash));
    }

    @Test public void fullEntriesPreserveSourceConversionAndOrder() throws Exception {
        String wildcard = RULE.replace("com.tencent.mm", "*").replace("fixed_deep_link", "none");
        OnlineRuleBundle bundle = parse(payload(1, RULE + "," + wildcard));
        assertEquals(2, bundle.rules.size());
        assertEquals("com.tencent.mm", bundle.rules.get(0).sourcePackage);
        assertEquals("fixed_deep_link", bundle.rules.get(0).urlConversion);
        assertEquals("online", bundle.rules.get(0).source);
        assertEquals("*", bundle.rules.get(1).sourcePackage);
        assertEquals("none", bundle.rules.get(1).urlConversion);
        try { bundle.rules.clear(); fail("mutable rules"); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void newerEmptySnapshotWithdrawsAllRulesWithoutFallingBack() throws Exception {
        OnlineRuleBundle old = parse(payload(1, RULE));
        OnlineRuleBundle empty = parse(payload(2, ""));
        OnlineRuleBundle.Cache selected = cache(empty, old, empty.revision, empty.checksum);
        assertEquals(2, selected.bundle.revision);
        assertTrue(selected.bundle.rules.isEmpty());
        assertFalse(selected.usingBackup);
        assertFalse(selected.allowLegacy);
    }

    @Test public void validBackupRecoversCorruptActiveButDoesNotResetHighWaterMark() throws Exception {
        OnlineRuleBundle old = parse(payload(1, RULE));
        OnlineRuleBundle latest = parse(payload(2, ""));
        OnlineRuleBundle.Cache selected = OnlineRuleBundle.selectCache("broken", latest.checksum,
                old.raw, old.checksum, latest.revision, latest.checksum, true);
        assertTrue(selected.usingBackup);
        assertEquals(1, selected.bundle.revision);
        assertFalse(selected.allowLegacy);
        rejects(() -> OnlineRuleBundle.checkRevision(manifest(old), latest.revision, latest.checksum), "revision-rollback");
        assertFalse(OnlineRuleBundle.canReuse(manifest(latest), selected));
    }

    @Test public void migrationIsOneWayEvenWhenBothOwnedCachesAreCorrupt() {
        assertTrue(OnlineRuleBundle.selectCache("", "", "", "", 0, "", false).allowLegacy);
        assertFalse(OnlineRuleBundle.selectCache("broken", "bad", "broken", "bad", 1, "bad", true).allowLegacy);
        assertFalse(OnlineRuleBundle.selectCache("", "", "", "", 0, "", true).allowLegacy);
        assertFalse(OnlineRuleBundle.selectCache("broken", "", "", "", 0, "", false).allowLegacy);
        assertFalse(OnlineRuleBundle.selectCache("", "", "", "", 0, repeat('0', 64), false).allowLegacy);
    }

    @Test public void sameRevisionMustKeepExactBytesAndValidCacheCanSkipBundleDownload() throws Exception {
        OnlineRuleBundle accepted = parse(payload(5, RULE));
        OnlineRuleBundle changed = parse(payload(5, ""));
        OnlineRuleBundle.checkRevision(manifest(accepted), 5, accepted.checksum);
        rejects(() -> OnlineRuleBundle.checkRevision(manifest(changed), 5, accepted.checksum), "revision-content-changed");
        assertTrue(OnlineRuleBundle.canReuse(manifest(accepted), cache(accepted, accepted, 5, accepted.checksum)));
        assertFalse(OnlineRuleBundle.canReuse(manifest(changed), cache(accepted, accepted, 5, accepted.checksum)));
    }

    @Test public void cacheRequiresChecksumsAndConsistentHighWaterState() throws Exception {
        OnlineRuleBundle bundle = parse(payload(1, RULE));
        assertNull(OnlineRuleBundle.selectCache(bundle.raw, "", "", "", 1, bundle.checksum, true).bundle);
        assertNull(OnlineRuleBundle.selectCache(bundle.raw, bundle.checksum, "", "", 0, "", true).bundle);
        assertNull(OnlineRuleBundle.selectCache(bundle.raw, bundle.checksum, "", "", 1, repeat('0', 64), true).bundle);
        rejects(() -> OnlineRuleBundle.checkRevision(manifest(bundle), 1, ""), "revision-state-invalid");
    }

    @Test public void schemaRejectsUnsupportedConversionIncompleteRulesAndDuplicateKeys() throws Exception {
        rejects(() -> parse(payload(1, RULE.replace("fixed_deep_link", "javascript"))), "rule-conversion");
        rejects(() -> parse(payload(1, RULE.replace(",\"note\":\"reviewed\"", ""))), "field-string-note");
        rejects(() -> parse(payload(1, RULE.replace("\"note\":\"reviewed\"", "\"note\":false"))), "field-string-note");
        rejects(() -> parse(payload(1, RULE.replace("\"note\":\"reviewed\"", "\"note\":\"\",\"note\":\"duplicate\""))), "json-duplicate-key");
        rejects(() -> parse(payload(1, RULE.replace("\"note\":\"reviewed\"", "\"note\":\"\",\"script\":\"do()\""))), "unknown-field-script");
        rejects(() -> parse(payload(1, RULE + "," + RULE)), "rule-duplicate");
    }

    @Test public void schemaRejectsRegexUrlsBadPackageNamesAndInvalidRevision() throws Exception {
        rejects(() -> parse(payload(1, RULE.replace("example.com", "*.example.com"))), "rule-host");
        rejects(() -> parse(payload(1, RULE.replace("example.com", "https://example.com"))), "rule-host");
        rejects(() -> parse(payload(1, RULE.replace("com.tencent.mm", "bad package"))), "rule-source");
        rejects(() -> parse(payload(1, RULE.replace("com.example.reader", "bad"))), "rule-package");
        rejects(() -> parse(payload(0, RULE)), "field-positive-revision");
        rejects(() -> parse(payload(1, RULE).replace("\"revision\":1", "\"revision\":1.5")), "field-integer-revision");
    }

    @Test public void strictJsonRejectsCommentsSingleQuotesTrailingDataAndMissingChecksum() throws Exception {
        rejectsAny(() -> parse(payload(1, RULE).replace("\"format\"", "/* comment */\"format\"")));
        rejectsAny(() -> parse(payload(1, RULE).replace("\"version\"", "'version'")));
        rejectsAny(() -> parse(payload(1, RULE) + " {}"));
        rejects(() -> OnlineRuleBundle.parse(payload(1, RULE), repeat('0', 64)), "bundle-checksum-mismatch");
        rejects(() -> OnlineRuleBundle.parse(payload(1, RULE), ""), "bundle-checksum");
    }

    @Test public void limitsRejectEntireOversizedSnapshotInsteadOfTruncating() throws Exception {
        StringBuilder hosts = new StringBuilder();
        for (int i = 0; i < 97; i++) {
            if (i > 0) hosts.append(',');
            hosts.append(RULE.replace("example.com", "h" + i + ".example.com"));
        }
        rejects(() -> parse(payload(1, hosts.toString())), "bundle-host-limit");
        StringBuilder rules = new StringBuilder();
        for (int i = 0; i < 769; i++) {
            if (i > 0) rules.append(',');
            rules.append(RULE.replace("com.tencent.mm", "com.source.p" + i));
        }
        rejects(() -> parse(payload(1, rules.toString())), "bundle-rule-limit");
        rejects(() -> parse(payload(1, RULE.replace("reviewed", repeat('n', 513)))), "rule-note");
    }

    @Test public void manifestOnlyResolvesRelativePathsInsideOwningRepository() throws Exception {
        OnlineRuleBundle bundle = parse(payload(1, RULE));
        String good = manifestJson(bundle, "revisions/rules-000001.json");
        OnlineRuleBundle.Manifest manifest = OnlineRuleBundle.parseManifest(good);
        assertEquals("https://raw.githubusercontent.com/yudigaga/IceBridge/rules/revisions/rules-000001.json",
                OnlineLinkRuleRepository.bundleUrl(HttpUrl.parse("https://raw.githubusercontent.com/yudigaga/IceBridge/rules/manifest.json"), manifest.file).toString());
        String[] bad = {"../other.json", "/root.json", "//evil.test/x.json", "https://evil.test/x.json",
                "revisions/%2e%2e/x.json", "revisions\\\\x.json", "rules.json?redirect=1", "rules.json#fragment"};
        for (String path : bad) rejectsAny(() -> OnlineRuleBundle.parseManifest(manifestJson(bundle, path)));
    }

    @Test public void malformedUtf8IsRejectedBeforeChecksumOrJsonParsing() throws Exception {
        rejectsAny(() -> OnlineLinkRuleRepository.readUtf8Bounded(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28}), 2));
    }

    @Test public void retryBackoffIsBoundedAndIncreasesAfterFailures() {
        assertEquals(TimeUnit.MINUTES.toMillis(15), OnlineLinkRuleRepository.retryDelayMillis(1));
        assertEquals(TimeUnit.MINUTES.toMillis(30), OnlineLinkRuleRepository.retryDelayMillis(2));
        assertEquals(TimeUnit.HOURS.toMillis(6), OnlineLinkRuleRepository.retryDelayMillis(100));
    }

    @Test public void migrationAndBackupRepairIgnoreThirdPartyRefreshTimestampButRespectBackoff() throws Exception {
        long now = TimeUnit.DAYS.toMillis(100);
        OnlineRuleBundle.Cache legacy = OnlineRuleBundle.selectCache("", "", "", "", 0, "", false);
        assertTrue(OnlineLinkRuleRepository.shouldRefresh(now, now, 0, legacy));
        assertFalse(OnlineLinkRuleRepository.shouldRefresh(now, now, now + 1000, legacy));
        OnlineRuleBundle bundle = parse(payload(1, RULE));
        OnlineRuleBundle.Cache active = cache(bundle, bundle, 1, bundle.checksum);
        assertFalse(OnlineLinkRuleRepository.shouldRefresh(now, now, 0, active));
        assertTrue(OnlineLinkRuleRepository.shouldRefresh(now, now - TimeUnit.DAYS.toMillis(7), 0, active));
        OnlineRuleBundle.Cache backup = OnlineRuleBundle.selectCache("broken", bundle.checksum,
                bundle.raw, bundle.checksum, 1, bundle.checksum, true);
        assertTrue(OnlineLinkRuleRepository.shouldRefresh(now, now, 0, backup));
    }

    private static String payload(long revision, String rules) {
        return "{\"format\":\"icebridge-online-rules\",\"version\":1,\"revision\":" + revision + ",\"rules\":[" + rules + "]}";
    }
    private static OnlineRuleBundle parse(String raw) throws IOException { return OnlineRuleBundle.parse(raw, OnlineLinkRuleRepository.sha256(raw)); }
    private static OnlineRuleBundle.Manifest manifest(OnlineRuleBundle bundle) { return new OnlineRuleBundle.Manifest(bundle.revision, "rules.json", bundle.checksum); }
    private static String manifestJson(OnlineRuleBundle bundle, String file) {
        return "{\"format\":\"icebridge-rule-manifest\",\"version\":1,\"revision\":" + bundle.revision
                + ",\"file\":\"" + file + "\",\"sha256\":\"" + bundle.checksum + "\"}";
    }
    private static OnlineRuleBundle.Cache cache(OnlineRuleBundle active, OnlineRuleBundle backup, long highest, String hash) {
        return OnlineRuleBundle.selectCache(active.raw, active.checksum, backup.raw, backup.checksum, highest, hash, true);
    }
    private static String repeat(char value, int count) { char[] values = new char[count]; java.util.Arrays.fill(values, value); return new String(values); }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String reason) throws Exception {
        try { action.run(); fail("accepted invalid data: " + reason); }
        catch (IOException expected) { assertEquals(reason, expected.getMessage()); }
    }
    private static void rejectsAny(Checked action) throws Exception {
        try { action.run(); fail("accepted invalid data"); } catch (IOException expected) { }
    }
}
