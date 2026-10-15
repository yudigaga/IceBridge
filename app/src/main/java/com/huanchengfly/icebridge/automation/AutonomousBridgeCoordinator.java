package com.huanchengfly.icebridge.automation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A worker-thread transaction that never needs an IceBridge Activity, provider or activation engine.
 * Package state is read for the request's user on every new transaction, including after thawing.
 * The hook owns scheduling and must only claim requests it can safely replay without a result token.
 */
public final class AutonomousBridgeCoordinator<P> {
    public enum Kind { WEB_RULE, EXPLICIT, THAW_ONLY }
    public enum Outcome { ROUTED, THAWED, PASSTHROUGH, DUPLICATE, FAILED }
    public enum Stage { THAWING, OPENING }

    public interface Clock { long elapsedRealtime(); }

    /** Progress is advisory and must never delay or prevent the package transaction. */
    public interface Observer<P> {
        void onStage(Request<P> request, String targetPackage, Stage stage);
    }

    public interface PackageAccess {
        State read(String packageName, int userId);
        Capabilities capabilities();

        /** Return null on success. The caller always reads back the complete package state. */
        String thaw(String packageName, int userId, State before);
    }

    public interface Launcher<P> {
        /** Keep the original payload intact for EXPLICIT; only WEB_RULE may convert its URL. */
        void launch(Request<P> request, String targetPackage, String conversion) throws Exception;

        /** Replay an owned original request with a one-use loop guard. */
        void fallback(Request<P> request) throws Exception;
    }

    public static final class Capabilities {
        public final boolean enableDisabled;
        public final boolean unhide;
        public final String description;

        public Capabilities(boolean enableDisabled, boolean unhide, String description) {
            this.enableDisabled = enableDisabled;
            this.unhide = unhide;
            this.description = description;
        }
    }

    public static final class State {
        public final boolean installed;
        public final boolean installedKnown;
        public final int enabledSetting;
        public final boolean enabledKnown;
        public final boolean applicationEnabled;
        public final boolean hidden;
        public final boolean hiddenKnown;
        public final boolean suspended;
        public final boolean suspendedKnown;
        public final String error;

        public State(boolean installed, boolean installedKnown, int enabledSetting,
                     boolean enabledKnown, boolean applicationEnabled, boolean hidden,
                     boolean hiddenKnown, boolean suspended, boolean suspendedKnown, String error) {
            this.installed = installed;
            this.installedKnown = installedKnown;
            this.enabledSetting = enabledSetting;
            this.enabledKnown = enabledKnown;
            this.applicationEnabled = applicationEnabled;
            this.hidden = hidden;
            this.hiddenKnown = hiddenKnown;
            this.suspended = suspended;
            this.suspendedKnown = suspendedKnown;
            this.error = error;
        }

        public static State known(boolean installed, int enabledSetting, boolean applicationEnabled,
                                  boolean hidden, boolean suspended) {
            return new State(installed, true, enabledSetting, true, applicationEnabled,
                    hidden, true, suspended, true, null);
        }

        public static State unknown(String error) {
            return new State(false, false, -1, false, false,
                    false, false, false, false, error);
        }

        public boolean isKnown() {
            return error == null && installedKnown && (!installed
                    || (enabledKnown && hiddenKnown && suspendedKnown));
        }

        public boolean isDisabled() {
            return enabledSetting == 2 || enabledSetting == 3 || enabledSetting == 4;
        }

        public boolean isRecoverable() {
            return isKnown() && installed && !suspended && (isDisabled() || hidden);
        }

        public boolean isLaunchable() {
            return isKnown() && installed && !hidden && !suspended && applicationEnabled
                    && (enabledSetting == 0 || enabledSetting == 1);
        }
    }

    public static final class Request<P> {
        public final Kind kind;
        public final int userId;
        public final String sourcePackage;
        public final String host;
        public final String explicitTarget;
        /** A non-sensitive fingerprint of the full original request, never just a target package. */
        public final String fingerprint;
        public final P payload;
        /** False for observations/clipboard and THAW_ONLY: the original call still belongs to Android. */
        public final boolean ownsOriginal;
        public final boolean autoOpen;
        public final boolean onlyFrozen;

        public Request(Kind kind, int userId, String sourcePackage, String host,
                       String explicitTarget, String fingerprint, P payload,
                       boolean ownsOriginal, boolean autoOpen) {
            this(kind, userId, sourcePackage, host, explicitTarget, fingerprint, payload,
                    ownsOriginal, autoOpen, true);
        }

