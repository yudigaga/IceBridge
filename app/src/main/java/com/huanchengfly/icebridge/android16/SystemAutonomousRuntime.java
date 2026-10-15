package com.huanchengfly.icebridge.android16;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;

import com.huanchengfly.icebridge.SystemServerConfigContract;
import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;
import com.huanchengfly.icebridge.automation.SystemOnlineRuleClient;
import com.huanchengfly.icebridge.automation.SystemRuntimeConfig;
import com.huanchengfly.icebridge.automation.SystemRuntimeConfigStore;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the worker-only runtime; frozen-package observations never call an app component. */
public final class SystemAutonomousRuntime {
    public interface Logger { void event(String message); }
    private static final String PROTECTED_PACKAGE = "io.github.yudigaga.icebridge";
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    private final Context context;
    private final Handler worker;
    private final Logger logger;
    private final SystemAutonomousLauncher.IntentDecorator decorator;
    private final SystemRuntimeConfigStore store = new SystemRuntimeConfigStore();
    private final SystemPackageAccess packages;
    private final AutonomousBridgeCoordinator<Intent> coordinator;
    private final SystemLaunchPreflight preflight;
    private final ExecutorService network = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "IceBridge-RuleUpdates");
        thread.setDaemon(true);
        return thread;
    });
    private volatile SystemRuntimeConfig.State configuration;
    private boolean networkBusy;
    private long nextOnlineAttempt;
    private int onlineFailures;

    public SystemAutonomousRuntime(Context context, Handler worker,
            SystemAutonomousLauncher.IntentDecorator decorator, Logger logger) {
        this.context = context;
        this.worker = worker;
        this.logger = logger;
        this.decorator = decorator;
        packages = SystemPackageAccess.create();
        preflight = new SystemLaunchPreflight(PROTECTED_PACKAGE, packages);
        coordinator = new AutonomousBridgeCoordinator<>(PROTECTED_PACKAGE, packages,
                new SystemAutonomousLauncher(context, PROTECTED_PACKAGE, decorator,
                        (event, user, target) -> logger.event(event + ": user=" + user + ", target=" + target)),
                SystemClock::elapsedRealtime);
    }

    /** The Provider channel currently configures owner user 0; never inherit it into a profile. */
    public void restore() {
        try {
            configuration = store.load(0, SystemServerConfigContract.SCHEMA_VERSION);
            if (configuration != null) logger.event("autonomous restored: user=0, rules="
                    + configuration.scopedRules.size() + ", revision=" + configuration.online.highestRevision);
        } catch (IOException error) {
            logger.event("autonomous restore unavailable: " + error.getMessage());
        }
    }

    public boolean isReady() { return packages.isReady(); }

    /** Called exclusively at the verified public Binder entry before framework launch locks. */
    public void prepareOriginalLaunch(Intent original, int userId, int callerUserId, boolean outsideLock) {
        SystemRuntimeConfig.State state = current(userId);
        if (state == null || !state.recoveryEnabled || !outsideLock) return;
        if (SystemLaunchPreflight.isPaymentSdkRequest(original)) {
            if (!state.paymentSdkBridge) return;
        } else if (!state.autoOpenLinks) return;
        AutonomousBridgeCoordinator.State own = packages.read(PROTECTED_PACKAGE, userId);
        if (!own.isKnown() || !own.installed) return;
        SystemLaunchPreflight.Result result = preflight.prepare(original, userId, callerUserId, outsideLock);
        if (result.outcome == SystemLaunchPreflight.Outcome.THAWED
                || result.outcome == SystemLaunchPreflight.Outcome.FAILED) {
            logger.event("autonomous original preflight: " + result.outcome + ", target="
                    + result.targetPackage + ", reason=" + result.reason);
        }
    }

    /** Immutable in-memory lookup, safe under ActivityTaskManager and clipboard locks. */
    public SystemRuntimeConfig.State current(int userId) {
        SystemRuntimeConfig.State value = configuration;
        return value != null && value.autonomousEnabled && value.userId == userId ? value : null;
    }

    public boolean ownerFrozen() {
        AutonomousBridgeCoordinator.State own = packages.read(PROTECTED_PACKAGE, 0);
        // Unknown state must not turn a transient PM failure into an app launch.
        return !own.isKnown() || !own.isLaunchable();
    }

    /** Trusted SYSTEM_UID Provider snapshot; acknowledge only after durable persistence succeeds. */
    public void accept(SystemServerConfigContract.Snapshot snapshot, boolean hooksReady) {
        if (!snapshot.available || snapshot.userId != 0) return;
        boolean ready = false;
        boolean persisted = false;
        String message;
        try {
            if (!snapshot.autonomousEnabled) {
                configuration = null;
                store.disable(snapshot.userId);
                persisted = true;
                message = "已关闭系统独立运行";
            } else {
                SystemRuntimeConfig.State incoming = snapshot.toRuntimeState(System.currentTimeMillis());
                incoming = mergeProvider(incoming, configuration);
                store.save(incoming);
                persisted = true;
                configuration = incoming;
                AutonomousBridgeCoordinator.Capabilities capability = packages.capabilities();
                ready = hooksReady && capability.enableDisabled && capability.unhide
                        && packages.read(PROTECTED_PACKAGE, snapshot.userId).isKnown();
                message = ready ? "配置已由系统持久保存，可冻结本体" : "配置已保存，但系统 Hook 或包管理能力不完整";
                logger.event("autonomous synchronized: ready=" + ready + ", rules="
                        + incoming.scopedRules.size() + ", revision=" + incoming.online.highestRevision);
            }
        } catch (IOException error) {
            message = "系统配置保存失败：" + error.getMessage();
            logger.event("autonomous sync failed: " + error.getMessage());
        }
        report(snapshot, ready, persisted, message);
        tick();
    }

    /** App settings/manual edits must never roll back a newer system-downloaded online snapshot. */
    static SystemRuntimeConfig.State mergeProvider(SystemRuntimeConfig.State incoming,
                                                   SystemRuntimeConfig.State retained) throws IOException {
        return SystemOnlineRuleClient.mergeProvider(incoming, retained);
    }

    public void execute(AutonomousBridgeCoordinator.Request<Intent> request) {
        SystemRuntimeConfig.State state = current(request.userId);
        request = rebaseRequest(state, request);
        if (request == null) return;
        // Installed is distinct from hidden: uninstalling IceBridge revokes stale runtime actions.
        AutonomousBridgeCoordinator.State own = packages.read(PROTECTED_PACKAGE, request.userId);
        if (!own.isKnown() || !own.installed) {
            if (own.isKnown()) {
                configuration = null;
                try { store.disable(request.userId); } catch (IOException ignored) { }
            }
            logger.event("autonomous request ignored: module-not-installed-or-state-unknown");
            return;
        }
        AutonomousBridgeCoordinator.Result result = coordinator.execute(request, state.scopedRules);
        logger.event("autonomous transaction: " + result.outcome + ", reason=" + result.reason
                + ", target=" + result.targetPackage + ", user=" + request.userId);
        // A copied URL has no foreground original request to preserve. Fall back only after an
        // actual selected-target failure; unmatched, installed/enabled and ambiguous cases stay quiet.
        if (state.browserFallback && request.fingerprint.startsWith("clipboard:")
                && request.kind == AutonomousBridgeCoordinator.Kind.WEB_RULE
                && result.targetPackage != null
                && (result.reason.startsWith("thaw-") || result.reason.startsWith("launch-failed:"))
                && request.payload != null && request.payload.getData() != null) {
            boolean opened = SystemBrowserFallback.open(context, request.userId,
                    request.payload.getData().toString(), result.targetPackage, decorator);
            logger.event("autonomous clipboard browser fallback: opened=" + opened);
        }
    }

    /** A queued observation may outlive a setting change; use the latest acknowledged policy. */
    static <P> AutonomousBridgeCoordinator.Request<P> rebaseRequest(SystemRuntimeConfig.State state,
            AutonomousBridgeCoordinator.Request<P> request) {
        if (state == null || request == null || !state.autonomousEnabled || state.userId != request.userId
                || !state.recoveryEnabled || !state.autoOpenLinks) return null;
        boolean clipboard = request.fingerprint.startsWith("clipboard:");
        if ((clipboard && !state.clipboardAutoOpen)
                || (!clipboard && request.kind == AutonomousBridgeCoordinator.Kind.WEB_RULE
                && !state.nativeLinkCapture)) return null;
        return new AutonomousBridgeCoordinator.Request<>(request.kind, request.userId, request.sourcePackage,
                request.host, request.explicitTarget, request.fingerprint, request.payload,
                request.ownsOriginal, state.autoOpenLinks, state.onlyFrozen);
    }

    /** Called on the serial worker; network work runs on its own bounded executor. */
    public void tick() {
        SystemRuntimeConfig.State start = configuration;
        if (start == null || !start.autonomousEnabled || !start.onlineUpdatesEnabled
                || networkBusy || SystemClock.elapsedRealtime() < nextOnlineAttempt) return;
        AutonomousBridgeCoordinator.State own = packages.read(PROTECTED_PACKAGE, start.userId);
        if (!own.isKnown()) return;
        if (!own.installed) {
            configuration = null;
            try { store.disable(start.userId); } catch (IOException ignored) { }
            return;
        }
        networkBusy = true;
        network.execute(() -> {
            SystemOnlineRuleClient.Result result = null;
            String failure = null;
            try { result = new SystemOnlineRuleClient().fetch(start); }
            catch (Exception error) { failure = error.getClass().getSimpleName() + ":" + error.getMessage(); }
            SystemOnlineRuleClient.Result downloaded = result;
            String failed = failure;
            worker.post(() -> {
                networkBusy = false;
                SystemRuntimeConfig.State latest = configuration;
                if (latest == null || !latest.onlineUpdatesEnabled || latest.userId != start.userId) return;
                try {
                    if (failed != null || downloaded == null) throw new IOException(failed);
                    SystemRuntimeConfig.State updated = downloaded.applyTo(latest, System.currentTimeMillis());
                    store.save(updated);
                    configuration = updated;
                    onlineFailures = 0;
                    nextOnlineAttempt = SystemClock.elapsedRealtime() + WEEK;
                    logger.event("autonomous online rules accepted: revision=" + updated.online.highestRevision);
                } catch (Exception error) {
                    onlineFailures = Math.min(onlineFailures + 1, 6);
                    nextOnlineAttempt = SystemClock.elapsedRealtime()
                            + Math.min(6L * 60 * 60 * 1000, 15L * 60 * 1000 * (1L << (onlineFailures - 1)));
                    logger.event("autonomous online rules retained: " + error.getMessage());
                }
            });
        });
    }

    private void report(SystemServerConfigContract.Snapshot snapshot, boolean ready, boolean persisted, String message) {
        Bundle status = new Bundle();
        status.putInt(SystemServerConfigContract.KEY_USER_ID, snapshot.userId);
        status.putInt(SystemServerConfigContract.KEY_SCHEMA_VERSION, SystemServerConfigContract.SCHEMA_VERSION);
        status.putString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, snapshot.configFingerprint);
        status.putBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, ready);
        status.putBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, persisted);
        status.putString(SystemServerConfigContract.KEY_RUNTIME_MESSAGE, message);
        status.putLong(SystemServerConfigContract.KEY_RUNTIME_REVISION,
                configuration == null ? 0 : configuration.online.highestRevision);
        long identity = Binder.clearCallingIdentity();
        try {
            context.getContentResolver().call(Uri.parse("content://" + SystemServerConfigContract.AUTHORITY),
                    SystemServerConfigContract.METHOD_REPORT_RUNTIME_STATUS, null, status);
        } catch (Exception error) {
            logger.event("autonomous acknowledgement unavailable: " + error.getClass().getSimpleName());
        } finally { Binder.restoreCallingIdentity(identity); }
    }
}
