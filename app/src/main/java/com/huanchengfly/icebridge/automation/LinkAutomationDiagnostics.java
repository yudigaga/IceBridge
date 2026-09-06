package com.huanchengfly.icebridge.automation;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Small, privacy-conscious in-app trace for URL automation. */
public final class LinkAutomationDiagnostics {
    private static final String PREFS = "link_automation_diagnostics";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 100;

    private LinkAutomationDiagnostics() {
    }

    public static synchronized void record(Context context, String event, String url, String detail) {
        if (context == null) return;
        String current = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENTRIES, "");
        ArrayDeque<String> entries = new ArrayDeque<>();
        if (!TextUtils.isEmpty(current)) {
            for (String line : current.split("\\n")) {
                if (!TextUtils.isEmpty(line)) entries.addLast(line);
            }
        }
        String now = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        String entry = now + " " + safe(event) + " url=" + redactUrl(url)
                + (TextUtils.isEmpty(detail) ? "" : " detail=" + safe(detail));
        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) entries.removeFirst();
        StringBuilder saved = new StringBuilder();
        for (String line : entries) {
            if (saved.length() > 0) saved.append('\n');
            saved.append(line);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ENTRIES, saved.toString()).apply();
    }

    public static String dump(Context context) {
        if (context == null) return "";
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ENTRIES, "");
    }

    private static String redactUrl(String raw) {
        if (TextUtils.isEmpty(raw)) return "-";
        try {
            Uri uri = Uri.parse(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (TextUtils.isEmpty(scheme) || TextUtils.isEmpty(host)) return "[invalid]";
            String path = uri.getPath();
            if (path == null) path = "";
            if (path.length() > 120) path = path.substring(0, 120) + "?";
            return scheme + "://" + host + path + (uri.getQuery() == null ? "" : "?[redacted]");
        } catch (Throwable ignored) {
            return "[invalid]";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }
}
