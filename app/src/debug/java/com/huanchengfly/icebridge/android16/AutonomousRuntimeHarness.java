package com.huanchengfly.icebridge.android16;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import com.huanchengfly.icebridge.BuildConfig;
import com.huanchengfly.icebridge.SystemServerConfigContract;
import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;
import com.huanchengfly.icebridge.automation.LinkRuleEntry;
import com.huanchengfly.icebridge.automation.ScopedRuleResolver;
import com.huanchengfly.icebridge.automation.SystemRuntimeConfig;
import com.huanchengfly.icebridge.automation.SystemRuntimeConfigStore;
import com.huanchengfly.icebridge.automation.SystemOnlineRuleClient;

import java.util.Collections;
import java.util.UUID;

/** Debug-only app_process entry point. Requires UID 1000; never exported as an Android component. */
public final class AutonomousRuntimeHarness {
    private static final int USER = 0;
    private static final String FIXTURE = "io.github.icebridge.fixture";
    private static final String HOST = "example.com";

    private AutonomousRuntimeHarness() { }

    public static void main(String[] args) throws Exception {
        if (!BuildConfig.DEBUG || Process.myUid() != Process.SYSTEM_UID) {
            throw new SecurityException("debug harness requires system UID 1000");
        }
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        SystemPackageAccess packages = SystemPackageAccess.create();
        SystemRuntimeConfigStore store = new SystemRuntimeConfigStore();
        String command = args.length == 0 ? "status" : args[0];
        System.out.println("command=" + command + ";uid=" + Process.myUid() + ";user=" + USER);
        System.out.println("capability=" + packages.capabilities().description);
        printState("own-before", packages.read(BuildConfig.APPLICATION_ID, USER));
        printState("target-before", packages.read(FIXTURE, USER));
        if ("save".equals(command)) {
            SystemRuntimeConfig.State state = new SystemRuntimeConfig.State(
                    SystemServerConfigContract.SCHEMA_VERSION, USER, System.currentTimeMillis(),
                    true, true, true, true, true, false, true, true, false,
                    ScopedRuleResolver.encode(Collections.singletonList(new LinkRuleEntry("*", HOST,
                            FIXTURE, LinkRuleEntry.SOURCE_MANUAL, LinkRuleEntry.CONVERSION_NONE, "debug fixture"))),
                    SystemRuntimeConfig.OnlineState.empty());
            store.save(state);
            System.out.println("saved=" + SystemRuntimeConfig.fingerprint(state));
        } else if ("disable".equals(command)) {
            store.disable(USER);
            System.out.println("disabled=true");
        } else if (!"status".equals(command) && !"web".equals(command) && !"explicit".equals(command)
                && !"online".equals(command) && !"capture-wechat".equals(command) && !"capture-qq".equals(command)) {
            throw new IllegalArgumentException("expected save, web, explicit, capture-wechat, capture-qq, online, status or disable");
        }
        // Load from disk in this fresh process. No Provider or app-owned file is accessed.
        SystemRuntimeConfig.State restored = store.load(USER, SystemServerConfigContract.SCHEMA_VERSION);
        System.out.println("restored=" + (restored != null) + ";rules="
                + (restored == null ? 0 : restored.scopedRules.size()));
        if ("online".equals(command)) {
            try {
                if (restored == null || !restored.autonomousEnabled) {
                    throw new IllegalStateException("no enabled retained system configuration");
                }
                // The fixture starts with updates disabled. This probe explicitly enables only
                // autonomous HTTP refresh while preserving every retained switch and source rule.
                SystemRuntimeConfig.State enabled = new SystemRuntimeConfig.State(
                        restored.providerSchemaVersion, restored.userId, restored.savedAtEpochMillis,
                        restored.autonomousEnabled, restored.recoveryEnabled, restored.autoOpenLinks,
                        restored.nativeLinkCapture, restored.clipboardAutoOpen, restored.paymentSdkBridge,
                        restored.onlyFrozen, restored.browserFallback, true, restored.encodedRules(), restored.online,
                        restored.transitSeedColor, restored.transitAppearance, restored.transitCardEnabled,
                        restored.openLinksInSmallWindow, restored.smallWindowPackages.toArray(new String[0]));
                SystemRuntimeConfig.State updated = new SystemOnlineRuleClient().fetch(enabled)
                        .applyTo(enabled, System.currentTimeMillis());
                store.save(updated);
                SystemRuntimeConfig.State accepted = store.load(USER, SystemServerConfigContract.SCHEMA_VERSION);
                if (accepted == null) throw new IllegalStateException("updated system configuration was not retained");
                System.out.println("online-accepted:revision=" + accepted.online.highestRevision
                        + ";rules=" + accepted.scopedRules.size() + ";sha256=" + accepted.online.highestSha256
                        + ";updatesEnabled=" + accepted.onlineUpdatesEnabled);
            } finally {
                // Keep package-state evidence even when DNS, TLS or bounded HTTP refresh fails.
                printState("own-after", packages.read(BuildConfig.APPLICATION_ID, USER));
                printState("target-after", packages.read(FIXTURE, USER));
            }
        }
        if ("web".equals(command) || "explicit".equals(command) || command.startsWith("capture-")) {
            if (restored == null || !restored.autonomousEnabled || !restored.recoveryEnabled) {
                throw new IllegalStateException("no enabled retained system configuration");
            }
            String url = args.length > 1 ? args[1] : "https://example.com/autonomous-fixture";
            Uri uri = Uri.parse(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !HOST.equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException("fixture URL must use https://example.com");
            }
            Intent original = new Intent(Intent.ACTION_VIEW, uri);
            AutonomousBridgeCoordinator.Kind kind = AutonomousBridgeCoordinator.Kind.WEB_RULE;
            if ("explicit".equals(command)) {
                original.setPackage(FIXTURE);
                kind = AutonomousBridgeCoordinator.Kind.EXPLICIT;
            }
            String fingerprint = UUID.randomUUID().toString();
            AutonomousBridgeCoordinator.Request<Intent> request = new AutonomousBridgeCoordinator.Request<>(
                    kind, USER, "com.android.shell", HOST, FIXTURE, fingerprint, original,
                    false, restored.autoOpenLinks, restored.onlyFrozen);
            if (command.startsWith("capture-")) {
                boolean wechat = "capture-wechat".equals(command);
                String carrier = wechat ? "com.tencent.mm" : "com.tencent.mobileqq";
                String activity = wechat ? "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI"
                        : "com.tencent.mobileqq.activity.QQBrowserActivity";
                Intent source = new Intent().setComponent(new ComponentName(carrier, activity))
                        .putExtra("rawUrl", url).putExtra("source-private", "must-not-be-forwarded");
                // Activity.startActivity() still passes its source token with requestCode=-1.
                SystemAutonomousCapture.Result capture = SystemAutonomousCapture.capture(source,
                        USER, restored, carrier, true, -1);
                System.out.println("capture:source=" + carrier + ";sourceToken=true;requestCode=-1;reason=" + capture.reason);
                if (capture.request == null) throw new IllegalStateException("ordinary source Activity was not captured");
                if (source.getData() != null || source.getAction() != null || !source.hasExtra("source-private")
                        || capture.request.payload.hasExtra("source-private")) {
                    throw new IllegalStateException("original source changed or private extras forwarded");
                }
                request = capture.request;
            }
            SystemAutonomousLauncher launcher = new SystemAutonomousLauncher(context, BuildConfig.APPLICATION_ID,
                    (intent, user, source) -> intent.putExtra("autonomous_harness_replay", fingerprint),
                    (event, user, target) -> System.out.println("event=" + event + ";user=" + user + ";target=" + target));
            AutonomousBridgeCoordinator<Intent> coordinator = new AutonomousBridgeCoordinator<>(
                    BuildConfig.APPLICATION_ID, packages, launcher, SystemClock::elapsedRealtime);
            AutonomousBridgeCoordinator.Result result = coordinator.execute(request, restored.scopedRules);
            System.out.println("outcome=" + result.outcome + ";target=" + result.targetPackage
                    + ";reason=" + result.reason + ";originalReplayed=" + result.originalReplayed);
        }
        if (!"online".equals(command)) {
            printState("own-after", packages.read(BuildConfig.APPLICATION_ID, USER));
            printState("target-after", packages.read(FIXTURE, USER));
        }
        // ActivityThread may create non-daemon Binder/Handler threads; this is a bounded CLI probe.
        System.exit(0);
    }

    private static void printState(String name, AutonomousBridgeCoordinator.State state) {
        System.out.println(name + ":installed=" + state.installed + ";installedKnown=" + state.installedKnown
                + ";enabled=" + state.enabledSetting + ";enabledKnown=" + state.enabledKnown
                + ";applicationEnabled=" + state.applicationEnabled + ";hidden=" + state.hidden
                + ";hiddenKnown=" + state.hiddenKnown + ";suspended=" + state.suspended
                + ";suspendedKnown=" + state.suspendedKnown + ";error=" + state.error);
    }
}
