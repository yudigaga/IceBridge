package com.huanchengfly.icebridge.android16;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Extracts URLs embedded in common serialized Intent extras without depending on a host app. */
public final class IntentUrlExtractor {
    private static final String[] MARKERS = {"S.rawUrl=", "S.url=", "rawUrl=", "url="};
    // Extras can be supplied by another application. Keep parsing bounded so a malformed or
    // cyclic Parcelable graph cannot consume unbounded system_server/app time or memory.
    private static final int MAX_BUNDLE_DEPTH = 8;
    private static final int MAX_SCAN_NODES = 256;
    private static final int MAX_VALUE_LENGTH = 8192;

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
        ScanState state = new ScanState();
        return findEmbeddedUrl(bundle, 0, state);
    }

    private static String findEmbeddedUrl(Bundle bundle, int depth, ScanState state) {
        if (bundle == null || depth > MAX_BUNDLE_DEPTH || !state.visit(bundle)) {
            return null;
        }
        for (String key : bundle.keySet()) {
            if (!state.consumeNode()) {
                return null;
            }
            Object value;
            try {
                value = bundle.get(key);
            } catch (Throwable ignored) {
                continue;
            }
            if (value instanceof Bundle) {
                String nested = findEmbeddedUrl((Bundle) value, depth + 1, state);
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
        if (isEmpty(value) || value.length() > MAX_VALUE_LENGTH) {
            return null;
        }
        String candidate = value.trim();
        for (int round = 0; round < 3; round++) {
            String extracted = extractMarkedValue(candidate);
            if (extracted != null) {
                candidate = extracted.trim();
            }
            if (candidate.length() > MAX_VALUE_LENGTH) {
                return null;
            }
            // Do not URL-decode an already plain URL: is valid URL data and URLDecoder would
            // incorrectly turn its plus signs into spaces.
            if (isSupportedUrl(candidate)) {
                return candidate;
            }
            String decoded = decodeComponent(candidate);
            if (decoded.equals(candidate)) {
                break;
            }
            candidate = decoded.trim();
        }
        return candidate.length() <= MAX_VALUE_LENGTH && isSupportedUrl(candidate) ? candidate : null;
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

    private static final class ScanState {
        private int nodes;
        private final Set<Bundle> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        boolean visit(Bundle bundle) {
            return visited.add(bundle);
        }

        boolean consumeNode() {
            return ++nodes <= MAX_SCAN_NODES;
        }
    }
}
