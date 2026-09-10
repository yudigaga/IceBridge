package com.huanchengfly.icebridge.automation;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuleConfigRepositoryTest {
    @Test
    public void manualRulesPrecedeOnlineRules() {
        Map<String, List<String>> manual = new LinkedHashMap<>();
        manual.put("com.example.manual", Collections.singletonList("example.com"));

        List<LinkRuleEntry> online = Collections.singletonList(
                new LinkRuleEntry(
                        "example.com",
                        "com.example.online",
                        LinkRuleEntry.SOURCE_ONLINE,
                        LinkRuleEntry.CONVERSION_NONE,
                        ""));

        List<LinkRuleEntry> result = RuleConfigRepository.buildEffectiveRuleEntries(manual, online);

        assertEquals(2, result.size());
        assertEquals("com.example.manual", result.get(0).targetPackage);
        assertEquals(LinkRuleEntry.SOURCE_MANUAL, result.get(0).source);
        assertEquals("com.example.online", result.get(1).targetPackage);
        assertEquals(LinkRuleEntry.SOURCE_ONLINE, result.get(1).source);
    }

    @Test
    public void bundledRulesAreNotAnEffectiveInput() {
        assertTrue(RuleConfigRepository.builtInRulesByPackage().isEmpty());

        Map<String, List<String>> manual = new LinkedHashMap<>();
        manual.put("com.example.manual", Collections.singletonList("example.com"));
        List<LinkRuleEntry> result = RuleConfigRepository.buildEffectiveRuleEntries(manual, Collections.<LinkRuleEntry>emptyList());

        assertEquals(1, result.size());
        assertFalse(result.get(0).source.equals(LinkRuleEntry.SOURCE_BUILT_IN));
        assertEquals("com.example.manual", result.get(0).targetPackage);
    }

    @Test
    public void uninstalledCandidatesAreRemovedBeforeSystemServerHandoff() {
        Map<String, List<String>> configured = new LinkedHashMap<>();
        configured.put("bilibili.com", Arrays.asList("com.example.piliplus", "tv.danmaku.bili"));

        Map<String, List<String>> filtered = RuleConfigRepository.filterCandidatesForInstalledPackages(
                configured,
                Collections.singleton("tv.danmaku.bili"));

        assertEquals(Collections.singletonList("tv.danmaku.bili"), filtered.get("bilibili.com"));
        assertFalse(filtered.containsKey("missing.example.com"));
    }

    @Test
    public void allMissingCandidatesRemoveTheHost() {
        Map<String, List<String>> configured = new LinkedHashMap<>();
        configured.put("x.com", Arrays.asList("com.example.one", "com.example.two"));

        Map<String, List<String>> filtered = RuleConfigRepository.filterCandidatesForInstalledPackages(
                configured,
                Collections.<String>emptySet());

        assertTrue(filtered.isEmpty());
    }
}
