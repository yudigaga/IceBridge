package com.huanchengfly.icebridge.android16;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PaymentSdkBridgeResolverTest {
    @Test
    public void alipayOwnedSdkActivityCanBeReplayed() {
        assertTrue(PaymentSdkBridgeResolver.isReplayableSdkActivity(
                PaymentSdkBridgeResolver.ALIPAY_PACKAGE,
                "com.alipay.android.app.TransProcessPayActivity"));
    }

    @Test
    public void carrierEmbeddedSdkActivityCannotBeReplayed() {
        assertFalse(PaymentSdkBridgeResolver.isReplayableSdkActivity(
                "com.sinovatech.unicom.ui",
                "com.alipay.sdk.app.H5PayActivity"));
    }

    @Test
    public void sdkLikeActivityFromUnrelatedPackageCannotBeReplayed() {
        assertFalse(PaymentSdkBridgeResolver.isReplayableSdkActivity(
                "com.example.merchant",
                "com.alipay.sdk.app.H5PayActivity"));
    }
}
