package com.huanchengfly.icebridge.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.huanchengfly.icebridge.R;
import com.huanchengfly.icebridge.automation.LinkAutomationConfig;
import com.huanchengfly.icebridge.automation.LinkAutomationController;
import com.huanchengfly.icebridge.automation.LinkAutomationDiagnostics;

/** Optional foreground service; clipboard automation is opt-in because it may open apps after copy. */
public final class ClipboardLinkService extends Service {
    private static final String CHANNEL = "icebridge_link_monitor";
    private static final int NOTIFICATION_ID = 8101;
    // Android 16+ has the system_server clipboard observer in IceBridgeModule.
    // Keep the app-side service as a compatibility fallback on older releases only.
    private static final int SYSTEM_SERVER_CLIPBOARD_OWNER_API = 35;
    private static final long APP_CLIPBOARD_DEDUPLICATION_MILLIS = 1_500L;
    private ClipboardManager clipboard;
    private ClipboardManager.OnPrimaryClipChangedListener listener;
    private String lastHandledClipboardUrl;
    private long lastHandledClipboardAt;

    public static void update(Context context) {
        Intent intent = new Intent(context, ClipboardLinkService.class);
        if (isSystemServerClipboardOwner()) {
            // Do not create a second listener in the app process. The system_server hook owns
            // clipboard observation on Android 16+, including dispatch and target selection.
            try {
                context.stopService(intent);
                LinkAutomationDiagnostics.record(context, "clipboard_service_skipped", null,
                        "system_server_owner api=" + Build.VERSION.SDK_INT);
            } catch (Throwable error) {
                LinkAutomationDiagnostics.record(context, "fail", null,
                        "CLIPBOARD_SERVICE_STOP=" + error.getClass().getSimpleName());
            }
            return;
        }
        if (LinkAutomationConfig.isClipboardEnabled(context)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
                else context.startService(intent);
            } catch (Throwable error) {
                LinkAutomationDiagnostics.record(context, "fail", null, "CLIPBOARD_SERVICE=" + error.getClass().getSimpleName());
            }
        } else context.stopService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        LinkAutomationDiagnostics.record(this, "clipboard_service_start_requested", null, "onCreate");
        if (isSystemServerClipboardOwner()) {
            // Covers a service instance started before preferences/process state changed.
            LinkAutomationDiagnostics.record(this, "clipboard_listener_skipped", null,
                    "system_server_owner api=" + Build.VERSION.SDK_INT);
            stopSelf();
            return;
        }
        createChannel();
        startForeground(NOTIFICATION_ID, notification());
        clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        listener = this::onClipboardChanged;
        if (clipboard != null) {
            clipboard.addPrimaryClipChangedListener(listener);
            LinkAutomationDiagnostics.record(this, "clipboard_listener_started", null, "ok");
        } else {
            LinkAutomationDiagnostics.record(this, "clipboard_service_start_failed", null, "manager_unavailable");
        }
    }

    private void onClipboardChanged() {
        if (!LinkAutomationConfig.isClipboardEnabled(this) || clipboard == null) return;
        LinkAutomationDiagnostics.record(this, "clipboard_changed", null, "listener");
        try {
            ClipData data = clipboard.getPrimaryClip();
            String raw = extractClipText(data);
            String url = raw == null ? null : com.huanchengfly.icebridge.android16.IntentUrlExtractor.extractHttpUrl(raw);
            if (url == null) {
                LinkAutomationDiagnostics.record(this, "clipboard_ignored_invalid", raw, "NO_HTTP_URL");
                return;
            }
            if (!claimClipboardUrl(url)) {
                LinkAutomationDiagnostics.record(this, "clipboard_coalesced", url,
                        "service_duplicate_window");
                return;
            }
            LinkAutomationDiagnostics.record(this, "clipboard_url_detected", url, "service");
            LinkAutomationController.handle(this, url, "clipboard", true, null);
        } catch (Throwable error) {
            LinkAutomationDiagnostics.record(this, "fail", null, "CLIPBOARD_READ=" + error.getClass().getSimpleName());
        }
    }

    private String extractClipText(ClipData data) {
        if (data == null || data.getItemCount() == 0 || data.getItemAt(0) == null) return null;
        ClipData.Item item = data.getItemAt(0);
        CharSequence text = item.getText();
        if (text != null && text.length() > 0) return text.toString().trim();
        CharSequence html = item.getHtmlText();
        if (html != null && html.length() > 0) return html.toString().trim();
        Uri uri = item.getUri();
        if (uri != null) return uri.toString();
        CharSequence coerced = item.coerceToText(this);
        return coerced == null ? null : coerced.toString().trim();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (isSystemServerClipboardOwner() || !LinkAutomationConfig.isClipboardEnabled(this)) stopSelf();
        return START_NOT_STICKY;
    }

    private synchronized boolean claimClipboardUrl(String url) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (url.equals(lastHandledClipboardUrl)
                && now - lastHandledClipboardAt < APP_CLIPBOARD_DEDUPLICATION_MILLIS) {
            return false;
        }
        lastHandledClipboardUrl = url;
        lastHandledClipboardAt = now;
        return true;
    }

    private static boolean isSystemServerClipboardOwner() {
        return Build.VERSION.SDK_INT >= SYSTEM_SERVER_CLIPBOARD_OWNER_API;
    }

    @Override public void onDestroy() {
        if (clipboard != null && listener != null) clipboard.removePrimaryClipChangedListener(listener);
        LinkAutomationDiagnostics.record(this, "clipboard_service_destroyed", null, "onDestroy");
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.clipboard_service_channel), NotificationManager.IMPORTANCE_MIN);
            channel.setDescription(getString(R.string.clipboard_service_description));
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification notification() {
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.clipboard_service_title))
                .setContentText(getString(R.string.clipboard_service_description))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }
}
