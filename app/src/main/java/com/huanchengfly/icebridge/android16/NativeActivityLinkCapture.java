package com.huanchengfly.icebridge.android16;

import android.content.ComponentName;
import android.content.Intent;

/**
 * Recognizes URLs carried by the built-in browser activities of WeChat and QQ.
 * This class is intentionally pure: it does not touch package state, providers, IceBox, or root.
 */
public final class NativeActivityLinkCapture {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String WECHAT_WEBVIEW =
            "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI";
    private static final String QQ_PACKAGE = "com.tencent.mobileqq";
    private static final String QQ_BROWSER = "com.tencent.mobileqq.activity.QQBrowserActivity";

    private NativeActivityLinkCapture() {
    }

    /** Recognizes the carrier Activity without reading its private extras. */
    public static boolean isSupportedActivity(Intent intent) {
        return supportedSource(intent) != null;
    }

    public static CapturedLink capture(Intent intent) {
        String source = supportedSource(intent);
        if (source == null) return null;
        ComponentName component = intent.getComponent();
        String url = IntentUrlExtractor.findHttpUrl(intent);
        return url == null ? null : new CapturedLink(url, component.getPackageName(),
                component.getClassName(), source);
    }

    private static String supportedSource(Intent intent) {
        if (intent == null) {
            return null;
        }
        ComponentName component = intent.getComponent();
        if (component == null) {
            return null;
        }
        String packageName = component.getPackageName();
        String activityName = component.getClassName();
        if (WECHAT_PACKAGE.equals(packageName) && WECHAT_WEBVIEW.equals(activityName)) {
            return "wechat_webview";
        } else if (QQ_PACKAGE.equals(packageName) && QQ_BROWSER.equals(activityName)) {
            return "qq_browser";
        } else {
            return null;
        }
    }

    public static final class CapturedLink {
        public final String url;
        public final String sourcePackage;
        public final String sourceActivity;
        public final String source;

        private CapturedLink(String url, String sourcePackage, String sourceActivity, String source) {
            this.url = url;
            this.sourcePackage = sourcePackage;
            this.sourceActivity = sourceActivity;
            this.source = source;
        }
    }
}
