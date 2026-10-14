package com.huanchengfly.icebridge.automation;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RuleConfigTransferTest {
    @Test(expected = RuleConfigTransfer.TransferException.class)
    public void rejectsSourceLongerThanWireLimit() throws Exception {
        char[] longPart = new char[252]; java.util.Arrays.fill(longPart, 'a');
        RuleConfigTransfer.importJson("{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[{"
                + "\"sourcePackage\":\"com." + new String(longPart)
                + "\",\"host\":\"example.com\",\"package\":\"com.example.app\"}]}");
    }

    @Test(expected = RuleConfigTransfer.TransferException.class)
    public void rejectsHostLongerThanWireLimit() throws Exception {
        char[] longPart = new char[125]; java.util.Arrays.fill(longPart, 'a');
        RuleConfigTransfer.importJson("{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[{"
                + "\"host\":\"" + new String(longPart) + ".com\",\"package\":\"com.example.app\"}]}");
    }

    @Test(expected = RuleConfigTransfer.TransferException.class)
    public void rejectsWildcardTarget() throws Exception {
        RuleConfigTransfer.importJson("{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[{"
                + "\"host\":\"example.com\",\"package\":\"*\"}]}");
    }
    @Test
    public void exportContainsVersionedManualRulesOnly() throws Exception {
        String json = RuleConfigTransfer.exportJson(Arrays.asList(
                new LinkRuleEntry("*", "x.com", "com.example.x", LinkRuleEntry.SOURCE_MANUAL,
                        LinkRuleEntry.CONVERSION_NONE, "manual"),
                new LinkRuleEntry("x.com", "com.example.online", LinkRuleEntry.SOURCE_ONLINE,
                        LinkRuleEntry.CONVERSION_NONE, "online"),
                new LinkRuleEntry("*", "built-in.example", "com.example.builtin", LinkRuleEntry.SOURCE_BUILT_IN,
                        LinkRuleEntry.CONVERSION_NONE, "built in")));

        assertTrue(json.contains("\"format\":\"icebridge-rules\""));
        assertTrue(json.contains("\"version\":1"));
        assertTrue(json.contains("com.example.x"));
        assertTrue(!json.contains("com.example.online"));
        assertTrue(!json.contains("com.example.builtin"));
        assertEquals(1, RuleConfigTransfer.importJson(json).size());
    }

    @Test
    public void emptyRulesAreValid() throws Exception {
        assertTrue(RuleConfigTransfer.importJson(
                "{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[]}").isEmpty());
    }

    @Test
    public void importAcceptsValidRulesAndDeduplicates() throws Exception {
        String json = "{" +
                "\"format\":\"icebridge-rules\",\"version\":1," +
                "\"rules\":[" +
                "{\"sourcePackage\":\"*\",\"host\":\"https://X.com/path\",\"package\":\"com.example.x\"}," +
                "{\"sourcePackage\":\"*\",\"host\":\"x.com\",\"package\":\"com.example.x\"}" +
                "]}";

        List<LinkRuleEntry> rules = RuleConfigTransfer.importJson(json);
        assertEquals(1, rules.size());
        assertEquals("x.com", rules.get(0).host);
        assertEquals(LinkRuleEntry.CONVERSION_NONE, rules.get(0).urlConversion);
    }

    @Test
    public void importAcceptsLegacyTargetPackageField() throws Exception {
        List<LinkRuleEntry> rules = RuleConfigTransfer.importJson(
                "{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[" +
                        "{\"host\":\"x.com\",\"targetPackage\":\"com.example.x\"}]}");
        assertEquals(1, rules.size());
        assertEquals("com.example.x", rules.get(0).targetPackage);
    }

    @Test
    public void noteIsTrimmedAndCapped() throws Exception {
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < 200; i++) note.append('x');
        List<LinkRuleEntry> rules = RuleConfigTransfer.importJson(
                "{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[" +
                        "{\"host\":\"x.com\",\"package\":\"com.example.x\",\"note\":\" " +
                        note + " \"}]}");
        assertEquals(160, rules.get(0).note.length());
    }

    @Test(expected = RuleConfigTransfer.TransferException.class)
    public void rejectsWrongFormat() throws Exception {
        RuleConfigTransfer.importJson("{\"format\":\"other\",\"version\":1,\"rules\":[]}");
    }

    @Test(expected = RuleConfigTransfer.TransferException.class)
    public void rejectsUnsupportedVersion() throws Exception {
        RuleConfigTransfer.importJson("{\"format\":\"icebridge-rules\",\"version\":2,\"rules\":[]}");
    }

    @Test(expected = RuleConfigTransfer.TransferException.class)
    public void rejectsInvalidPackage() throws Exception {
        RuleConfigTransfer.importJson("{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[{" +
                "\"host\":\"x.com\",\"package\":\"not a package\"}]}");
    }

    @Test(expected = RuleConfigTransfer.TransferException.class)
    public void rejectsInvalidSourcePackage() throws Exception {
        RuleConfigTransfer.importJson("{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[{" +
                "\"sourcePackage\":\"not a package\",\"host\":\"x.com\",\"package\":\"com.example.x\"}]}");
    }

    @Test(expected = RuleConfigTransfer.TransferException.class)
    public void rejectsInvalidConversion() throws Exception {
        RuleConfigTransfer.importJson("{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[{" +
                "\"host\":\"x.com\",\"package\":\"com.example.x\",\"conversion\":\"unknown\"}]}");
    }

    @Test(expected = RuleConfigTransfer.TransferException.class)
    public void rejectsMissingHost() throws Exception {
        RuleConfigTransfer.importJson("{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[{" +
                "\"package\":\"com.example.x\"}]}");
    }

    @Test(expected = RuleConfigTransfer.TransferException.class)
    public void rejectsTooManyDistinctHosts() throws Exception {
        StringBuilder json = new StringBuilder(
                "{\"format\":\"icebridge-rules\",\"version\":1,\"rules\":[");
        for (int i = 0; i < 97; i++) {
            if (i > 0) json.append(',');
            json.append("{\"host\":\"host").append(i)
                    .append(".example.com\",\"package\":\"com.example.x\"}");
        }
        json.append("]}");
        RuleConfigTransfer.importJson(json.toString());
    }

    @Test(expected = RuleConfigTransfer.TransferException.class)
    public void rejectsOversizedJson() throws Exception {
        char[] data = new char[RuleConfigTransfer.MAX_BYTES + 1];
        Arrays.fill(data, 'x');
        RuleConfigTransfer.importJson(new String(data));
    }
}
