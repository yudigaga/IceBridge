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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private final SystemTransitPresenter transit;
    private final Handler transitEvents;
    private final AtomicLong nextTransitId = new AtomicLong();
    private final ConcurrentHashMap<Long, TransitTrace> transitTraces = new ConcurrentHashMap<>();
    private TransitTrace workerTrace;
    private volatile boolean windowObservationReady;
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
        this(context, worker, decorator, logger, SystemAutonomousRuntime.class.getClassLoader());
    }

    public SystemAutonomousRuntime(Context context, Handler worker,
            SystemAutonomousLauncher.IntentDecorator decorator, Logger logger, ClassLoader serverLoader) {
        this.context = context;
        this.worker = worker;
        this.logger = logger;
        this.decorator = decorator;
        transit = SystemTransitPresenter.create(context, this::safeLog, serverLoader);
        Handler uiEvents;
        try { uiEvents = (Handler) Class.forName("com.android.server.UiThread", true, serverLoader)
                .getMethod("getHandler").invoke(null); }
        catch (Throwable unavailable) { uiEvents = worker; }
        transitEvents = uiEvents;
        packages = SystemPackageAccess.create();
        preflight = new SystemLaunchPreflight(PROTECTED_PACKAGE, packages);
        coordinator = new AutonomousBridgeCoordinator<>(PROTECTED_PACKAGE, packages,
                new SystemAutonomousLauncher(context, PROTECTED_PACKAGE, (intent, user, source) -> {
                    decorator.registerReplay(intent, user, source);
                    TransitTrace trace = workerTrace;
                    if (trace != null) {
                        intent.putExtra(SystemTargetWindowObserver.EXTRA_TRANSIT_ID, trace.id);
                        String pkg = intent.getComponent() == null ? intent.getPackage()
                                : intent.getComponent().getPackageName();
                        if (pkg != null && trace.targetPackage == null) reportStage(trace, pkg,
                                AutonomousBridgeCoordinator.Stage.OPENING);
                    }
                },
                        (event, user, target) -> logger.event(event + ": user=" + user + ", target=" + target)),
                SystemClock::elapsedRealtime, (request, target, stage) -> {
                    TransitTrace trace = workerTrace;
                    if (trace != null) reportStage(trace, target, stage);
                });
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

    public void setWindowObservationReady(boolean ready) { windowObservationReady = ready; }

    public boolean observesTargetWindows() {
        SystemRuntimeConfig.State state = current(0);
        return state != null && state.recoveryEnabled && TransitWindowCorrelation.interrupted(
                transitTraces.values(), 0, SystemClock.elapsedRealtime()) != null;
    }

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
                transit.cancelAll("disabled");
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
        long now = SystemClock.elapsedRealtime();
        execute(request, now, now);
    }

    public void execute(AutonomousBridgeCoordinator.Request<Intent> request, long observedAt, long queuedAt) {
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
        TransitTrace trace = beginTrace(request.userId, state, observedAt, queuedAt);
        workerTrace = trace;
        AutonomousBridgeCoordinator.Result result;
        try { result = coordinator.execute(request, state.scopedRules); }
        finally { workerTrace = null; }
        logger.event("autonomous transaction: " + result.outcome + ", reason=" + result.reason
                + ", target=" + result.targetPackage + ", user=" + request.userId);
        if (result.outcome == AutonomousBridgeCoordinator.Outcome.ROUTED) {
            trace.acceptedAt = SystemClock.elapsedRealtime();
            transit.onSubmitted(trace.id);
            traceLog(trace, "submitted", trace.acceptedAt);
        } else {
            trace.done.set(true);
            transit.finish(trace.id, result.reason);
            traceLog(trace, "finished-" + result.reason, SystemClock.elapsedRealtime());
        }
        // A copied URL has no foreground original request to preserve. Fall back only after an
        // actual selected-target failure; unmatched, installed/enabled and ambiguous cases stay quiet.
        if (state.browserFallback && request.fingerprint.startsWith("clipboard:")
                && request.kind == AutonomousBridgeCoordinator.Kind.WEB_RULE
                && result.targetPackage != null
                && (result.reason.startsWith("thaw-") || result.reason.startsWith("launch-failed:"))
                && request.payload != null && request.payload.getData() != null) {
            TransitTrace fallback = beginTrace(request.userId, state, observedAt, SystemClock.elapsedRealtime());
            workerTrace = fallback;
            boolean opened;
            try {
                opened = SystemBrowserFallback.open(context, request.userId,
                        request.payload.getData().toString(), result.targetPackage, (intent, user, source) -> {
                            decorator.registerReplay(intent, user, source);
                            intent.putExtra(SystemTargetWindowObserver.EXTRA_TRANSIT_ID, fallback.id);
                            reportStage(fallback, intent.getComponent().getPackageName(),
                                    AutonomousBridgeCoordinator.Stage.OPENING);
                        });
            } finally { workerTrace = null; }
            if (opened) { transit.onSubmitted(fallback.id); traceLog(fallback, "submitted", SystemClock.elapsedRealtime()); }
            else { fallback.done.set(true); transit.finish(fallback.id, "browser-unavailable"); }
            logger.event("autonomous clipboard browser fallback: opened=" + opened);
        }
    }

    /** Called outside UI code by after-hooks; it never queries packages or waits for a handler. */
    public void onTargetWindow(SystemTargetWindowObserver.Event event) {
        if (event == null) return;
        transitEvents.post(() -> handleTargetWindow(event));
    }

    private void handleTargetWindow(SystemTargetWindowObserver.Event event) {
        if (event.systemPrompt) {
            TransitWindowCorrelation.Pending pending = TransitWindowCorrelation.interrupted(
                    transitTraces.values(), event.userId, event.observedAt);
            if (pending != null && current(event.userId) != null) {
                transit.finish(pending.id(), "system-permission-window");
                traceLog((TransitTrace) pending, "system-prompt", event.observedAt);
            }
            return;
        }
        TransitTrace trace = (TransitTrace) TransitWindowCorrelation.match(transitTraces.values(),
                event.id, event.userId, event.packageName, event.observedAt);
        if (trace == null || !trace.done.compareAndSet(false, true)) return;
        transit.onTargetDrawn(trace.id, event.userId, event.packageName);
        traceLog(trace, "window-ready", event.observedAt);
    }

    private TransitTrace beginTrace(int user, SystemRuntimeConfig.State state, long observedAt, long queuedAt) {
        long now = SystemClock.elapsedRealtime();
        transitTraces.entrySet().removeIf(entry -> now < entry.getValue().startedAt
                || now - entry.getValue().startedAt > 20_000L);
        while (transitTraces.size() >= 64) {
            Long oldest = java.util.Collections.min(transitTraces.keySet());
            transitTraces.remove(oldest);
        }
        TransitTrace trace = new TransitTrace(nextTransitId.incrementAndGet(), user, state, observedAt, queuedAt, now);
        transitTraces.put(trace.id, trace);
        traceLog(trace, "worker-start", now);
        return trace;
    }

    private void reportStage(TransitTrace trace, String target, AutonomousBridgeCoordinator.Stage stage) {
        long now = SystemClock.elapsedRealtime();
        trace.targetPackage = target;
        if (stage == AutonomousBridgeCoordinator.Stage.THAWING) trace.thawingAt = now;
        else trace.openingAt = now;
        if (windowObservationReady) {
            transit.onStage(trace.id, trace.userId, target,
                    stage == AutonomousBridgeCoordinator.Stage.THAWING
                            ? SystemTransitPresenter.Stage.THAWING : SystemTransitPresenter.Stage.OPENING,
                    trace.state.transitSeedColor, trace.state.transitAppearance);
        }
        traceLog(trace, stage == AutonomousBridgeCoordinator.Stage.THAWING ? "thawing" : "opening", now);
    }

    private void traceLog(TransitTrace trace, String stage, long now) {
        safeLog("transit timing: id=" + trace.id + ", stage=" + stage + ", user=" + trace.userId
                + ", target=" + trace.targetPackage + ", elapsedMs=" + Math.max(0, now - trace.observedAt)
                + ", sourceStartMs=" + Math.max(0, trace.queuedAt - trace.observedAt)
                + ", queueMs=" + Math.max(0, trace.startedAt - trace.queuedAt)
                + ", workerMs=" + Math.max(0, now - trace.startedAt)
                + ", thawMs=" + (trace.thawingAt > 0 && trace.openingAt > 0
                ? Math.max(0, trace.openingAt - trace.thawingAt) : -1));
    }
    private void safeLog(String message) { try { logger.event(message); } catch (Throwable ignored) { } }

    private static final class TransitTrace implements TransitWindowCorrelation.Pending {
        final long id, observedAt, queuedAt, startedAt;
        final int userId;
        final SystemRuntimeConfig.State state;
        final AtomicBoolean done = new AtomicBoolean();
        volatile String targetPackage;
        volatile long thawingAt, openingAt, acceptedAt;
        TransitTrace(long id, int userId, SystemRuntimeConfig.State state, long observedAt, long queuedAt, long startedAt) {
            this.id = id; this.userId = userId; this.state = state;
            this.observedAt = observedAt; this.queuedAt = queuedAt; this.startedAt = startedAt;
        }
        @Override public long id() { return id; }
        @Override public int userId() { return userId; }
        @Override public String targetPackage() { return targetPackage; }
        @Override public long startedAt() { return startedAt; }
        @Override public long openingAt() { return openingAt; }
        @Override public boolean completed() { return done.get(); }
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
