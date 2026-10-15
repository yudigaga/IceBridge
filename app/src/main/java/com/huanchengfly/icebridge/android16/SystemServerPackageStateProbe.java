package com.huanchengfly.icebridge.android16;

import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;

/** Compatibility facade shared by the original payment path and autonomous package adapter. */
public final class SystemServerPackageStateProbe {
    private final SystemPackageAccess access;

    private SystemServerPackageStateProbe(SystemPackageAccess access) {
        this.access = access;
    }

    public static SystemServerPackageStateProbe create() {
        return new SystemServerPackageStateProbe(SystemPackageAccess.create());
    }

    public String getCapability() { return access.capabilities().description; }

    public boolean isReady() { return access.isReady(); }

    public boolean canEnableDisabledPackage() {
        AutonomousBridgeCoordinator.Capabilities capability = access.capabilities();
        return isReady() && (capability.enableDisabled || capability.unhide);
    }

    public String enableDisabledPackage(String packageName, int userId) {
        if (!canEnableDisabledPackage()) return "setter-unavailable";
        AutonomousBridgeCoordinator.State before = access.read(packageName, userId);
        if (!before.isKnown()) return "readback-" + before.error;
        if (!before.installed) return "not-installed-for-user";
        if (before.suspended) return "suspended-unsupported";
        if (before.isLaunchable()) return null;
        if (!before.isRecoverable()) return "state-not-recoverable";
        String failure = access.thaw(packageName, userId, before);
        if (failure != null) return failure;
        AutonomousBridgeCoordinator.State after = access.read(packageName, userId);
        if (!after.isKnown()) return "readback-" + after.error;
        return after.isLaunchable() ? null : "readback-state-" + after.enabledSetting
                + (after.hidden ? ",hidden" : "") + (after.suspended ? ",suspended" : "");
    }

    public State read(String packageName, int userId) {
        return new State(access.read(packageName, userId));
    }

    public static final class State {
        public final int enabledSetting;
        public final boolean hidden;
        public final boolean hiddenKnown;
        public final boolean suspended;
        public final boolean suspendedKnown;
        public final boolean installed;
        public final boolean installedKnown;
        public final String error;
        private final AutonomousBridgeCoordinator.State value;

        private State(AutonomousBridgeCoordinator.State value) {
            this.value = value;
            enabledSetting = value.enabledSetting;
            hidden = value.hidden;
            hiddenKnown = value.hiddenKnown;
            suspended = value.suspended;
            suspendedKnown = value.suspendedKnown;
            installed = value.installed;
            installedKnown = value.installedKnown;
            error = value.error;
        }

        public boolean isAvailable() { return value.isKnown(); }

        public boolean isLaunchable() { return value.isLaunchable(); }
    }
}
