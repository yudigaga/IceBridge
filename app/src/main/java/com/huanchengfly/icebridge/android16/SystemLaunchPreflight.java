package com.huanchengfly.icebridge.android16;

import android.content.Intent;

import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;

import java.lang.reflect.Field;
import java.util.Objects;

/**
 * Narrow synchronous package repair at the public Binder entry, before any ATMS launch lock.
 * The framework executes its original request afterward; no launch, replay or synthetic success.
 */
public final class SystemLaunchPreflight {
    public enum Outcome { THAWED, NO_ACTION, SKIPPED, FAILED }

    public static final class Result {
        public final Outcome outcome;
        public final String targetPackage;
        public final String reason;

        Result(Outcome outcome, String targetPackage, String reason) {
            this.outcome = outcome;
            this.targetPackage = targetPackage;
            this.reason = reason;
        }
    }

    private final String protectedPackage;
    private final AutonomousBridgeCoordinator.PackageAccess packages;

    public SystemLaunchPreflight(String protectedPackage, AutonomousBridgeCoordinator.PackageAccess packages) {
        this.protectedPackage = Objects.requireNonNull(protectedPackage, "protectedPackage");
        this.packages = Objects.requireNonNull(packages, "packages");
    }

    /** Inspect an original Intent; never mutate its fields, extras, resultTo or calling identity. */
    public Result prepare(Intent original, int userId, int callerUserId, boolean outsideGlobalLock) {
        if (original == null || original.getSelector() != null) {
            return new Result(Outcome.SKIPPED, null, "no-unambiguous-original-target");
        }
        String target = original.getComponent() == null ? original.getPackage()
                : original.getComponent().getPackageName();
        // A forged SDK-looking class name must never undo the owner's own freeze.
        if (protectedPackage.equals(target)) {
            return new Result(Outcome.SKIPPED, target, "self-thaw-prohibited");
        }
        PaymentSdkBridgeResolver.Candidate payment = PaymentSdkBridgeResolver.resolve(original);
        if ((payment != null && payment.prewarmOnly) || isStrictAlipaySdkHost(original)) {
            // Carrier/merchant SDK Activities must keep their original host/caller/result token.
            // Prewarming the wallet here never launches or replays the SDK Activity itself.
            target = PaymentSdkBridgeResolver.ALIPAY_PACKAGE;
        } else if (target == null) {
            target = CustomSchemeTargetResolver.findTargetPackageForCustomSchemeUri(
                    CustomSchemeTargetResolver.findCustomSchemeUri(original));
        }
        return thawTarget(target, userId, callerUserId, outsideGlobalLock);
    }

    /** Must match the preflight's SDK selection so the payment switch gates host prewarming too. */
    public static boolean isPaymentSdkRequest(Intent original) {
        return original != null && (PaymentSdkBridgeResolver.resolve(original) != null
                || isStrictAlipaySdkHost(original));
    }

    private static boolean isStrictAlipaySdkHost(Intent original) {
        if (original == null || original.getComponent() == null) return false;
        String className = original.getComponent().getClassName();
        return className.startsWith("com.alipay.sdk.app.")
                || className.startsWith("com.alipay.android.app.");
    }

    /** Pure transaction seam used with real PM in production and deterministic adapters in tests. */
    public Result thawTarget(String target, int userId, int callerUserId, boolean outsideGlobalLock) {
        if (!outsideGlobalLock) return new Result(Outcome.SKIPPED, target, "launch-lock-or-local-call");
        if (userId < 0 || callerUserId < 0 || userId != callerUserId) {
            return new Result(Outcome.SKIPPED, target, "unknown-or-cross-user");
        }
        if (target == null || target.isEmpty()) return new Result(Outcome.NO_ACTION, null, "no-explicit-target");
        if (protectedPackage.equals(target)) return new Result(Outcome.SKIPPED, target, "self-thaw-prohibited");
        try {
            AutonomousBridgeCoordinator.State before = packages.read(target, userId);
            if (before == null || !before.isKnown()) return new Result(Outcome.SKIPPED, target, "package-state-unknown");
            if (!before.installed) return new Result(Outcome.NO_ACTION, target, "not-installed-for-user");
            if (before.isLaunchable()) return new Result(Outcome.NO_ACTION, target, "already-launchable");
            if (before.suspended) return new Result(Outcome.SKIPPED, target, "suspended-unsupported");
            if (!before.isRecoverable()) return new Result(Outcome.SKIPPED, target, "freeze-method-unsupported");
            AutonomousBridgeCoordinator.Capabilities ability = packages.capabilities();
            if (ability == null || (before.isDisabled() && !ability.enableDisabled)
                    || (before.hidden && !ability.unhide)) {
                return new Result(Outcome.SKIPPED, target, "setter-unavailable");
            }
            String failed = packages.thaw(target, userId, before);
            if (failed != null) return new Result(Outcome.FAILED, target, "thaw-" + failed);
            AutonomousBridgeCoordinator.State after = packages.read(target, userId);
            if (after == null || !after.isLaunchable()) return new Result(Outcome.FAILED, target, "thaw-readback-failed");
            return new Result(Outcome.THAWED, target, "thawed-preserving-original-call");
        } catch (Throwable error) {
            return new Result(Outcome.FAILED, target, "package-" + error.getClass().getSimpleName());
        }
    }

    /**
     * Check this at every Binder hook invocation. OEM/local re-entry can already own mGlobalLock
     * even though the public AOSP entry does not acquire it. A missing field is unsupported.
     */
    public static boolean isOutsideGlobalLock(Object service, int callingPid, int currentPid) {
        if (service == null || callingPid <= 0 || callingPid == currentPid) return false;
        for (Class<?> owner = service.getClass(); owner != null; owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField("mGlobalLock");
                field.setAccessible(true);
                Object lock = field.get(service);
                return lock != null && !Thread.holdsLock(lock);
            } catch (NoSuchFieldException ignored) {
                // OEM subclasses may inherit the global lock from the framework base service.
            } catch (Throwable ignored) { return false; }
        }
        return false;
    }
}
