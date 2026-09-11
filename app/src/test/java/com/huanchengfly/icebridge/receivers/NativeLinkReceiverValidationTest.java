package com.huanchengfly.icebridge.receivers;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeLinkReceiverValidationTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void acceptsKnownSystemServerEvents() {
        assertTrue(NativeLinkReceiver.isValidPayload("https://mp.weixin.qq.com/s/example",
                "wechat_webview", "com.tencent.mm",
                "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI", 0, NOW, NOW));
        assertTrue(NativeLinkReceiver.isValidPayload("https://example.com/post",
                "qq_browser", "com.tencent.mobileqq",
                "com.tencent.mobileqq.activity.QQBrowserActivity", 999, NOW - 1, NOW));
        assertTrue(NativeLinkReceiver.isValidPayload("https://example.com/path",
                "clipboard", null, null, 10, NOW, NOW));
    }

    @Test
    public void rejectsSpoofedSourceAndInvalidPayloadFields() {
        assertFalse(NativeLinkReceiver.isValidPayload("https://example.com", "wechat_webview",
                "com.example.fake", "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI", 0, NOW, NOW));
        assertFalse(NativeLinkReceiver.isValidPayload("https://example.com", "qq_browser",
                "com.tencent.mobileqq", "com.example.OtherActivity", 0, NOW, NOW));
        assertFalse(NativeLinkReceiver.isValidUrl("file:///data/local/tmp/value"));
        assertFalse(NativeLinkReceiver.isValidUrl(" https://example.com"));
        assertFalse(NativeLinkReceiver.isValidUrl("https://"));
        assertFalse(NativeLinkReceiver.isValidPackageName("com.example-unsafe.app"));
        assertFalse(NativeLinkReceiver.isValidActivityName(".RelativeActivity"));
        assertFalse(NativeLinkReceiver.isValidUserId(-1));
        assertFalse(NativeLinkReceiver.isValidUserId(1000));
        assertFalse(NativeLinkReceiver.isFresh(NOW - 120_001L, NOW));
        assertFalse(NativeLinkReceiver.isFresh(NOW + 120_001L, NOW));
    }

    @Test
    public void rejectsUnknownSourcesAndOversizedUrls() {
        assertFalse(NativeLinkReceiver.isValidSource("browser"));
        StringBuilder oversized = new StringBuilder("https://example.com/");
        while (oversized.length() <= 8192) oversized.append('a');
        assertFalse(NativeLinkReceiver.isValidUrl(oversized.toString()));
    }
}
