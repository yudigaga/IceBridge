package com.huanchengfly.icebridge.automation;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OnlineLinkRuleRepositoryTest {
    private static final String RAW_RULES =
            "[{\"host\":\"www.example.com\",\"packages\":[\"com.example.reader\"]}]";

    @Test
    public void checksumRoundTripAcceptsExactPayload() {
        String checksum = OnlineLinkRuleRepository.sha256(RAW_RULES);

        assertTrue(OnlineLinkRuleRepository.checksumMatches(RAW_RULES, checksum));
        assertFalse(OnlineLinkRuleRepository.checksumMatches(RAW_RULES + " ", checksum));
        assertFalse(OnlineLinkRuleRepository.checksumMatches(RAW_RULES, "not-a-checksum"));
    }

    @Test
    public void validCacheAcceptsLegacyPayloadWithoutChecksum() {
        Map<String, List<String>> parsed = OnlineLinkRuleRepository.parseStoredCandidates(RAW_RULES);

        assertTrue(OnlineLinkRuleRepository.isValidStoredCache(RAW_RULES, "", parsed));
        assertTrue(OnlineLinkRuleRepository.isValidStoredCache(
                RAW_RULES, OnlineLinkRuleRepository.sha256(RAW_RULES), parsed));
        assertFalse(OnlineLinkRuleRepository.isValidStoredCache(
                RAW_RULES, "bad-checksum", parsed));
    }

    @Test
    public void parserSupportsLegacyAndCurrentPackageFields() {
        Map<String, List<String>> parsed = OnlineLinkRuleRepository.parseStoredCandidates(
                "[{\"host\":\"Example.COM\",\"package\":\"com.example.legacy\"},"
                        + "{\"host\":\"example.com\",\"packages\":[\"com.example.current\",\"bad\"]}]" );

        assertEquals(1, parsed.size());
        assertEquals(2, parsed.get("example.com").size());
        assertEquals("com.example.legacy", parsed.get("example.com").get(0));
        assertEquals("com.example.current", parsed.get("example.com").get(1));
    }

    @Test
    public void malformedOrEmptyCacheIsInvalid() {
        assertTrue(OnlineLinkRuleRepository.parseStoredCandidates("not-json").isEmpty());
        assertTrue(OnlineLinkRuleRepository.parseStoredCandidates("[]").isEmpty());
        assertFalse(OnlineLinkRuleRepository.isValidStoredCache(
                "[]", "", OnlineLinkRuleRepository.parseStoredCandidates("[]")));
    }


    @Test
    public void cacheSelectionPrefersValidPrimarySnapshot() {
        String backup = "[{\"host\":\"backup.example.com\",\"packages\":[\"com.example.backup\"]}]";

        OnlineLinkRuleRepository.StoredRules selected = OnlineLinkRuleRepository.selectStoredRules(
                RAW_RULES, OnlineLinkRuleRepository.sha256(RAW_RULES),
                backup, OnlineLinkRuleRepository.sha256(backup));

        assertTrue(selected.valid);
        assertFalse(selected.usingBackup);
        assertTrue(selected.checksumPresent);
        assertTrue(selected.rules.containsKey("www.example.com"));
        assertFalse(selected.rules.containsKey("backup.example.com"));
    }

    @Test
    public void cacheSelectionFallsBackWhenPrimaryChecksumIsCorrupt() {
        String backup = "[{\"host\":\"backup.example.com\",\"packages\":[\"com.example.backup\"]}]";

        OnlineLinkRuleRepository.StoredRules selected = OnlineLinkRuleRepository.selectStoredRules(
                RAW_RULES, "corrupt-checksum", backup, OnlineLinkRuleRepository.sha256(backup));

        assertTrue(selected.valid);
        assertTrue(selected.usingBackup);
        assertTrue(selected.checksumPresent);
        assertTrue(selected.rules.containsKey("backup.example.com"));
        assertFalse(selected.rules.containsKey("www.example.com"));
    }

    @Test
    public void cacheSelectionAcceptsLegacyPrimaryWithoutChecksum() {
        OnlineLinkRuleRepository.StoredRules selected = OnlineLinkRuleRepository.selectStoredRules(
                RAW_RULES, "", "", "");

        assertTrue(selected.valid);
        assertFalse(selected.usingBackup);
        assertFalse(selected.checksumPresent);
        assertEquals(1, selected.rules.size());
    }

    @Test
    public void cacheSelectionRejectsWhenBothSnapshotsAreInvalid() {
        OnlineLinkRuleRepository.StoredRules selected = OnlineLinkRuleRepository.selectStoredRules(
                "not-json", "", "[]", "");

        assertFalse(selected.valid);
        assertFalse(selected.usingBackup);
        assertTrue(selected.rules.isEmpty());
    }

    @Test
    public void boundedResponseAcceptsPayloadAtLimit() throws Exception {
        String payload = "0123456789";

        assertEquals(payload, OnlineLinkRuleRepository.readUtf8Bounded(
                new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)), 10));
    }

    @Test
    public void boundedResponseRejectsPayloadOverLimit() {
        try {
            OnlineLinkRuleRepository.readUtf8Bounded(
                    new ByteArrayInputStream("01234567890".getBytes(StandardCharsets.UTF_8)), 10);
        } catch (IOException error) {
            assertEquals("response-too-large", error.getMessage());
            return;
        }
        throw new AssertionError("oversized response was accepted");
    }
}
