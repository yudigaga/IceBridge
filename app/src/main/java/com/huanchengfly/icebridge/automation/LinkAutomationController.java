package com.huanchengfly.icebridge.automation;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import com.huanchengfly.icebridge.android16.CustomSchemeTargetResolver;
import com.huanchengfly.icebridge.engines.BaseEngine;
import com.huanchengfly.icebridge.engines.EngineManager;
import com.huanchengfly.icebridge.engines.RootCommandRunner;
import com.huanchengfly.icebridge.engines.RootEngine;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-process coordinator for URLs received from native activity observation or clipboard. */
public final class LinkAutomationController {
    public interface Completion { void onFinished(); }
    private static final ExecutorService SHORT_LINK_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "IceBridge-short-links"));

    private LinkAutomationController() {
    }

    public static void handle(Context context, String rawUrl, String source, boolean background,
                              Completion completion) {
        handle(context, rawUrl, source, "*", background, completion);
    }

    /** Handles a link with the package that initiated it. Exact source rules are preferred over wildcard rules. */
    public static void handle(Context context, String rawUrl, String source, String sourcePackage,
                              boolean background, Completion completion) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) {
            finish(completion);
            return;
        }
        if (!LinkAutomationConfig.isAutoOpenEnabled(app)) {
            LinkAutomationDiagnostics.record(app, "skip", rawUrl, "AUTO_OPEN_DISABLED source=" + source);
            finish(completion);
            return;
        }
        Uri uri = parseWebUri(rawUrl);
        if (uri == null) {
            LinkAutomationDiagnostics.record(app, "reject", rawUrl, "UNSUPPORTED_URL source=" + source);
            finish(completion);
            return;
        }
        String target = resolveTarget(app, uri, sourcePackage);
        if (!LinkAutomationConfig.isPackage(target)) {
            LinkAutomationDiagnostics.record(app, "skip", uri.toString(), "UNKNOWN_TARGET source=" + source);
            finish(completion);
            return;
        }
        ApplicationInfo info = getApplicationInfo(app, target);
        BaseEngine engine = EngineManager.getEngine(app);
        if (info == null) {
            // IceBox may make a frozen package invisible to this app even with disabled/uninstalled
            // package flags. The URL-to-package mapping is already allow-listed, so delegate the
            // package existence/state decision to IceBox/root instead of incorrectly reporting it
            // as uninstalled. This path never runs for arbitrary URL hosts.
            restoreHiddenTarget(app, uri, target, background, source, engine, completion);
            return;
        }
        if (!isInstalled(info)) {
            LinkAutomationDiagnostics.record(app, "skip", uri.toString(),
                    "TARGET_NOT_INSTALLED target=" + target + " source=" + source);
            fallback(app, uri, source, completion);
            return;
        }
        boolean frozen = !engine.isAppEnabled(info);
        // onlyFrozenLinkTargets controls whether thaw is needed; it must never suppress
        // launching an already-enabled target. A user may tap the same URL repeatedly.
        if (!frozen) {
            launch(app, uri, target, background, source, completion);
            return;
        }
        if (engine.checkPermission() != PackageManager.PERMISSION_GRANTED) {
            LinkAutomationDiagnostics.record(app, "fail", uri.toString(), "ENGINE_PERMISSION_DENIED target=" + target);
            fallback(app, uri, source, completion);
            return;
        }
        LinkAutomationDiagnostics.record(app, "thaw", uri.toString(), "target=" + target + " source=" + source);
        try {
            engine.setEnabled(target, true, new BaseEngine.Callback() {
                @Override public void onSuccess() {
                    LinkAutomationDiagnostics.record(app, "thaw_ok", uri.toString(), "target=" + target);
                    launch(app, uri, target, background, source, completion);
                }
                @Override public void onFailure() {
                    LinkAutomationDiagnostics.record(app, "fail", uri.toString(), "THAW_FAILED target=" + target);
                    fallback(app, uri, source, completion);
                }
            });
        } catch (Throwable error) {
            LinkAutomationDiagnostics.record(app, "fail", uri.toString(), "THAW_EXCEPTION=" + error.getClass().getSimpleName());
            fallback(app, uri, source, completion);
        }
    }

    private static void restoreHiddenTarget(Context app, Uri uri, String target, boolean background,
                                            String source, BaseEngine engine, Completion completion) {
        if (engine.checkPermission() != PackageManager.PERMISSION_GRANTED) {
            LinkAutomationDiagnostics.record(app, "fail", uri.toString(),
                    "HIDDEN_TARGET_PERMISSION_DENIED target=" + target);
            fallback(app, uri, source, completion);
            return;
        }
        LinkAutomationDiagnostics.record(app, "thaw", uri.toString(),
                "target=" + target + " source=" + source + " state=package_hidden");
        try {
            engine.setEnabled(target, true, new BaseEngine.Callback() {
                @Override public void onSuccess() {
                    ApplicationInfo restored = getApplicationInfo(app, target);
                    LinkAutomationDiagnostics.record(app, "thaw_ok", uri.toString(),
                            "target=" + target + (restored == null ? " visibility=pending" : ""));
                    launch(app, uri, target, background, source, completion);
                }

                @Override public void onFailure() {
                    LinkAutomationDiagnostics.record(app, "fail", uri.toString(),
                            "HIDDEN_TARGET_THAW_FAILED target=" + target);
                    fallback(app, uri, source, completion);
                }
            });
        } catch (Throwable error) {
            LinkAutomationDiagnostics.record(app, "fail", uri.toString(),
                    "HIDDEN_TARGET_THAW_EXCEPTION=" + error.getClass().getSimpleName());
            fallback(app, uri, source, completion);
        }
    }

    private static void launch(Context app, Uri uri, String target, boolean background, String source,
                               Completion completion) {
        // b23.tv is a web redirect. Resolve only this known Bilibili short-link host off the main
        // thread so the official Bilibili App receives its final bilibili.com URL directly.
        if (isBilibiliShortLink(uri)) {
            SHORT_LINK_EXECUTOR.execute(() -> launchResolved(app, expandBilibiliShortLink(app, uri),
                    target, background, source, completion));
        } else {
            launchResolved(app, uri, target, background, source, completion);
        }
    }

    private static void launchResolved(Context app, Uri uri, String target, boolean background, String source,
                                       Completion completion) {
        if (background && EngineManager.getEngine(app) instanceof RootEngine) {
            String command = "am start --user 0 -a android.intent.action.VIEW -d "
                    + shellQuote(uri.toString()) + " -p " + shellQuote(target);
            RootCommandRunner.execute(command, result -> {
                if (result.isSuccessful()) {
                    LinkAutomationDiagnostics.record(app, "open_ok", uri.toString(), "target=" + target + " source=" + source + " via=root");
                    finish(completion);
                } else {
                    LinkAutomationDiagnostics.record(app, "open_retry", uri.toString(), "ROOT_AM_FAILED target=" + target);
                    launchFromApp(app, uri, target, source, completion);
                }
            });
        } else {
            launchFromApp(app, uri, target, source, completion);
        }
    }

    private static boolean isBilibiliShortLink(Uri uri) {
        return uri != null && "b23.tv".equalsIgnoreCase(uri.getHost());
    }

    /** Follows at most five HTTPS redirects and only accepts Bilibili-owned final hosts. */
    private static Uri expandBilibiliShortLink(Context context, Uri original) {
        Uri current = original;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(current.toString()).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(6_000);
                connection.setReadTimeout(6_000);
                connection.setRequestProperty("User-Agent", "IceBridge-safe-link/1");
                int code = connection.getResponseCode();
                if (code < 300 || code > 399) break;
                String location = connection.getHeaderField("Location");
                if (TextUtils.isEmpty(location)) break;
                Uri next = Uri.parse(location);
                if (next.getScheme() == null) next = current.buildUpon().encodedPath(location).build();
                if (!"https".equalsIgnoreCase(next.getScheme()) && !"http".equalsIgnoreCase(next.getScheme())) break;
                current = next;
            } catch (IOException | RuntimeException error) {
                LinkAutomationDiagnostics.record(context, "short_link_expand_failed", original.toString(),
                        error.getClass().getSimpleName());
                break;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        String host = current.getHost() == null ? "" : current.getHost().toLowerCase(Locale.ROOT);
        if ("b23.tv".equals(host) || "bilibili.com".equals(host) || host.endsWith(".bilibili.com")) {
            if (!current.equals(original)) {
                LinkAutomationDiagnostics.record(context, "short_link_expanded", current.toString(), "source=b23.tv");
            }
            return current;
        }
        LinkAutomationDiagnostics.record(context, "short_link_expand_rejected", original.toString(), "host=" + host);
        return original;
    }

    private static void launchFromApp(Context app, Uri uri, String target, String source,
                                      Completion completion) {
        try {
            Uri handoffUri = FluidCloudRuleCatalog.rewriteForTarget(uri, target);
            Intent launch = new Intent(Intent.ACTION_VIEW, handoffUri);
            launch.setPackage(target);
            if (!uri.equals(handoffUri)) {
                LinkAutomationDiagnostics.record(app, "rule_rewrite", uri.toString(),
                        "target=" + target + " source=fluid_catalog");
            }
            launch.addCategory(Intent.CATEGORY_BROWSABLE);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            app.startActivity(launch);
            LinkAutomationDiagnostics.record(app, "open_ok", uri.toString(),
                    "target=" + target + " source=" + source);
            finish(completion);
            return;
        } catch (Throwable error) {
            LinkAutomationDiagnostics.record(app, "open_failed", uri.toString(),
                    error.getClass().getSimpleName() + " target=" + target);
        }
        fallback(app, uri, source, completion);
    }

    private static void fallback(Context app, Uri uri, String source, Completion completion) {
        if (LinkAutomationConfig.isBrowserFallbackEnabled(app)) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, uri);
                fallback.addCategory(Intent.CATEGORY_BROWSABLE);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                app.startActivity(fallback);
                LinkAutomationDiagnostics.record(app, "fallback", uri.toString(), "browser source=" + source);
            } catch (Throwable error) {
                LinkAutomationDiagnostics.record(app, "fail", uri.toString(), "FALLBACK=" + error.getClass().getSimpleName());
            }
        }
        finish(completion);
    }

    private static Uri parseWebUri(String raw) {
        if (TextUtils.isEmpty(raw) || raw.length() > 8192) return null;
        try {
            Uri uri = Uri.parse(raw.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return null;
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
            return uri;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String resolveTarget(Context context, Uri uri, String sourcePackage) {
        // A host can have multiple valid clients. Prefer the first installed candidate while
        // retaining the rule order selected in the configuration snapshot.
        for (String candidate : CustomSchemeTargetResolver.findTargetPackagesForUri(uri.getScheme(), uri.getHost(),
                RuleConfigRepository.effectiveHostCandidates(context, sourcePackage))) {
            ApplicationInfo info = getApplicationInfo(context, candidate);
            if (isInstalled(info)) return candidate;
        }
        return null;
    }

    private static boolean isInstalled(ApplicationInfo info) {
        return info != null && (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
    }

    private static ApplicationInfo getApplicationInfo(Context context, String packageName) {
        try {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS
                    : PackageManager.GET_UNINSTALLED_PACKAGES | PackageManager.GET_DISABLED_COMPONENTS;
            return context.getPackageManager().getApplicationInfo(packageName, flags);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
    }

    private static void finish(Completion completion) {
        if (completion != null) completion.onFinished();
    }
}