        public Request(Kind kind, int userId, String sourcePackage, String host,
                       String explicitTarget, String fingerprint, P payload,
                       boolean ownsOriginal, boolean autoOpen, boolean onlyFrozen) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.userId = userId;
            this.sourcePackage = sourcePackage == null ? "*" : sourcePackage;
            this.host = host;
            this.explicitTarget = explicitTarget;
            this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
            this.payload = payload;
            this.ownsOriginal = ownsOriginal;
            this.autoOpen = autoOpen;
            this.onlyFrozen = onlyFrozen;
        }
    }

    public static final class Result {
        public final Outcome outcome;
        public final String targetPackage;
        public final String reason;
        public final boolean originalReplayed;

        private Result(Outcome outcome, String targetPackage, String reason, boolean originalReplayed) {
            this.outcome = outcome;
            this.targetPackage = targetPackage;
            this.reason = reason;
            this.originalReplayed = originalReplayed;
        }
    }

    private static final int MAX_DEDUPLICATION_ENTRIES = 256;
    private static final long DEDUPLICATION_MILLIS = 1_500L;
    private final String protectedPackage;
    private final PackageAccess packages;
    private final Launcher<P> launcher;
    private final Clock clock;
    private final Observer<P> observer;
    private final LinkedHashMap<String, Long> successfulTransactions = new LinkedHashMap<>();

    public AutonomousBridgeCoordinator(String protectedPackage, PackageAccess packages,
                                       Launcher<P> launcher, Clock clock) {
        this(protectedPackage, packages, launcher, clock, (request, target, stage) -> { });
    }

    public AutonomousBridgeCoordinator(String protectedPackage, PackageAccess packages,
                                       Launcher<P> launcher, Clock clock, Observer<P> observer) {
        this.protectedPackage = Objects.requireNonNull(protectedPackage, "protectedPackage");
        this.packages = Objects.requireNonNull(packages, "packages");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /** Run only on the serial system worker, outside ActivityTaskManager/clipboard locks. */
    public Result execute(Request<P> request, List<LinkRuleEntry> rules) {
        Objects.requireNonNull(request, "request");
        if (request.userId < 0) return preserve(request, null, "user-unknown");
        String key = request.userId + "|" + request.sourcePackage + "|" + request.kind
                + "|" + request.fingerprint;
        long now = clock.elapsedRealtime();
        prune(now);
        if (successfulTransactions.containsKey(key)) {
            return new Result(Outcome.DUPLICATE, null, "duplicate", false);
        }
        String selectedTarget = null;
        try {
            Selection selected = select(request, rules);
            if (selected.target == null) return preserve(request, null, selected.reason);
            String target = selected.target;
            selectedTarget = target;
            if (protectedPackage.equals(target)) return preserve(request, target, "self-thaw-prohibited");
            State before = selected.state;
            if (!before.isRecoverable()) {
                if (request.kind == Kind.WEB_RULE && !request.onlyFrozen && request.autoOpen
                        && before.isLaunchable()) {
                    try {
                        notifyStage(request, target, Stage.OPENING);
                        launcher.launch(request, target, selected.conversion);
                    } catch (Throwable error) {
                        return preserve(request, target, "launch-failed:" + error.getClass().getSimpleName());
                    }
                    remember(key, clock.elapsedRealtime());
                    return new Result(Outcome.ROUTED, target, "routed-already-enabled", false);
                }
                return preserve(request, target,
                        before.isLaunchable() ? "already-launchable" : "not-recoverable");
            }
            Capabilities capability = packages.capabilities();
            if (capability == null || (before.isDisabled() && !capability.enableDisabled)
                    || (before.hidden && !capability.unhide)) {
                return preserve(request, target, "freeze-method-unsupported");
            }
            notifyStage(request, target, Stage.THAWING);
            String failure = packages.thaw(target, request.userId, before);
            if (failure != null) return preserve(request, target, "thaw-" + failure);
            State after = packages.read(target, request.userId);
            if (after == null || !after.isLaunchable()) {
                return preserve(request, target, "thaw-readback-failed");
            }
            if (request.kind == Kind.THAW_ONLY || !request.autoOpen) {
                boolean replayed = false;
                if (request.ownsOriginal && request.kind != Kind.THAW_ONLY) {
                    launcher.fallback(request);
                    replayed = true;
                }
                remember(key, clock.elapsedRealtime());
                return new Result(Outcome.THAWED, target, "thawed", replayed);
            }
            try {
                notifyStage(request, target, Stage.OPENING);
                launcher.launch(request, target, selected.conversion);
            } catch (Throwable error) {
                return preserve(request, target, "launch-failed:" + error.getClass().getSimpleName());
            }
            remember(key, clock.elapsedRealtime());
            return new Result(Outcome.ROUTED, target, "routed", false);
        } catch (Throwable error) {
            return preserve(request, selectedTarget, "worker-" + error.getClass().getSimpleName());
        }
    }

    private void notifyStage(Request<P> request, String target, Stage stage) {
        try {
            observer.onStage(request, target, stage);
        } catch (Throwable ignored) {
            // A missing or broken system progress surface must not change the transaction.
        }
    }

    private Selection select(Request<P> request, List<LinkRuleEntry> rules) {
        if (request.kind != Kind.WEB_RULE) {
            if (request.explicitTarget == null || request.explicitTarget.isEmpty()) {
                return Selection.none("target-missing");
            }
            State state = packages.read(request.explicitTarget, request.userId);
            if (state == null || !state.isKnown()) return Selection.none("package-state-unknown");
            if (!state.installed) return Selection.none("not-installed-for-user");
            if (state.suspended) return Selection.none("suspended-unsupported");
            return new Selection(request.explicitTarget, LinkRuleEntry.CONVERSION_NONE, state, null);
        }
        List<LinkRuleEntry> matches = ScopedRuleResolver.matchingRules(rules,
                request.sourcePackage, request.host);
        if (matches.isEmpty()) return Selection.none("no-matching-rule");
        Map<String, State> states = new LinkedHashMap<>();
        List<LinkRuleEntry> installed = new ArrayList<>();
        for (LinkRuleEntry rule : matches) {
            // Installed targets in the first priority group shadow all lower groups. Do not
            // query or wake lower-priority applications after that group has been established.
            if (!installed.isEmpty() && !samePriority(installed.get(0), rule)) break;
            State state = states.get(rule.targetPackage);
            if (state == null) {
                state = packages.read(rule.targetPackage, request.userId);
                if (state == null || !state.isKnown()) return Selection.none("package-state-unknown");
                states.put(rule.targetPackage, state);
            }
            if (state.installed) installed.add(rule);
        }
        if (installed.isEmpty()) return Selection.none("not-installed-for-user");
        Map<String, State> eligible = new LinkedHashMap<>();
        for (LinkRuleEntry rule : installed) {
            State state = states.get(rule.targetPackage);
            if (state.suspended) return Selection.none("suspended-unsupported");
            if (state.isRecoverable() || (!request.onlyFrozen && state.isLaunchable())) {
                eligible.put(rule.targetPackage, state);
            }
        }
        LinkRuleEntry selected = ScopedRuleResolver.selectFrozenRule(installed, eligible.keySet());
        if (selected == null) return Selection.none("no-unambiguous-frozen-rule");
        // Conflicting conversions for the same winning target must not depend on input order.
        for (LinkRuleEntry rule : installed) {
            if (samePriority(selected, rule) && selected.targetPackage.equals(rule.targetPackage)
                    && !selected.urlConversion.equals(rule.urlConversion)) {
                return Selection.none("ambiguous-conversion");
            }
        }
        return new Selection(selected.targetPackage, selected.urlConversion,
                states.get(selected.targetPackage), null);
    }

    private static boolean samePriority(LinkRuleEntry left, LinkRuleEntry right) {
        return LinkRuleEntry.SOURCE_MANUAL.equals(left.source) == LinkRuleEntry.SOURCE_MANUAL.equals(right.source)
                && "*".equals(left.sourcePackage) == "*".equals(right.sourcePackage)
                && left.host.length() == right.host.length();
    }

    private Result preserve(Request<P> request, String target, String reason) {
        if (request.ownsOriginal && request.kind != Kind.THAW_ONLY) {
            try {
                launcher.fallback(request);
                return new Result(Outcome.PASSTHROUGH, target, reason, true);
            } catch (Throwable ignored) {
                return new Result(Outcome.FAILED, target, reason + ";fallback-failed", false);
            }
        }
        return new Result(Outcome.PASSTHROUGH, target, reason, false);
    }

    private void prune(long now) {
        successfulTransactions.entrySet().removeIf(entry -> now < entry.getValue()
                || now - entry.getValue() >= DEDUPLICATION_MILLIS);
    }

    private void remember(String key, long now) {
        while (successfulTransactions.size() >= MAX_DEDUPLICATION_ENTRIES) {
            successfulTransactions.remove(successfulTransactions.keySet().iterator().next());
        }
        successfulTransactions.put(key, now);
    }

    private static final class Selection {
        final String target;
        final String conversion;
        final State state;
        final String reason;

        Selection(String target, String conversion, State state, String reason) {
            this.target = target;
            this.conversion = conversion;
            this.state = state;
            this.reason = reason;
        }

        static Selection none(String reason) {
            return new Selection(null, LinkRuleEntry.CONVERSION_NONE, null, reason);
        }
    }
}
