package com.huanchengfly.icebridge.android16;

import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves high-confidence URI schemes and app-link hosts when a target app is disabled. */
public final class CustomSchemeTargetResolver {
    private static final Map<String, String> SCHEME_TO_PACKAGE;
    private static final Map<String, String> BUILT_IN_HOST_TO_PACKAGE;

    static {
        Map<String, String> schemes = new LinkedHashMap<>();
        schemes.put("xhsdiscover", "com.xingin.xhs");
        schemes.put("alipays", "com.eg.android.AlipayGphone");
        schemes.put("alipay", "com.eg.android.AlipayGphone");
        schemes.put("instagram", "com.instagram.android");
        schemes.put("tg", "org.telegram.messenger");
        schemes.put("telegram", "org.telegram.messenger");
        schemes.put("vnd.youtube", "com.google.android.youtube");
        schemes.put("youtube", "com.google.android.youtube");
        schemes.put("twitter", "com.twitter.android");
        schemes.put("twitterauth", "com.twitter.android");
        schemes.put("x", "com.twitter.android");
        SCHEME_TO_PACKAGE = Collections.unmodifiableMap(schemes);

        // HTTP(S) routing is intentionally data-driven: online cache and manual rules only.
        // Custom URI schemes above remain stable compatibility mappings.
        BUILT_IN_HOST_TO_PACKAGE = Collections.emptyMap();
    }

    private CustomSchemeTargetResolver() {
    }

    public static String findTargetPackageForScheme(String scheme) {
        if (scheme == null) return null;
        return SCHEME_TO_PACKAGE.get(scheme.toLowerCase(Locale.ROOT));
    }

    /** Returns immutable default HTTPS host rules for the rule configuration screen. */
    public static Map<String, String> builtInHostMappings() {
        return BUILT_IN_HOST_TO_PACKAGE;
    }

    /**
     * Resolves only registered non-HTTP(S) URI schemes. This is deliberately separate from
     * app-link host resolution: callers that intercept a system Activity start must never treat
     * a normal HTTPS deep link as a direct scheme launch, or their own replay would recurse.
     */
    public static String findTargetPackageForCustomScheme(String scheme) {
        if (scheme == null || "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return null;
        }
        return findTargetPackageForScheme(scheme);
    }

    public static String findTargetPackageForCustomSchemeUri(Uri uri) {
        return uri == null ? null : findTargetPackageForCustomScheme(uri.getScheme());
    }

    /** Resolves payment/deep-link intents that are wrapped in Android's intent:// format. */
    public static Uri findCustomSchemeUri(Intent intent) {
        if (intent == null) return null;
        Uri data = intent.getData();
        Uri resolved = unwrapCustomSchemeUri(data);
        if (resolved != null) return resolved;
        String embedded = IntentUrlExtractor.findEmbeddedUrl(intent.getExtras());
        return unwrapCustomSchemeUri(embedded == null ? null : Uri.parse(embedded));
    }

    private static Uri unwrapCustomSchemeUri(Uri uri) {
        if (uri == null) return null;
        if (findTargetPackageForCustomSchemeUri(uri) != null) return uri;
        if (!"intent".equalsIgnoreCase(uri.getScheme())) return null;
        try {
            Intent nested = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
            Uri nestedData = nested.getData();
            return nestedData != null && findTargetPackageForCustomSchemeUri(nestedData) != null
                    ? nestedData : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Uses only exact domains or their legitimate subdomains; unknown HTTPS links are untouched. */
    public static String findTargetPackageForUri(String scheme, String host) {
        return findTargetPackageForUri(scheme, host, null);
    }

    /**
     * Resolves an HTTP(S) host only from the supplied runtime rule snapshot. There is deliberately
     * no bundled host fallback: an absent rule means the link is left untouched.
     */
    public static String findTargetPackageForUri(String scheme, String host, Map<String, String> savedMappings) {
        if (scheme == null) return null;
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
            return findTargetPackageForScheme(normalizedScheme);
        }
        return findHostMapping(normalizedHost, savedMappings);
    }

    private static String findHostMapping(String host, Map<String, String> mappings) {
        if (mappings == null || mappings.isEmpty()) return null;
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String root = entry.getKey();
            String packageName = entry.getValue();
            if (root != null && packageName != null && isHost(host, root)) return packageName;
        }
        return null;
    }

    /** Ordered host candidates for the structured rule snapshot. */
    public static List<String> findTargetPackagesForUri(String scheme, String host,
                                                          Map<String, List<String>> savedCandidates) {
        if (scheme == null) return Collections.emptyList();
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
            String target = findTargetPackageForScheme(normalizedScheme);
            return target == null ? Collections.emptyList() : Collections.singletonList(target);
        }
        String normalizedHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return findHostCandidates(normalizedHost, savedCandidates);
    }

    /** Compatibility helper for system paths that need one deterministic candidate. */
    public static String findFirstTargetPackageForUri(String scheme, String host,
                                                        Map<String, List<String>> savedCandidates) {
        List<String> values = findTargetPackagesForUri(scheme, host, savedCandidates);
        return values.isEmpty() ? null : values.get(0);
    }

    private static List<String> findHostCandidates(String host, Map<String, List<String>> mappings) {
        if (mappings == null || mappings.isEmpty()) return Collections.emptyList();
        for (Map.Entry<String, List<String>> entry : mappings.entrySet()) {
            if (entry.getKey() != null && isHost(host, entry.getKey()) && entry.getValue() != null && !entry.getValue().isEmpty()) {
                return Collections.unmodifiableList(new ArrayList<>(entry.getValue()));
            }
        }
        return Collections.emptyList();
    }

    /** Human-readable routing policy for legacy settings and diagnostics. */
    public static String builtInRulesDescription() {
        return "HTTP(S) \u57df\u540d\uff1a\u4ec5\u4f7f\u7528\u5728\u7ebf\u89c4\u5219\u6216\u624b\u52a8\u6dfb\u52a0\u89c4\u5219\u3002\n\n"
                + "\u81ea\u5b9a\u4e49 Scheme\uff1a\n"
                + "\u2022 \u5c0f\u7ea2\u4e66\uff1axhsdiscover\n"
                + "\u2022 \u652f\u4ed8\u5b9d\uff1aalipays\u3001alipay\n"
                + "\u2022 Instagram\uff1ainstagram\n"
                + "\u2022 Telegram\uff1atg\u3001telegram\n"
                + "\u2022 YouTube\uff1avnd.youtube\u3001youtube\n"
                + "\u2022 X\uff1atwitter\u3001twitterauth\u3001x";
    }
    private static boolean isHost(String host, String root) {
        return root.equals(host) || host.endsWith("." + root);
    }

    public static String findTargetPackage(Intent intent) {
        if (intent == null) return null;
        Uri data = intent.getData();
        if (data != null) {
            String target = findTargetPackageForUri(data.getScheme(), data.getHost());
            if (target != null) return target;
        }
        Uri custom = findCustomSchemeUri(intent);
        return custom == null ? null : findTargetPackageForCustomSchemeUri(custom);
    }
}
