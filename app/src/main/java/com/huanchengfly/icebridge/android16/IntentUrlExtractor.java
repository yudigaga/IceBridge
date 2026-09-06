package com.huanchengfly.icebridge.android16;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/** Extracts URLs embedded in common serialized Intent extras without depending on a host app. */
public final class IntentUrlExtractor {
    private static final String[] MARKERS = {"S.rawUrl=", "S.url=", "rawUrl=", "url="};

    private IntentUrlExtractor() {
    }

    public static Intent promoteToData(Intent original) {
        if (original == null || original.getData() != null) {
            return original;
        }
        String embedded = findEmbeddedUrl(original.getExtras());
        if (isEmpty(embedded)) {
            return original;
        }
        Intent copy = new Intent(original);
        copy.setData(Uri.parse(embedded));
        return copy;
    }

    /**
     * Returns only an HTTP(S) URL suitable for the system-side activity observer.
     * This deliberately excludes intent:// and market:// values so observing a WebView launch
     * can never turn into a generic system intent interceptor.
     */
    public static String findHttpUrl(Intent intent) {
        if (intent == null) {
            return null;
        }
        Uri data = intent.getData();
        String candidate = data == null ? null : httpUrl(data.toString());
        if (candidate != null) {
            return candidate;
        }
        String[] directKeys = {"rawUrl", "url", Intent.EXTRA_TEXT};
        for (String key : directKeys) {
            candidate = httpUrl(intent.getStringExtra(key));
            if (candidate != null) {
                return candidate;
            }
        }
        candidate = httpUrl(findEmbeddedUrl(intent.getExtras()));
        if (candidate != null) {
            return candidate;
        }
        try {
            return httpUrl(intent.toUri(Intent.URI_INTENT_SCHEME));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String httpUrl(String value) {
        return extractHttpUrl(value);
    }

    /** Parses a serialized value but returns it only when it is HTTP(S). */
    public static String extractHttpUrl(String value) {
        String candidate = extractUrl(value);
        return candidate != null && (candidate.startsWith("http://")
                || candidate.startsWith("https://")) ? candidate : null;
    }

    public static String findEmbeddedUrl(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        for (String key : bundle.keySet()) {
            Object value;
            try {
                value = bundle.get(key);
            } catch (Throwable ignored) {
                continue;
            }
            if (value instanceof Bundle) {
                String nested = findEmbeddedUrl((Bundle) value);
                if (!isEmpty(nested)) {
                    return nested;
                }
            } else if (value instanceof String) {
                String candidate = extractUrl((String) value);
                if (!isEmpty(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public static String extractUrl(String value) {
        if (isEmpty(value)) {
            return null;
        }
        String candidate = value.trim();
        for (int round = 0; round < 3; round++) {
            String extracted = extractMarkedValue(candidate);
            if (extracted != null) {
                candidate = extracted.trim();
            }
            // Do not URL-decode an already plain URL:  is valid URL data and
            // URLDecoder would incorrectly turn it into a space.
            if (isSupportedUrl(candidate)) {
                return candidate;
            }
            String decoded = decodeComponent(candidate);
            if (decoded.equals(candidate)) {
                break;
            }
            candidate = decoded.trim();
        }
        return isSupportedUrl(candidate) ? candidate : null;
    }

    private static String decodeComponent(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
            return value;
        }
    }
    private static String extractMarkedValue(String value) {
        for (String marker : MARKERS) {
            int start = value.indexOf(marker);
            if (start >= 0) {
                String candidate = value.substring(start + marker.length());
                int end = candidate.indexOf(';');
                return end > 0 ? candidate.substring(0, end) : candidate;
            }
        }
        return value;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }

    private static boolean isSupportedUrl(String value) {
        return !isEmpty(value)
                && (value.startsWith("http://") || value.startsWith("https://")
                || value.startsWith("market://") || value.startsWith("intent://")
                || value.startsWith("alipay://") || value.startsWith("alipays://")
                || value.startsWith("alipayqr://") || value.startsWith("alipayauth://")
                || value.startsWith("alipayre://"));
    }
}
