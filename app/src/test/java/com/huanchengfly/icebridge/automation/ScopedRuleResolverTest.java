package com.huanchengfly.icebridge.automation;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class ScopedRuleResolverTest {
    private LinkRuleEntry rule(String source, String target, String origin, String conversion) {
        return new LinkRuleEntry(source, "example.com", target, origin, conversion, "");
    }

    @Test public void transportPreservesSourceAndConversionAndClipboardCannotSeeScopedRules() {
        List<LinkRuleEntry> rules = Arrays.asList(
                rule("com.tencent.mm", "com.example.wechat", "manual", "fixed_deep_link"),
                rule("com.tencent.mobileqq", "com.example.qq", "online", "none"));
        List<LinkRuleEntry> restored = ScopedRuleResolver.decode(ScopedRuleResolver.encode(rules));
        assertEquals(rules, restored);
        assertEquals("com.example.wechat", ScopedRuleResolver.matchingRules(restored,
                "com.tencent.mm", "m.example.com").get(0).targetPackage);
        assertTrue(ScopedRuleResolver.matchingRules(restored, "*", "example.com").isEmpty());
        assertTrue(ScopedRuleResolver.matchingRules(restored, "com.tencent.mm", "evil-example.com").isEmpty());
    }

    @Test public void manualRuleWinsOverOnlineWithinMatchingSource() {
        List<LinkRuleEntry> rules = Arrays.asList(
                rule("com.tencent.mm", "com.example.online", "online", "none"),
                rule("*", "com.example.manual", "manual", "none"));
        List<LinkRuleEntry> matches = ScopedRuleResolver.matchingRules(rules, "com.tencent.mm", "example.com");
        assertEquals("com.example.manual", matches.get(0).targetPackage);
        assertEquals("com.example.manual", ScopedRuleResolver.selectFrozenRule(matches,
                new java.util.HashSet<>(Arrays.asList("com.example.manual", "com.example.online"))).targetPackage);
    }

    @Test public void ambiguousTargetsRemainUnclaimedAndEnabledManualTargetDoesNotWakeOnlineFallback() {
        List<LinkRuleEntry> rules = Arrays.asList(rule("*", "com.example.one", "online", "none"),
                rule("*", "com.example.two", "online", "none"));
        assertNull(ScopedRuleResolver.selectFrozenRule(ScopedRuleResolver.matchingRules(rules, "*", "example.com"),
                new java.util.HashSet<>(Arrays.asList("com.example.one", "com.example.two"))));
        List<LinkRuleEntry> manual = Arrays.asList(rule("*", "com.example.enabled", "manual", "none"),
                rule("*", "com.example.two", "online", "none"));
        assertNull(ScopedRuleResolver.selectFrozenRule(ScopedRuleResolver.matchingRules(manual, "*", "example.com"),
                Collections.singleton("com.example.two")));
    }

    @Test(expected = IllegalArgumentException.class) public void malformedTransportFailsClosed() {
        ScopedRuleResolver.decode(new String[]{"*|example.com|com.example.app|online|arbitrary_script"});
    }

    @Test public void installedProjectionAgreesBeforeAndAfterSystemServerTransport() {
        List<LinkRuleEntry> all = Arrays.asList(rule("*", "com.example.removed", "manual", "none"),
                rule("*", "com.example.frozen", "online", "none"));
        List<LinkRuleEntry> projected = ScopedRuleResolver.filterInstalled(all,
                Collections.singleton("com.example.frozen"));
        List<LinkRuleEntry> transported = ScopedRuleResolver.decode(ScopedRuleResolver.encode(projected));
        for (List<LinkRuleEntry> entries : Arrays.asList(projected, transported)) {
            LinkRuleEntry chosen = ScopedRuleResolver.selectFrozenRule(
                    ScopedRuleResolver.matchingRules(entries, "com.tencent.mm", "example.com"),
                    Collections.singleton("com.example.frozen"));
            assertNotNull(chosen);
            assertEquals("com.example.frozen", chosen.targetPackage);
        }
        List<LinkRuleEntry> bothInstalled = ScopedRuleResolver.filterInstalled(all,
                new java.util.HashSet<>(Arrays.asList("com.example.removed", "com.example.frozen")));
        assertNull(ScopedRuleResolver.selectFrozenRule(
                ScopedRuleResolver.matchingRules(bothInstalled, "*", "example.com"),
                Collections.singleton("com.example.frozen")));
    }
}
