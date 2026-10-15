package com.huanchengfly.icebridge.android16;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.huanchengfly.icebridge.BuildConfig;
import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;
import com.huanchengfly.icebridge.automation.FluidCloudRuleCatalog;
import com.huanchengfly.icebridge.automation.LinkRuleEntry;

import java.util.Objects;

/** Launches from system_server's worker without starting any IceBridge component. */
public final class SystemAutonomousLauncher implements AutonomousBridgeCoordinator.Launcher<Intent> {
    private static final int CALLER_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    public interface IntentDecorator {
        /** Add and register an expiring one-use loop token before the system launch. */
        void registerReplay(Intent intent, int userId, String sourcePackage);
    }

    public interface EventLogger { void onEvent(String event, int userId, String targetPackage); }

    private final Context systemContext;
    private final String protectedPackage;
    private final IntentDecorator decorator;
    private final EventLogger logger;

    public SystemAutonomousLauncher(Context systemContext, IntentDecorator decorator, EventLogger logger) {
        this(systemContext, BuildConfig.APPLICATION_ID, decorator, logger);
    }

    public SystemAutonomousLauncher(Context systemContext, String protectedPackage,
                                    IntentDecorator decorator, EventLogger logger) {
        this.systemContext = Objects.requireNonNull(systemContext, "systemContext");
        this.protectedPackage = Objects.requireNonNull(protectedPackage, "protectedPackage");
        this.decorator = Objects.requireNonNull(decorator, "decorator");
        this.logger = logger;
    }

    @Override public void launch(AutonomousBridgeCoordinator.Request<Intent> request,
                                 String targetPackage, String conversion) throws Exception {
        Intent launch = buildLaunchIntent(request, targetPackage, conversion, protectedPackage);
        start(launch, request.userId, request.sourcePackage);
        if (logger != null) logger.onEvent("system-launch-requested", request.userId, targetPackage);
    }

    @Override public void fallback(AutonomousBridgeCoordinator.Request<Intent> request) throws Exception {
        if (!request.ownsOriginal || request.kind == AutonomousBridgeCoordinator.Kind.THAW_ONLY) return;
        if (request.payload == null || request.userId < 0) throw new IllegalArgumentException("fallback-invalid");
        rejectCallerBoundPayload(request.payload);
        if (protectedPackage.equals(explicitTarget(request.payload))) {
            throw new IllegalArgumentException("self-launch-prohibited");
        }
        Intent original = new Intent(request.payload).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(original, request.userId, request.sourcePackage);
        if (logger != null) logger.onEvent("system-original-replayed", request.userId, explicitTarget(original));
    }

    /** Exposed for instrumentation verification without launching a real target. */
    public static Intent buildLaunchIntent(AutonomousBridgeCoordinator.Request<Intent> request,
            String targetPackage, String conversion, String protectedPackage) {
        if (request == null || request.payload == null || request.userId < 0
                || targetPackage == null || targetPackage.isEmpty()) {
            throw new IllegalArgumentException("launch-invalid");
        }
        if (targetPackage.equals(protectedPackage)) throw new IllegalArgumentException("self-launch-prohibited");
        if (request.kind == AutonomousBridgeCoordinator.Kind.THAW_ONLY) {
            throw new IllegalArgumentException("thaw-only-must-preserve-caller");
        }
        Intent original = request.payload;
        if (request.kind == AutonomousBridgeCoordinator.Kind.WEB_RULE) {
            Uri web = original.getData();
            if (!isWeb(web)) {
                String embedded = IntentUrlExtractor.findEmbeddedUrl(original.getExtras());
                web = embedded == null ? null : Uri.parse(embedded);
            }
            if (!isWeb(web) || web.getHost() == null || web.getHost().isEmpty()) {
                throw new IllegalArgumentException("source-must-be-http-or-https");
            }
            if (request.host == null || !request.host.equalsIgnoreCase(web.getHost())) {
                throw new IllegalArgumentException("source-host-mismatch");
            }
            Uri routed;
            if (LinkRuleEntry.CONVERSION_FIXED_DEEP_LINK.equals(conversion)) {
                routed = FluidCloudRuleCatalog.rewriteForTarget(web, targetPackage);
            } else if (LinkRuleEntry.CONVERSION_NONE.equals(conversion)) {
                routed = web;
            } else {
                throw new IllegalArgumentException("unsupported-conversion");
            }
            Intent launch = new Intent(original);
            // URI grants and a source WebView's resolver/selector belong to the original caller.
            // The independent transaction only transfers the reviewed public link.
            launch.setSelector(null);
            launch.setComponent(null);
            launch.setPackage(targetPackage);
            launch.setAction(Intent.ACTION_VIEW);
            launch.setClipData(null);
            launch.setFlags((launch.getFlags() & ~CALLER_GRANTS) | Intent.FLAG_ACTIVITY_NEW_TASK);
            launch.setData(routed);
            launch.addCategory(Intent.CATEGORY_BROWSABLE);
            return launch;
        }
        rejectCallerBoundPayload(original);
        String originalTarget = explicitTarget(original);
        if (originalTarget != null && !targetPackage.equals(originalTarget)) {
            throw new IllegalArgumentException("explicit-target-mismatch");
        }
        Intent launch = new Intent(original);
        launch.setSelector(null);
        if (launch.getComponent() == null) launch.setPackage(targetPackage);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return launch;
    }

    private void start(Intent launch, int userId, String sourcePackage) throws Exception {
        if (userId < 0) throw new IllegalArgumentException("user-unknown");
        decorator.registerReplay(launch, userId, sourcePackage);
        SystemActivityLauncher.start(systemContext, launch, userId);
    }

    private static boolean isWeb(Uri uri) {
        return uri != null && ("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private static void rejectCallerBoundPayload(Intent intent) {
        Uri data = intent.getData();
        if ((intent.getFlags() & CALLER_GRANTS) != 0 || intent.getClipData() != null
                || (data != null && ("content".equalsIgnoreCase(data.getScheme())
                || "file".equalsIgnoreCase(data.getScheme())))) {
            throw new IllegalArgumentException("caller-uri-grant-required");
        }
        PaymentSdkBridgeResolver.Candidate payment = PaymentSdkBridgeResolver.resolve(intent);
        if (payment != null && payment.prewarmOnly) {
            throw new IllegalArgumentException("sdk-result-chain-must-pass-through");
        }
    }

    private static String explicitTarget(Intent intent) {
        return intent.getComponent() != null ? intent.getComponent().getPackageName() : intent.getPackage();
    }
}
