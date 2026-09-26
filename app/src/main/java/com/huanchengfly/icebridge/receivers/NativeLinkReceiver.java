package com.huanchengfly.icebridge.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.annotation.SuppressLint;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import com.huanchengfly.icebridge.automation.LinkAutomationConfig;
import com.huanchengfly.icebridge.automation.LinkAutomationController;
import com.huanchengfly.icebridge.automation.LinkAutomationDiagnostics;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/** Receives lightweight URLs observed by the system-server hook and performs app-process work. */
public final class NativeLinkReceiver extends BroadcastReceiver {
    public static final String ACTION_NATIVE_LINK_CAPTURED =
            "io.github.yudigaga.icebridge.action.NATIVE_LINK_CAPTURED";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_SOURCE_PACKAGE = "source_package";
    public static final String EXTRA_SOURCE_ACTIVITY = "source_activity";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_CAPTURED_AT = "captured_at";
    private static final int MAX_URL_LENGTH = 8192;
    private static final long MAX_EVENT_AGE_MILLIS = 2 * 60_000L;
    private static final long ASYNC_TIMEOUT_MILLIS = 10_000L;
    private static final String APP_PACKAGE = "io.github.yudigaga.icebridge";
    private static final String RECEIVER_CLASS =
            "com.huanchengfly.icebridge.receivers.NativeLinkReceiver";
    private static final String SOURCE_CLIPBOARD = "clipboard";
    private static final String SOURCE_WECHAT_WEBVIEW = "wechat_webview";
    private static final String SOURCE_QQ_BROWSER = "qq_browser";
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String WECHAT_ACTIVITY =
            "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI";
    private static final String QQ_PACKAGE = "com.tencent.mobileqq";
    private static final String QQ_ACTIVITY =
            "com.tencent.mobileqq.activity.QQBrowserActivity";

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_NATIVE_LINK_CAPTURED.equals(intent.getAction())
                || Binder.getCallingUid() != Process.SYSTEM_UID || !isOwnComponent(intent)
                || getSendingUserIdCompat() < 0) {
            return;
        }
        String url = intent.getStringExtra(EXTRA_URL);
        String source = intent.getStringExtra(EXTRA_SOURCE);
        String sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE);
        String sourceActivity = intent.getStringExtra(EXTRA_SOURCE_ACTIVITY);
        int userId = intent.getIntExtra(EXTRA_USER_ID, -1);
        long capturedAt = intent.getLongExtra(EXTRA_CAPTURED_AT, 0L);
        if (!isValidPayload(url, source, sourcePackage, sourceActivity, userId, capturedAt,
                System.currentTimeMillis())) {
            LinkAutomationDiagnostics.record(context, "reject", null, "INVALID_NATIVE_EVENT");
            return;
        }
        if (getSendingUserIdCompat() != userId) {
            LinkAutomationDiagnostics.record(context, "reject", url, "USER_MISMATCH");
            return;
        }
        boolean clipboard = SOURCE_CLIPBOARD.equals(source);
        boolean sourceEnabled = clipboard
                ? LinkAutomationConfig.isClipboardEnabled(context)
                : LinkAutomationConfig.isNativeLinkCaptureEnabled(context);
        if (!sourceEnabled || !LinkAutomationConfig.isAutoOpenEnabled(context)) {
            LinkAutomationDiagnostics.record(context, "skip", url,
                    (clipboard ? "CLIPBOARD_DISABLED" : "NATIVE_CAPTURE_DISABLED"));
            return;
        }
        PendingResult pending = goAsync();
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                pending.finish();
            }
        };
        new Handler(Looper.getMainLooper()).postDelayed(finish, ASYNC_TIMEOUT_MILLIS);
        LinkAutomationDiagnostics.record(context, clipboard ? "clipboard_url_captured" : "activity_url_captured", url,
                "source=" + source + " user=" + userId);
        try {
            LinkAutomationController.handle(context, url,
                    clipboard ? "system_clipboard" : "native_activity:" + source,
                    clipboard ? "*" : sourcePackage, true, finish::run);
        } catch (Throwable error) {
            LinkAutomationDiagnostics.record(context, "fail", url,
                    "NATIVE_RECEIVER_EXCEPTION=" + error.getClass().getSimpleName());
            finish.run();
        }
    }

    private static boolean isOwnComponent(Intent intent) {
        return intent.getComponent() != null
                && APP_PACKAGE.equals(intent.getComponent().getPackageName())
                && RECEIVER_CLASS.equals(intent.getComponent().getClassName());
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private int getSendingUserIdCompat() {
        try {
            Method method = BroadcastReceiver.class.getDeclaredMethod("getSendingUserId");
            method.setAccessible(true);
            Object value = method.invoke(this);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable error) {
            return -1;
        }
    }

    static boolean isValidPayload(String url, String source, String sourcePackage,
                                  String sourceActivity, int userId, long capturedAt, long now) {
        if (!isValidUrl(url) || !isValidSource(source) || !isValidUserId(userId)
                || !isFresh(capturedAt, now)) {
            return false;
        }
        if (SOURCE_CLIPBOARD.equals(source)) {
            return (sourcePackage == null || isValidPackageName(sourcePackage))
                    && (sourceActivity == null || isValidActivityName(sourceActivity));
        }
        if (!isValidPackageName(sourcePackage) || !isValidActivityName(sourceActivity)) {
            return false;
        }
        return SOURCE_WECHAT_WEBVIEW.equals(source)
                ? WECHAT_PACKAGE.equals(sourcePackage) && WECHAT_ACTIVITY.equals(sourceActivity)
                : QQ_PACKAGE.equals(sourcePackage) && QQ_ACTIVITY.equals(sourceActivity);
    }

    static boolean isValidUrl(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_URL_LENGTH || !url.equals(url.trim())) {
            return false;
        }
        try {
            URI parsed = new URI(url);
            String scheme = parsed.getScheme();
            return scheme != null && parsed.getHost() != null && !parsed.getHost().isEmpty()
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean isValidSource(String source) {
        return SOURCE_CLIPBOARD.equals(source) || SOURCE_WECHAT_WEBVIEW.equals(source)
                || SOURCE_QQ_BROWSER.equals(source);
    }

    static boolean isValidPackageName(String packageName) {
        return packageName != null
                && packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }

    static boolean isValidActivityName(String activityName) {
        return activityName != null && activityName.matches(
                "[A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)+");
    }

    static boolean isValidUserId(int userId) {
        return userId >= 0 && userId <= 999;
    }

    static boolean isFresh(long capturedAt, long now) {
        return capturedAt > 0 && now > 0
                && Math.abs(now - capturedAt) <= MAX_EVENT_AGE_MILLIS;
    }
}
