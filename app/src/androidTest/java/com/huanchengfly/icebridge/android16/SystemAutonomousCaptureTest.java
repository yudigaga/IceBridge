package com.huanchengfly.icebridge.android16;

import android.content.ComponentName;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

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

    @Test public void ordinaryPublicViewRuleCanBeOwnedRegardlessOfLegacyFrozenOnlyPreference() throws Exception {
        Intent original = new Intent(Intent.ACTION_VIEW, Uri.parse(URL))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        SystemAutonomousCapture.Result result = SystemAutonomousCapture.capture(original, 0,
                configuration(WECHAT), WECHAT, true, -1);
        assertNotNull(result.request);
        assertTrue(result.request.onlyFrozen);
        assertTrue(SystemAutonomousCapture.matchesOwnedWebSource(original, result.request));
        assertEquals(WECHAT, result.request.sourcePackage);
        assertNull(result.request.payload.getPackage());
        assertEquals(URL, original.getDataString());
    }

    @Test public void knownPrivateCarrierCanBeOwnedButRetainsItsUnmodifiedSourcePayload() throws Exception {
        Intent original = webView(WECHAT);
        AutonomousBridgeCoordinator.Request<Intent> request = SystemAutonomousCapture.capture(original, 0,
                configuration(WECHAT), WECHAT, true, -1).request;
        assertTrue(SystemAutonomousCapture.matchesOwnedWebSource(original, request));
        assertEquals("source-private", original.getStringExtra("private_marker"));
        assertNull(request.payload.getExtras());
    }

    @Test public void opaquePublicViewExtrasAndExplicitInternalActivitiesKeepOriginalCallerChain() throws Exception {
        Intent plain = new Intent(Intent.ACTION_VIEW, Uri.parse(URL));
        AutonomousBridgeCoordinator.Request<Intent> request = SystemAutonomousCapture.capture(plain, 0,
                configuration(WECHAT), WECHAT, true, -1).request;
        assertNotNull(request);
        assertFalse(SystemAutonomousCapture.matchesOwnedWebSource(
                new Intent(plain).putExtra("callback", "caller-private"), request));
        assertFalse(SystemAutonomousCapture.matchesOwnedWebSource(
                new Intent(plain).setPackage(WECHAT), request));
        assertFalse(SystemAutonomousCapture.matchesOwnedWebSource(new Intent(plain)
                .setComponent(new ComponentName(WECHAT, "com.tencent.mm.InternalActivity")), request));
        assertFalse(SystemAutonomousCapture.matchesOwnedWebSource(
                new Intent(plain).setType("text/html").setDataAndType(Uri.parse(URL), "text/html"), request));
    }

    @Test public void grantsSelectorsAndClipDataCannotBeReassignedToModuleOwnedRoute() throws Exception {
        Intent original = new Intent(Intent.ACTION_VIEW, Uri.parse(URL));
        AutonomousBridgeCoordinator.Request<Intent> request = SystemAutonomousCapture.capture(original, 0,
                configuration(WECHAT), WECHAT, true, -1).request;
        for (int flag : new int[]{Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                Intent.FLAG_ACTIVITY_FORWARD_RESULT}) {
            Intent granted = new Intent(original).addFlags(flag);
            assertFalse(SystemAutonomousCapture.matchesOwnedWebSource(granted, request));
            assertNull(SystemAutonomousCapture.capture(granted, 0, configuration(WECHAT), WECHAT, true, -1).request);
        }
        Intent selected = new Intent(original);
        selected.setSelector(new Intent(Intent.ACTION_VIEW, Uri.parse(URL)));
        assertFalse(SystemAutonomousCapture.matchesOwnedWebSource(selected, request));
        assertNull(SystemAutonomousCapture.capture(selected, 0, configuration(WECHAT), WECHAT, true, -1).request);
        Intent clipped = new Intent(original);
        clipped.setClipData(ClipData.newPlainText("private", "caller-private"));
        assertFalse(SystemAutonomousCapture.matchesOwnedWebSource(clipped, request));
        assertNull(SystemAutonomousCapture.capture(clipped, 0, configuration(WECHAT), WECHAT, true, -1).request);
    }

    @Test public void ordinaryPublicViewForResultAndUnmatchedHostStayWithAndroid() throws Exception {
        Intent original = new Intent(Intent.ACTION_VIEW, Uri.parse(URL));
        assertNull(SystemAutonomousCapture.capture(original, 0, configuration(WECHAT), WECHAT, true, 5).request);
        assertNull(SystemAutonomousCapture.capture(original, 0, configuration(WECHAT), WECHAT, true, null).request);
        Intent unmatched = new Intent(Intent.ACTION_VIEW, Uri.parse("https://unmatched.example/fixture"));
        assertNull(SystemAutonomousCapture.capture(unmatched, 0, configuration(WECHAT), WECHAT, true, -1).request);
        assertEquals(URL, original.getDataString());
    }

    @Test public void legacyWebViewBridgeUsesSameCallerPayloadBoundaryWithExactNoResultCode() throws Exception {
        Intent original = webView(WECHAT);
        assertTrue(NativeBridgePolicy.canReplaceWebSource(-1,
                SystemAutonomousCapture.hasCallerBoundPayload(original)));
        for (Integer resultCode : new Integer[]{0, 42, null}) {
            assertFalse(NativeBridgePolicy.canReplaceWebSource(resultCode,
                    SystemAutonomousCapture.hasCallerBoundPayload(original)));
        }
        for (int flag : new int[]{Intent.FLAG_GRANT_READ_URI_PERMISSION, Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                Intent.FLAG_ACTIVITY_FORWARD_RESULT}) {
            assertFalse(NativeBridgePolicy.canReplaceWebSource(-1,
                    SystemAutonomousCapture.hasCallerBoundPayload(new Intent(original).addFlags(flag))));
        }
        Intent clipped = new Intent(original);
        clipped.setClipData(ClipData.newPlainText("private", "caller-private"));
        assertFalse(NativeBridgePolicy.canReplaceWebSource(-1,
                SystemAutonomousCapture.hasCallerBoundPayload(clipped)));
        Intent selected = new Intent(original);
        selected.setSelector(new Intent(Intent.ACTION_VIEW, Uri.parse(URL)));
        assertFalse(NativeBridgePolicy.canReplaceWebSource(-1,
                SystemAutonomousCapture.hasCallerBoundPayload(selected)));
        assertEquals("source-private", original.getStringExtra("private_marker"));
    }

    @Test public void fileDescriptorsCannotEnterEitherLegacyBridgeOrIndependentCapture() throws Exception {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            Intent original = webView(WECHAT).putExtra("caller-fd", pipe[0]);
            assertTrue(original.hasFileDescriptors());
            assertFalse(NativeBridgePolicy.canReplaceWebSource(-1,
                    SystemAutonomousCapture.hasCallerBoundPayload(original)));
            assertNull(SystemAutonomousCapture.capture(original, 0,
                    configuration(WECHAT), WECHAT, true, -1).request);
        } finally {
            pipe[0].close();
            pipe[1].close();
        }
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
