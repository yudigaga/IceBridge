package com.huanchengfly.icebridge.android16;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;

import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;

import org.junit.Test;

import static org.junit.Assert.*;

public class SystemLaunchPreflightIntentTest {
    private static final String SELF = "io.github.yudigaga.icebridge";
    private static final String WALLET = PaymentSdkBridgeResolver.ALIPAY_PACKAGE;

    @Test public void strictCarrierSdkHostPrewarmsWalletAndPreservesOriginalSignedHostIntent() {
        Intent original = new Intent("carrier.PAY")
                .setComponent(new ComponentName("com.sinovatech.unicom.ui", "com.alipay.sdk.app.H5PayActivity"))
                .putExtra("orderInfo", "signed-caller-payload");
        Packages packages = new Packages();
        assertNull(PaymentSdkBridgeResolver.resolve(original)); // Existing replay guard stays strict.
        assertTrue(SystemLaunchPreflight.isPaymentSdkRequest(original));
        SystemLaunchPreflight.Result result = new SystemLaunchPreflight(SELF, packages).prepare(original, 0, 0, true);
        assertEquals(SystemLaunchPreflight.Outcome.THAWED, result.outcome);
        assertEquals(WALLET, packages.target);
        assertEquals("com.sinovatech.unicom.ui", original.getComponent().getPackageName());
        assertEquals("com.alipay.sdk.app.H5PayActivity", original.getComponent().getClassName());
        assertEquals("carrier.PAY", original.getAction());
        assertEquals("signed-caller-payload", original.getStringExtra("orderInfo"));
        assertEquals(0, original.getFlags());
    }

    @Test public void walletOwnedSdkAndDirectPaymentCandidatesShareThePaymentGate() {
        for (String name : new String[]{"com.alipay.sdk.app.H5PayActivity", WALLET + ".PaymentActivity"}) {
            Intent original = new Intent(Intent.ACTION_VIEW, Uri.parse("alipays://platformapi/startapp"))
                    .setComponent(new ComponentName(WALLET, name));
            assertTrue(SystemLaunchPreflight.isPaymentSdkRequest(original));
            Packages packages = new Packages();
            assertEquals(SystemLaunchPreflight.Outcome.THAWED,
                    new SystemLaunchPreflight(SELF, packages).prepare(original, 0, 0, true).outcome);
            assertEquals(WALLET, packages.target);
        }
    }

    @Test public void sdkLookingOwnComponentNeverThawsOwnerOrWallet() {
        Intent original = new Intent().setComponent(new ComponentName(SELF, "com.alipay.sdk.app.H5PayActivity"));
        Packages packages = new Packages();
        assertEquals("self-thaw-prohibited", new SystemLaunchPreflight(SELF, packages)
                .prepare(original, 0, 0, true).reason);
        assertNull(packages.target);
        assertEquals(0, packages.reads);
    }

    @Test public void ordinaryExplicitHostKeepsPrecedenceOverAnEmbeddedWalletUri() {
        Intent original = new Intent().setComponent(new ComponentName("com.example.host", "com.example.host.Page"))
                .putExtra("url", "alipays://platformapi/startapp");
        Packages packages = new Packages();
        assertFalse(SystemLaunchPreflight.isPaymentSdkRequest(original));
        assertEquals(SystemLaunchPreflight.Outcome.THAWED, new SystemLaunchPreflight(SELF, packages)
                .prepare(original, 0, 0, true).outcome);
        assertEquals("com.example.host", packages.target);
    }

    @Test public void implicitSerializedIntentAndEmbeddedSchemeAreReadWithoutRewritingPayload() {
        String serialized = new Intent(Intent.ACTION_VIEW,
                Uri.parse("alipays://platformapi/startapp?appId=20000067")).toUri(Intent.URI_INTENT_SCHEME);
        for (Intent original : new Intent[]{new Intent(Intent.ACTION_VIEW, Uri.parse(serialized)),
                new Intent(Intent.ACTION_VIEW).putExtra("url", serialized)}) {
            String before = original.toUri(Intent.URI_INTENT_SCHEME);
            Packages packages = new Packages();
            assertEquals(SystemLaunchPreflight.Outcome.THAWED, new SystemLaunchPreflight(SELF, packages)
                    .prepare(original, 0, 0, true).outcome);
            assertEquals(WALLET, packages.target);
            assertEquals(before, original.toUri(Intent.URI_INTENT_SCHEME));
        }
    }

    private static final class Packages implements AutonomousBridgeCoordinator.PackageAccess {
        AutonomousBridgeCoordinator.State state = AutonomousBridgeCoordinator.State.known(true, 3, false, false, false);
        String target;
        int reads;
        @Override public AutonomousBridgeCoordinator.State read(String target, int user) {
            this.target = target;
            reads++;
            return state;
        }
        @Override public AutonomousBridgeCoordinator.Capabilities capabilities() {
            return new AutonomousBridgeCoordinator.Capabilities(true, true, "fake");
        }
        @Override public String thaw(String target, int user, AutonomousBridgeCoordinator.State before) {
            state = AutonomousBridgeCoordinator.State.known(true, 1, true, false, false);
            return null;
        }
    }
}
