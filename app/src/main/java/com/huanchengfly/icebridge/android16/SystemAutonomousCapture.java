package com.huanchengfly.icebridge.android16;

import android.content.Intent;
import android.net.Uri;

import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;
import com.huanchengfly.icebridge.automation.BoundedExpiringDeduplicator;
import com.huanchengfly.icebridge.automation.LinkRuleEntry;
import com.huanchengfly.icebridge.automation.ScopedRuleResolver;
import com.huanchengfly.icebridge.automation.SystemRuntimeConfig;

import java.util.List;
import java.util.regex.Pattern;

/** Value-only capture policy shared by the system hook and Android regression tests. */
public final class SystemAutonomousCapture {
    private static final String SELF = "io.github.yudigaga.icebridge";
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final int CALLER_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_ACTIVITY_FORWARD_RESULT;

    private SystemAutonomousCapture() { }

    public static final class Result {
        public final AutonomousBridgeCoordinator.Request<Intent> request;
        /** Fixed diagnostic code, never a URL or an application payload. */
        public final String reason;
        public final boolean recognizedSource;

        private Result(AutonomousBridgeCoordinator.Request<Intent> request,
                       String reason, boolean recognizedSource) {
            this.request = request;
            this.reason = reason;
            this.recognizedSource = recognizedSource;
        }
    }

    /** No reflection, package queries, Context access, threads, or Activity starts. */
    public static Result capture(Intent original, int userId, SystemRuntimeConfig.State configuration,
                                 String callingPackage, boolean hasResultTo, Integer requestCode) {
        boolean recognized = NativeActivityLinkCapture.isSupportedActivity(original);
        if (original == null || configuration == null || !configuration.recoveryEnabled
                || !configuration.autoOpenLinks) return skipped("configuration", recognized);
        // Ordinary Activity.startActivity also supplies a caller token, with requestCode=-1.
        // Only a real/unknown result relationship or forwarded result forbids independent replay.
        if ((original.getFlags() & Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0) {
            return skipped("forward-result", recognized);
        }
        if (hasResultTo && (requestCode == null || requestCode >= 0)) {
            return skipped("result-token", recognized);
        }
        if (hasCallerBoundPayload(original)) {
            return skipped("caller-grants", recognized);
        }
        NativeActivityLinkCapture.CapturedLink captured = configuration.nativeLinkCapture
                ? NativeActivityLinkCapture.capture(original) : null;
        String sourcePackage = captured == null ? "*" : captured.sourcePackage;
        if (captured == null && callingPackage != null
                && PACKAGE_NAME.matcher(callingPackage).matches()) sourcePackage = callingPackage;
        String webUrl = captured == null ? (configuration.nativeLinkCapture
                && Intent.ACTION_VIEW.equals(original.getAction()) && original.getData() != null
                ? original.getData().toString() : null) : captured.url;
        if (webUrl != null && webUrl.length() <= 16_384) {
            Uri uri = Uri.parse(webUrl);
            String scheme = uri.getScheme();
            if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) && uri.getHost() != null) {
                List<LinkRuleEntry> matches = ScopedRuleResolver.matchingRules(configuration.scopedRules,
                        sourcePackage, uri.getHost());
                if (matches.isEmpty()) return skipped("no-rule", recognized);
                String bound = original.getComponent() == null ? original.getPackage()
                        : original.getComponent().getPackageName();
                String directTarget = ScopedRuleResolver.targetPackages(matches).contains(bound) ? bound : null;
                Intent payload = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
                return selected(new AutonomousBridgeCoordinator.Request<>(AutonomousBridgeCoordinator.Kind.WEB_RULE,
                        userId, sourcePackage, uri.getHost(), directTarget,
                        BoundedExpiringDeduplicator.fingerprint(userId, sourcePackage + ":" + webUrl),
                        payload, false, configuration.autoOpenLinks, configuration.onlyFrozen), recognized);
            }
        }
        Uri direct = CustomSchemeTargetResolver.findCustomSchemeUri(original);
        String target = CustomSchemeTargetResolver.findTargetPackageForCustomSchemeUri(direct);
        if (target == null || SELF.equals(target) || !Intent.ACTION_VIEW.equals(original.getAction())) {
            return skipped("unsupported-intent", recognized);
        }
        if ((original.getComponent() != null && !target.equals(original.getComponent().getPackageName()))
                || (original.getPackage() != null && !target.equals(original.getPackage()))) {
            return skipped("target-mismatch", recognized);
        }
        Intent payload = direct.equals(original.getData()) ? new Intent(original)
                : new Intent(Intent.ACTION_VIEW, direct).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(target);
        return selected(new AutonomousBridgeCoordinator.Request<>(AutonomousBridgeCoordinator.Kind.EXPLICIT,
                userId, "*", null, target,
                BoundedExpiringDeduplicator.fingerprint(userId, original.toUri(Intent.URI_INTENT_SCHEME)),
                payload, false, true, configuration.onlyFrozen), recognized);
    }

    private static Result skipped(String reason, boolean recognized) {
        return new Result(null, reason, recognized);
    }

    private static Result selected(AutonomousBridgeCoordinator.Request<Intent> request, boolean recognized) {
        return new Result(request, "captured", recognized);
    }

    /**
     * Recognized private browser carriers and plain public VIEW links are replayable as public
     * URLs. Bound activities, opaque extras and URI grants must keep the caller's original chain.
     * This checks values only and is safe both before and inside the framework launch lock.
     */
    public static boolean matchesOwnedWebSource(Intent original,
            AutonomousBridgeCoordinator.Request<Intent> request) {
        if (original == null || request == null
                || request.kind != AutonomousBridgeCoordinator.Kind.WEB_RULE
                || request.explicitTarget != null || request.payload == null
                || request.payload.getData() == null || request.sourcePackage == null
                || !PACKAGE_NAME.matcher(request.sourcePackage).matches()
                || SELF.equals(request.sourcePackage)
                || hasCallerBoundPayload(original)) return false;
        NativeActivityLinkCapture.CapturedLink nativeSource = NativeActivityLinkCapture.capture(original);
        if (nativeSource != null) {
            return request.sourcePackage.equals(nativeSource.sourcePackage)
                    && nativeSource.url.equals(request.payload.getDataString());
        }
        Uri data = original.getData();
        String scheme = data == null ? null : data.getScheme();
        if (!Intent.ACTION_VIEW.equals(original.getAction()) || original.getComponent() != null
                || original.getPackage() != null || original.getType() != null
                || (original.getExtras() != null && !original.getExtras().isEmpty())
                || data == null || data.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || !data.equals(request.payload.getData())) return false;
        java.util.Set<String> categories = original.getCategories();
        if (categories != null) for (String category : categories) {
            if (!Intent.CATEGORY_BROWSABLE.equals(category) && !Intent.CATEGORY_DEFAULT.equals(category)) {
                return false;
            }
        }
        return true;
    }

    /** Shared by independent takeover and the legacy foreground bridge before replacing a source. */
    public static boolean hasCallerBoundPayload(Intent original) {
        return original == null || (original.getFlags() & CALLER_FLAGS) != 0
                || original.getClipData() != null || original.getSelector() != null
                || original.hasFileDescriptors();
    }
}
