package com.huanchengfly.icebridge.android16;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class IntentUrlExtractorTest {
    @Test
    public void extractsDirectUrlExtra() {
        assertEquals("https://x.com/i/status/123?utm_source=test",
                IntentUrlExtractor.extractUrl("https://x.com/i/status/123?utm_source=test"));
    }

    @Test
    public void extractsSerializedWeChatAndQqValues() {
        assertEquals("https://www.youtube.com/watch?v=abc",
                IntentUrlExtractor.extractUrl("S.rawUrl=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dabc;"));
        assertEquals("https://instagram.com/p/abc",
                IntentUrlExtractor.extractUrl("S.url=https://instagram.com/p/abc"));
    }

    @Test
    public void extractsOnlyHttpUrlsForNativeActivityCapture() {
        assertEquals("https://x.com/example",
                IntentUrlExtractor.extractHttpUrl("S.rawUrl=https%3A%2F%2Fx.com%2Fexample;"));
        assertNull(IntentUrlExtractor.extractHttpUrl("intent://x.com/example#Intent;end"));
        assertNull(IntentUrlExtractor.extractHttpUrl("market://details?id=com.twitter.android"));
    }

    @Test
    public void rejectsInvalidValues() {
        assertNull(IntentUrlExtractor.extractUrl("not a URL"));
        assertNull(IntentUrlExtractor.extractUrl("content://example/item"));
    }
}
