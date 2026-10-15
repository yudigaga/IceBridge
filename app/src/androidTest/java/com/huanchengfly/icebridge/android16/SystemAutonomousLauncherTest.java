package com.huanchengfly.icebridge.android16;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;

import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;

import org.junit.Test;

import static org.junit.Assert.*;

public class SystemAutonomousLauncherTest {
    private static final String SELF = "io.github.yudigaga.icebridge";
    private static final String TARGET = "com.example.target";

    @Test public void webRouteClearsSourceComponentAndCallerGrantsWithoutMutatingOriginal() {
        Intent original = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/path?a=1"))
                .setComponent(new ComponentName("com.tencent.mm", "com.tencent.mm.WebViewActivity"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra("source_marker", "preserved");
        original.setClipData(ClipData.newPlainText("private", "source clipboard"));
        Intent routed = SystemAutonomousLauncher.buildLaunchIntent(request(original,
                AutonomousBridgeCoordinator.Kind.WEB_RULE, "example.com"), TARGET, "none", SELF);
        assertEquals(original.getData(), routed.getData());
        assertNull(routed.getComponent());
        assertEquals(TARGET, routed.getPackage());
        assertEquals("preserved", routed.getStringExtra("source_marker"));
        assertTrue((routed.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals(0, routed.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertNull(routed.getClipData());
        assertNotNull(original.getComponent());
        assertNotNull(original.getClipData());
        assertTrue((original.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test public void fixedConversionUsesAuditedCatalogAndNoneKeepsTheWebUri() {
        Intent original = new Intent(Intent.ACTION_VIEW, Uri.parse("https://3.cn/item"));
        AutonomousBridgeCoordinator.Request<Intent> request = request(original,
                AutonomousBridgeCoordinator.Kind.WEB_RULE, "3.cn");
        Intent converted = SystemAutonomousLauncher.buildLaunchIntent(request,
                "com.jingdong.app.mall", "fixed_deep_link", SELF);
        assertEquals("openapp.jdmobile", converted.getData().getScheme());
        assertEquals(original.getData(), SystemAutonomousLauncher.buildLaunchIntent(request,
                "com.jingdong.app.mall", "none", SELF).getData());
        assertEquals(original.getData(), SystemAutonomousLauncher.buildLaunchIntent(request,
                TARGET, "fixed_deep_link", SELF).getData());
    }

    @Test public void embeddedWebViewUrlIsPromotedAndStillPreservesExtras() {
        Intent original = new Intent().putExtra("rawUrl", "https://example.com/embedded");
        Intent routed = SystemAutonomousLauncher.buildLaunchIntent(request(original,
                AutonomousBridgeCoordinator.Kind.WEB_RULE, "example.com"), TARGET, "none", SELF);
        assertEquals("https://example.com/embedded", routed.getDataString());
        assertEquals(Intent.ACTION_VIEW, routed.getAction());
        assertNull(original.getData());
    }

    @Test public void explicitNativeRequestRetainsComponentExtrasActionAndUri() {
        Intent original = new Intent("com.example.OPEN", Uri.parse("exampleapp://item/42"))
                .setComponent(new ComponentName(TARGET, TARGET + ".TargetActivity"))
                .putExtra("callback_payload", "opaque-signed-data");
        Intent routed = SystemAutonomousLauncher.buildLaunchIntent(request(original,
                AutonomousBridgeCoordinator.Kind.EXPLICIT, null), TARGET, "none", SELF);
        assertEquals(original.getComponent(), routed.getComponent());
        assertEquals(original.getAction(), routed.getAction());
        assertEquals(original.getData(), routed.getData());
        assertEquals("opaque-signed-data", routed.getStringExtra("callback_payload"));
        assertTrue((routed.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals(0, original.getFlags());
    }

    @Test public void webRoutesRejectNonWebSourceWrongHostAndUnknownConversion() {
        expectFailure(() -> SystemAutonomousLauncher.buildLaunchIntent(request(new Intent(
                Intent.ACTION_VIEW, Uri.parse("content://private/item")),
                AutonomousBridgeCoordinator.Kind.WEB_RULE, "private"), TARGET, "none", SELF));
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/path"));
        expectFailure(() -> SystemAutonomousLauncher.buildLaunchIntent(request(web,
                AutonomousBridgeCoordinator.Kind.WEB_RULE, "other.example"), TARGET, "none", SELF));
        expectFailure(() -> SystemAutonomousLauncher.buildLaunchIntent(request(web,
                AutonomousBridgeCoordinator.Kind.WEB_RULE, "example.com"), TARGET, "execute_script", SELF));
    }

    @Test public void explicitGrantAndWrongTargetAreNeverReplayedUnderSystemIdentity() {
        Intent original = new Intent(Intent.ACTION_VIEW, Uri.parse("exampleapp://item"))
                .setPackage(TARGET).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        expectFailure(() -> SystemAutonomousLauncher.buildLaunchIntent(request(original,
                AutonomousBridgeCoordinator.Kind.EXPLICIT, null), TARGET, "none", SELF));
        original.setFlags(0);
        expectFailure(() -> SystemAutonomousLauncher.buildLaunchIntent(request(original,
                AutonomousBridgeCoordinator.Kind.EXPLICIT, null), "com.example.wrong", "none", SELF));
    }

    @Test public void paymentSdkAndThawOnlyCannotBeConvertedIntoIndependentActivityStarts() {
        Intent payment = new Intent().setComponent(new ComponentName(
                PaymentSdkBridgeResolver.ALIPAY_PACKAGE, "com.alipay.sdk.app.H5PayActivity"))
                .putExtra("orderInfo", "signed-caller-payload");
        expectFailure(() -> SystemAutonomousLauncher.buildLaunchIntent(request(payment,
                AutonomousBridgeCoordinator.Kind.EXPLICIT, null),
                PaymentSdkBridgeResolver.ALIPAY_PACKAGE, "none", SELF));
        expectFailure(() -> SystemAutonomousLauncher.buildLaunchIntent(request(payment,
                AutonomousBridgeCoordinator.Kind.THAW_ONLY, null),
                PaymentSdkBridgeResolver.ALIPAY_PACKAGE, "none", SELF));
        assertEquals("signed-caller-payload", payment.getStringExtra("orderInfo"));
    }

    @Test public void launcherNeverStartsOwnPackage() {
        expectFailure(() -> SystemAutonomousLauncher.buildLaunchIntent(request(new Intent(
                Intent.ACTION_VIEW, Uri.parse("https://example.com/path")),
                AutonomousBridgeCoordinator.Kind.WEB_RULE, "example.com"), SELF, "none", SELF));
    }

    private static AutonomousBridgeCoordinator.Request<Intent> request(Intent original,
            AutonomousBridgeCoordinator.Kind kind, String host) {
        return new AutonomousBridgeCoordinator.Request<>(kind, 10, "com.tencent.mm", host,
                TARGET, "request-fingerprint", original, false, true);
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            fail("expected fail-closed launch policy");
        } catch (IllegalArgumentException expected) { }
    }
}
