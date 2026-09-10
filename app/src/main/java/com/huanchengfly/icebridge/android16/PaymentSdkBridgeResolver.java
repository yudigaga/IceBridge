package com.huanchengfly.icebridge.android16;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Identifies payment SDK wrapper Activities before they try to hand control to a frozen wallet.
 *
 * <p>Only an Activity owned by Alipay itself may be prewarmed and replayed. Merchant and carrier
 * apps can embed similarly named SDK Activities, including non-exported ones. Replaying those
 * explicit intents from IceBridge changes the caller UID and Android correctly rejects the launch;
 * they must remain on the original caller path.</p>
 */
public final class PaymentSdkBridgeResolver {
    public static final String ALIPAY_PACKAGE = "com.eg.android.AlipayGphone";

    private PaymentSdkBridgeResolver() {
    }

    public static Candidate resolve(Intent intent) {
        if (intent == null || intent.getComponent() == null) {
            return null;
        }
        ComponentName component = intent.getComponent();
        String componentPackage = component.getPackageName();
        String className = component.getClassName();

        // Only a component declared by Alipay can safely be replayed from IceBridge's UID.
        // A carrier/merchant's embedded, SDK-looking Activity may be non-exported, and must pass
        // through untouched so Android retains the original caller identity.
        if (isReplayableSdkActivity(componentPackage, className)) {
            return Candidate.prewarm(ALIPAY_PACKAGE, "alipay_h5_sdk");
        }

        // An Activity inside the Alipay package is only a candidate when the request carries a
        // clear wallet/payment signal. Matching every Alipay Activity would intercept normal
        // Alipay pages and was the cause of page/task jitter in earlier builds.
        if (ALIPAY_PACKAGE.equals(componentPackage) && hasPaymentSignal(intent)) {
            return Candidate.launch(ALIPAY_PACKAGE, "alipay_direct");
        }
        return null;
    }

    public static boolean isReplayableSdkActivity(String componentPackage, String className) {
        return ALIPAY_PACKAGE.equals(componentPackage) && isSdkActivity(className);
    }

    private static boolean isSdkActivity(String className) {
        return className != null && (className.startsWith("com.alipay.sdk.app.")
                || className.startsWith("com.alipay.android.app.")
                || className.contains("AlipaySdk")
                || className.contains("H5PayActivity"));
    }

    private static boolean hasPaymentSignal(Intent intent) {
        Uri data = intent.getData();
        if (isPaymentUri(data)) return true;
        String embedded = IntentUrlExtractor.findEmbeddedUrl(intent.getExtras());
        if (embedded != null) {
            try {
                if (isPaymentUri(Uri.parse(embedded))) return true;
            } catch (Throwable ignored) {
                // Continue with key inspection.
            }
        }
        Bundle extras = intent.getExtras();
        return containsPaymentKey(extras);
    }

    private static boolean isPaymentUri(Uri uri) {
        if (uri == null || uri.getScheme() == null) return false;
        String scheme = uri.getScheme();
        return "alipay".equalsIgnoreCase(scheme)
                || "alipays".equalsIgnoreCase(scheme)
                || "alipayqr".equalsIgnoreCase(scheme)
                || "alipayauth".equalsIgnoreCase(scheme)
                || "alipayre".equalsIgnoreCase(scheme)
                || "intent".equalsIgnoreCase(scheme);
    }

    private static boolean containsPaymentKey(Bundle extras) {
        if (extras == null) return false;
        for (String key : extras.keySet()) {
            Object value;
            try {
                value = extras.get(key);
            } catch (Throwable ignored) {
                continue;
            }
            String lower = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("orderinfo") || lower.contains("out_trade_no")
                    || lower.contains("trade_no") || lower.contains("tradeinfo")
                    || lower.contains("biz_content") || lower.contains("payment")
                    || lower.contains("alipay") || lower.equals("app_id")
                    || lower.equals("partnerid") || lower.equals("partner_id")) {
                return true;
            }
            if (value instanceof Bundle && containsPaymentKey((Bundle) value)) return true;
        }
        return false;
    }

    public static final class Candidate {
        public final String targetPackage;
        public final boolean prewarmOnly;
        public final String source;

        private Candidate(String targetPackage, boolean prewarmOnly, String source) {
            this.targetPackage = targetPackage;
            this.prewarmOnly = prewarmOnly;
            this.source = source;
        }

        private static Candidate launch(String targetPackage, String source) {
            return new Candidate(targetPackage, false, source);
        }

        private static Candidate prewarm(String targetPackage, String source) {
            return new Candidate(targetPackage, true, source);
        }
    }
}
