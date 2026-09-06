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

    public static CapturedLink capture(Intent intent) {
        if (intent == null) {
            return null;
        }
        ComponentName component = intent.getComponent();
        if (component == null) {
            return null;
        }
        String packageName = component.getPackageName();
        String activityName = component.getClassName();
        String source;
        if (WECHAT_PACKAGE.equals(packageName) && WECHAT_WEBVIEW.equals(activityName)) {
            source = "wechat_webview";
        } else if (QQ_PACKAGE.equals(packageName) && QQ_BROWSER.equals(activityName)) {
            source = "qq_browser";
        } else {
            return null;
        }
        String url = IntentUrlExtractor.findHttpUrl(intent);
        return url == null ? null : new CapturedLink(url, packageName, activityName, source);
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
