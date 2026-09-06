package com.huanchengfly.icebridge.android16;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

public class WebViewCapturePolicyTest {
    private static final Map<String, Object> RULES = Collections.singletonMap("xiaohongshu.com", "com.xingin.xhs");

    @Test public void capturesNormalShareUrlWithSecurityToken() {
        WebViewCapturePolicy.Navigation n = WebViewCapturePolicy.inspect(
                "wv-xhs", "com.tencent.mm",
                "https://www.xiaohongshu.com/discovery/item/abc?xsec_token=abc&share_id=1#pushState",
                true, true, RULES);
        assertTrue(n.capture);
        assertEquals("com.xingin.xhs", n.target);
    }

    @Test public void capturesCoolapkSubdomainAndUsesLongestRule() {
        Map<String, Object> rules = new java.util.HashMap<>();
        rules.put("coolapk.com", "generic.coolapk");
        rules.put("www.coolapk.com", "com.coolapk.market");
        WebViewCapturePolicy.Navigation n = WebViewCapturePolicy.inspect(
                "wv-coolapk", "org.telegram.messenger",
                "https://www.coolapk.com/feed/73574452?s=abc", true, true, rules);
        assertTrue(n.capture);
        assertEquals("com.coolapk.market", n.target);
    }

    @Test public void supportsCandidateRules() {
        Map<String, Object> rules = Collections.<String, Object>singletonMap(
                "example.com", Arrays.asList("", "com.example.app"));
        assertTrue(WebViewCapturePolicy.inspect("wv-candidate", "source",
                "https://example.com/a", true, true, rules).capture);
    }

    @Test public void skipsIframePostAndSensitiveFlows() {
        assertFalse(WebViewCapturePolicy.inspect("wv-iframe", "source", "https://example.com/a", false, true, RULES).capture);
        assertFalse(WebViewCapturePolicy.inspect("wv-post", "source", "https://example.com/a", true, false, RULES).capture);
        assertFalse(WebViewCapturePolicy.inspect("wv-pay", "source", "https://example.com/payment/start", true, true,
                Collections.<String, Object>singletonMap("example.com", "com.example.app")).capture);
    }

    @Test public void redactsQueryAndKeepsOnlySafePath() {
        assertEquals("https://coolapk.com/feed/73574452?[redacted]",
                WebViewCapturePolicy.redactForLog(
                        "https://coolapk.com/feed/73574452?s=private-token#fragment"));
        assertEquals("[invalid]", WebViewCapturePolicy.redactForLog("not a url"));
    }

    @Test public void skipsUnsupportedSchemesAndInvalidUrls() {
        assertFalse(WebViewCapturePolicy.inspect("wv-scheme", "source", "intent://example.com/a", true, true, RULES).capture);
        assertFalse(WebViewCapturePolicy.inspect("wv-invalid", "source", "not a url", true, true, RULES).capture);
    }
}
