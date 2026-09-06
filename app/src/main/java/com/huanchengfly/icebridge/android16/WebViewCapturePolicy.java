package com.huanchengfly.icebridge.android16;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Conservative, side-effect-free filter for public WebView navigation callbacks. */
public final class WebViewCapturePolicy {
    public static final long CACHE_MILLIS = 3000L;
    public static final long EVENT_DEDUPLICATION_MILLIS = 500L;
    private static final int MAX_URL_LENGTH = 8192;
    private static final Map<String, Long> RECENT = new ConcurrentHashMap<>();

    private WebViewCapturePolicy() {}

    public static Navigation inspect(String webViewKey, String sourcePackage, String rawUrl,
                                     boolean mainFrame, boolean getRequest,
                                     Map<String, ?> hostRules) {
        String url = normalize(rawUrl);
        if (!mainFrame || !getRequest || url == null) return Navigation.skip(rawUrl, "not-main-get-or-invalid");
        URI uri;
        try { uri = URI.create(url); } catch (Throwable e) { return Navigation.skip(rawUrl, "malformed"); }
        String scheme = lower(uri.getScheme());
        String host = lower(uri.getHost());
        if (!("http".equals(scheme) || "https".equals(scheme)) || host == null || host.isEmpty()) {
            return Navigation.skip(url, "unsupported-scheme-or-host");
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (containsSensitive(lower)) return Navigation.skip(url, "sensitive-navigation");
        String target = findTarget(host, hostRules);
        if (target == null) return Navigation.skip(url, "rule-miss");
        String key = safe(webViewKey) + "|" + safe(sourcePackage) + "|" + url + "|" + (mainFrame ? "main" : "sub");
        long now = System.currentTimeMillis();
        Long previous = RECENT.put(key, now);
        if (previous != null && now - previous < EVENT_DEDUPLICATION_MILLIS) {
            return Navigation.skip(url, "duplicate");
        }
        if (RECENT.size() > 256) RECENT.entrySet().removeIf(e -> now - e.getValue() > EVENT_DEDUPLICATION_MILLIS * 4);
        return Navigation.capture(url, host, target);
    }

    /** Privacy-safe URL representation for LSPosed/module diagnostics. */
    public static String redactForLog(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "-";
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isEmpty()) return "[invalid]";
            String path = uri.getPath();
            if (path == null) path = "";
            if (path.length() > 120) path = path.substring(0, 120) + "?";
            return scheme + "://" + host + path
                    + (uri.getQuery() == null ? "" : "?[redacted]");
        } catch (Throwable ignored) {
            return "[invalid]";
        }
    }

    public static String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_URL_LENGTH) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("intent:") || lower.startsWith("market:") || lower.startsWith("alipay:")
                || lower.startsWith("alipays:") || lower.startsWith("alipayqr:") || lower.startsWith("file:")
                || lower.startsWith("javascript:") || lower.startsWith("data:")) return null;
        return value;
    }

    private static boolean containsSensitive(String value) {
        // Do not reject arbitrary tracking/security parameters such as xsec_token,
        // which are part of normal share URLs (for example Xiaohongshu). Match only
        // navigation endpoints and parameter names that strongly indicate a flow
        // which must remain inside the original WebView.
        String[] pathTokens = {"/payment", "/pay/", "/oauth", "/auth", "/callback", "/redirect",
                "/return", "/order", "/login"};
        for (String token : pathTokens) if (value.contains(token)) return true;
        String[] queryTokens = {"payment=", "pay=", "oauth=", "auth=", "callback=", "redirect=",
                "redirect_uri=", "return_url=", "order=", "login=", "payment_id=", "order_id="};
        for (String token : queryTokens) {
            if (value.contains("?" + token) || value.contains("&" + token)) return true;
        }
        return false;
    }

    private static String findTarget(String host, Map<String, ?> rules) {
        if (rules == null || rules.isEmpty()) return null;
        String best = null;
        int length = -1;
        for (Map.Entry<String, ?> entry : rules.entrySet()) {
            String rule = lower(entry.getKey());
            if (rule == null || rule.isEmpty()) continue;
            if ((host.equals(rule) || host.endsWith("." + rule)) && rule.length() > length) {
                Object value = entry.getValue();
                if (value instanceof String && !((String) value).trim().isEmpty()) {
                    best = ((String) value).trim();
                    length = rule.length();
                } else if (value instanceof Iterable) {
                    for (Object candidate : (Iterable<?>) value) {
                        if (candidate instanceof String && !((String) candidate).trim().isEmpty()) {
                            best = ((String) candidate).trim();
                            length = rule.length();
                            break;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static String lower(String s) { return s == null ? null : s.toLowerCase(Locale.ROOT); }
    private static String safe(String s) { return s == null ? "" : s; }

    public static final class Navigation {
        public final boolean capture; public final String url; public final String host; public final String target; public final String reason;
        private Navigation(boolean c, String u, String h, String t, String r) { capture=c; url=u; host=h; target=t; reason=r; }
        static Navigation capture(String u, String h, String t) { return new Navigation(true,u,h,t,"matched"); }
        static Navigation skip(String u, String r) { return new Navigation(false,u,null,null,r); }
    }
}
