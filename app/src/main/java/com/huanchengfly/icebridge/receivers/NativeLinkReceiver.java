package com.huanchengfly.icebridge.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.huanchengfly.icebridge.automation.LinkAutomationConfig;
import com.huanchengfly.icebridge.automation.LinkAutomationController;
import com.huanchengfly.icebridge.automation.LinkAutomationDiagnostics;

/** Receives lightweight URLs observed by the system-server hook and performs app-process work. */
public final class NativeLinkReceiver extends BroadcastReceiver {
    public static final String ACTION_NATIVE_LINK_CAPTURED =
            "com.huanchengfly.icebridge.action.NATIVE_LINK_CAPTURED";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_SOURCE_PACKAGE = "source_package";
    public static final String EXTRA_SOURCE_ACTIVITY = "source_activity";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_CAPTURED_AT = "captured_at";

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_NATIVE_LINK_CAPTURED.equals(intent.getAction())) {
            return;
        }
        String url = intent.getStringExtra(EXTRA_URL);
        String source = intent.getStringExtra(EXTRA_SOURCE);
        String sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE);
        boolean clipboard = "clipboard".equals(source);
        boolean sourceEnabled = clipboard
                ? LinkAutomationConfig.isClipboardEnabled(context)
                : LinkAutomationConfig.isNativeLinkCaptureEnabled(context);
        if (!sourceEnabled || !LinkAutomationConfig.isAutoOpenEnabled(context)) {
            LinkAutomationDiagnostics.record(context, "skip", url,
                    (clipboard ? "CLIPBOARD_DISABLED" : "NATIVE_CAPTURE_DISABLED"));
            return;
        }
        PendingResult pending = goAsync();
        LinkAutomationDiagnostics.record(context, clipboard ? "clipboard_url_captured" : "activity_url_captured", url,
                "source=" + (source == null ? "unknown" : source));
        LinkAutomationController.handle(context, url,
                clipboard ? "system_clipboard" : "native_activity:" + source,
                clipboard ? "*" : sourcePackage, true, pending::finish);
    }
}
