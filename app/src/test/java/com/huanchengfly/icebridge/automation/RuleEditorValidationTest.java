package com.huanchengfly.icebridge.automation;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class RuleEditorValidationTest {
    private RuleEditorValidation.Result validate(String source, String hosts) {
        return RuleEditorValidation.validate(source, "com.example.reader", hosts,
                LinkRuleEntry.CONVERSION_NONE, "");
    }

    @Test public void mixedValidAndInvalidHostsCannotSilentlyPartiallySave() {
        RuleEditorValidation.Result result = validate("*", "example.com\nftp://invalid.example");
        assertFalse(result.isValid());
        assertTrue(result.entries.isEmpty());
        assertTrue(result.errors.toString().contains("ftp://invalid.example"));
    }

    @Test public void sharesRepositoryNormalizationAndPreservesSource() {
        RuleEditorValidation.Result result = validate("com.tencent.mm", "https://EXAMPLE.com/a，example.com; news.example.com");
        assertTrue(result.errors.toString(), result.isValid());
        assertEquals(2, result.entries.size());
        assertEquals("example.com", result.entries.get(0).host);
        assertEquals("com.tencent.mm", result.entries.get(0).sourcePackage);
        assertEquals(LinkRuleEntry.SOURCE_MANUAL, result.entries.get(0).source);
    }

    @Test public void invalidSourceTargetConversionAndLongNoteAreExplained() {
        RuleEditorValidation.Result result = RuleEditorValidation.validate("bad source", "*", "example.com", "unknown", new String(new char[161]).replace('\0', 'a'));
        assertEquals(4, result.errors.size());
        assertTrue(result.entries.isEmpty());
    }

    @Test public void blankRowMustBeDeletedExplicitly() {
        assertFalse(validate("*", "  \n ").isValid());
    }

    @Test public void twentyFirstHostIsRejectedInsteadOfTruncated() {
        StringBuilder hosts = new StringBuilder();
        for (int index = 0; index < 21; index++) hosts.append("a").append(index).append(".example.com\n");
        assertFalse(validate("*", hosts.toString()).isValid());
    }

    @Test public void capacityIsCheckedBeforeRepositoryCanDropExcessHosts() {
        List<LinkRuleEntry> entries = new ArrayList<>();
        for (int i = 0; i < 96; i++) entries.add(new LinkRuleEntry("a" + i + ".example.com", "com.example.reader", LinkRuleEntry.SOURCE_MANUAL, "none", ""));
        assertNull(RuleEditorValidation.validateCapacity(entries));
        entries.add(new LinkRuleEntry("overflow.example.com", "com.example.reader", LinkRuleEntry.SOURCE_MANUAL, "none", ""));
        assertNotNull(RuleEditorValidation.validateCapacity(entries));
    }

    @Test public void previewAcceptsHttpsWithPathWithoutReimplementingRouting() {
        assertEquals("example.com", RuleEditorValidation.previewHost("com.tencent.mm", "https://example.com/a?q=one"));
        assertEquals("example.com", RuleEditorValidation.previewHost("*", "http://example.com/"));
    }

    @Test(expected = IllegalArgumentException.class) public void previewRejectsNonHttpScheme() {
        RuleEditorValidation.previewHost("com.tencent.mm", "xhsdiscover://example.com/a");
    }

    @Test(expected = IllegalArgumentException.class) public void previewRejectsUnknownSourceSyntax() {
        RuleEditorValidation.previewHost("bad source", "https://example.com/");
    }

    @Test public void replacingTargetPreservesLegacyAndRecentlyImportedRulesForOtherTargets() {
        LinkRuleEntry legacy = new LinkRuleEntry("legacy.example.com", "com.example.legacy", "manual", "none", "");
        LinkRuleEntry previous = new LinkRuleEntry("old.example.com", "com.example.reader", "manual", "none", "");
        LinkRuleEntry imported = new LinkRuleEntry("com.tencent.mm", "new.example.com", "com.example.imported", "manual", "none", "");
        LinkRuleEntry edited = validate("*", "edited.example.com").entries.get(0);
        assertEquals(Arrays.asList(legacy, imported, edited), RuleEditorValidation.replaceTarget(
                Arrays.asList(legacy, previous, imported), "com.example.reader", Collections.singletonList(edited)));
    }

    @Test public void deletingAllRowsRemovesOnlyTheEditedTarget() {
        LinkRuleEntry keep = new LinkRuleEntry("legacy.example.com", "com.example.legacy", "manual", "none", "");
        assertEquals(Collections.singletonList(keep), RuleEditorValidation.replaceTarget(
                Arrays.asList(keep, validate("*", "example.com").entries.get(0)),
                "com.example.reader", Collections.emptyList()));
    }
}
