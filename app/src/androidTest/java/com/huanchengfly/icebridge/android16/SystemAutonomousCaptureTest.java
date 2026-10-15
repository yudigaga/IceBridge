package com.huanchengfly.icebridge.android16;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;

import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;
import com.huanchengfly.icebridge.automation.SystemRuntimeConfig;

import org.junit.Test;

import static org.junit.Assert.*;

/** Framework Intent/Bundle tests for the actual WeChat and QQ Activity start shape. */
public class SystemAutonomousCaptureTest {
    private static final String WECHAT = "com.tencent.mm";
    private static final String QQ = "com.tencent.mobileqq";
    private static final String URL = "https://v.douyin.com/fixture";
    private static final String TARGET = "com.ss.android.ugc.aweme";

    @Test public void ordinaryWechatStartHasCallerTokenButNoResultRequest() throws Exception {
        assertWebObservation(webView(WECHAT), WECHAT, true, -1);
    }

    @Test public void ordinaryQqStartHasCallerTokenButNoResultRequest() throws Exception {
        assertWebObservation(webView(QQ), QQ, true, -1);
    }

    @Test public void existingWechatCaptureWithoutCallerTokenStillRoutes() throws Exception {
        assertWebObservation(webView(WECHAT), WECHAT, false, -1);
    }

    @Test public void actualResultRequestRemainsOwnedByItsCaller() throws Exception {
        Intent original = webView(WECHAT);
        assertNull(SystemAutonomousCapture.capture(original, 0, configuration(WECHAT),
                WECHAT, true, 42).request);
        assertNull(original.getData());
        assertEquals(URL, original.getStringExtra("rawUrl"));
    }

    @Test public void forwardResultIsNeverReplayedAsAnIndependentStart() throws Exception {
        Intent original = webView(WECHAT).addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        assertNull(SystemAutonomousCapture.capture(original, 0, configuration(WECHAT),
                WECHAT, true, -1).request);
    }

    @Test public void unknownRequestCodeWithCallerTokenFailsClosed() throws Exception {
        assertNull(SystemAutonomousCapture.capture(webView(WECHAT), 0, configuration(WECHAT),
                WECHAT, true, null).request);
    }

    @Test public void explicitSchemeWithResultRequestNeverReplaysCallerRelationship() throws Exception {
        Intent original = new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=fixture"))
                .setPackage("org.telegram.messenger").putExtra("callback", "caller-private");
        assertNull(SystemAutonomousCapture.capture(original, 0, configuration(WECHAT),
                WECHAT, true, 10).request);
        assertEquals("caller-private", original.getStringExtra("callback"));
    }

    @Test public void sourceSpecificRuleDoesNotLeakToAnotherCarrier() throws Exception {
        SystemAutonomousCapture.Result result = SystemAutonomousCapture.capture(webView(QQ), 0,
                configuration(WECHAT), QQ, false, -1);
        assertTrue(result.recognizedSource);
        assertNull(result.request);
        assertEquals("no-rule", result.reason);
    }

    private static void assertWebObservation(Intent original, String source, boolean hasResultTo,
                                            Integer requestCode) throws Exception {
        Intent before = new Intent(original);
        SystemAutonomousCapture.Result result = SystemAutonomousCapture.capture(original, 0,
                configuration(source), source, hasResultTo, requestCode);
        assertTrue(result.recognizedSource);
        assertNotNull("ordinary Activity.startActivity must remain observable", result.request);
        AutonomousBridgeCoordinator.Request<Intent> request = result.request;
        assertEquals(AutonomousBridgeCoordinator.Kind.WEB_RULE, request.kind);
        assertFalse(request.ownsOriginal);
        assertEquals(source, request.sourcePackage);
        assertEquals("v.douyin.com", request.host);
        assertNull(request.explicitTarget);
        assertEquals(URL, request.payload.getDataString());
        assertEquals(Intent.ACTION_VIEW, request.payload.getAction());
        assertTrue(request.payload.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertNull(request.payload.getComponent());
        assertNull(request.payload.getExtras());
        assertTrue(before.filterEquals(original));
        assertEquals(before.getFlags(), original.getFlags());
        assertNull(original.getAction());
        assertNull(original.getData());
        assertEquals(URL, original.getStringExtra("rawUrl"));
        assertEquals("source-private", original.getStringExtra("private_marker"));
    }

    private static Intent webView(String source) {
        String activity = WECHAT.equals(source) ? "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI"
                : "com.tencent.mobileqq.activity.QQBrowserActivity";
        return new Intent().setComponent(new ComponentName(source, activity))
                .putExtra("rawUrl", URL).putExtra("private_marker", "source-private");
    }

    private static SystemRuntimeConfig.State configuration(String source) throws Exception {
        return new SystemRuntimeConfig.State(8, 0, 1, true, true, true, true,
                false, true, true, true, false,
                new String[] { source + "|v.douyin.com|" + TARGET + "|manual|none" },
                SystemRuntimeConfig.OnlineState.empty());
    }
}
