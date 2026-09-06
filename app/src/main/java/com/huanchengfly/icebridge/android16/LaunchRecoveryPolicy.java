package com.huanchengfly.icebridge.android16;

import android.content.ComponentName;
import android.content.Intent;

public final class LaunchRecoveryPolicy {
    private static final int COMPONENT_ENABLED_STATE_DISABLED = 2;

    private LaunchRecoveryPolicy() {
    }

    /**
     * Returns a target for explicit and package-scoped intents. A package-scoped intent has no
     * component but still names exactly one application, so it is safe to recover that app.
     */
    public static String getExplicitTargetPackage(Intent intent, int userId) {
        if (intent == null || userId < 0) {
            return null;
        }
        if (intent.getComponent() != null) {
            return packageName(intent.getComponent());
        }
        String packageName = intent.getPackage();
        return packageName == null || packageName.isEmpty() ? null : packageName;
    }

    public static boolean isRecoverableState(int enabledSetting, boolean suspended) {
        return isRecoverableState(enabledSetting, false, true, suspended);
    }

    public static boolean isRecoverableState(
            int enabledSetting,
            boolean hidden,
            boolean hiddenKnown,
            boolean suspended
    ) {
        boolean disabled = enabledSetting == COMPONENT_ENABLED_STATE_DISABLED
                || enabledSetting == 3
                || enabledSetting == 4;
        return !suspended && (disabled || (hiddenKnown && hidden));
    }

    private static String packageName(ComponentName component) {
        String packageName = component.getPackageName();
        return packageName == null || packageName.isEmpty() ? null : packageName;
    }
}
