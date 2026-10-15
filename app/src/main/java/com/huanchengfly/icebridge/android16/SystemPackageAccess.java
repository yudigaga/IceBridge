package com.huanchengfly.icebridge.android16;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;

import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** System-server-only PackageManager adapter. Every method uses the explicit request user. */
public final class SystemPackageAccess implements AutonomousBridgeCoordinator.PackageAccess {
    private static final long QUERY_FLAGS = PackageManager.MATCH_UNINSTALLED_PACKAGES
            | PackageManager.MATCH_DISABLED_COMPONENTS;
    private static final int PRIVATE_FLAG_HIDDEN = 1;
    private static final Field PRIVATE_FLAGS = findField(ApplicationInfo.class, "privateFlags");
    private final Object packageManager;
    private final Method applicationInfo;
    private final Method enabledSetting;
    private final Method hiddenSetting;
    private final Method suspendedSetting;
    private final Method setEnabled;
    private final Method setHidden;
    private final boolean longFlags;
    private final AutonomousBridgeCoordinator.Capabilities capabilities;

    private SystemPackageAccess(Object packageManager, Method applicationInfo, Method enabledSetting,
                                Method hiddenSetting, Method suspendedSetting, Method setEnabled,
                                Method setHidden, boolean longFlags, String error) {
        this.packageManager = packageManager;
        this.applicationInfo = applicationInfo;
        this.enabledSetting = enabledSetting;
        this.hiddenSetting = hiddenSetting;
        this.suspendedSetting = suspendedSetting;
        this.setEnabled = setEnabled;
        this.setHidden = setHidden;
        this.longFlags = longFlags;
        this.capabilities = new AutonomousBridgeCoordinator.Capabilities(setEnabled != null, setHidden != null,
                error != null ? error : "package-binder=ready;applicationInfo=" + (applicationInfo != null)
                        + ";flags=" + (longFlags ? "long" : "int")
                        + ";installed=" + (applicationInfo != null)
                        + ";enabled=" + (enabledSetting != null)
                        + ";hidden=" + (hiddenSetting != null || PRIVATE_FLAGS != null)
                        + ";suspended=" + (applicationInfo != null || suspendedSetting != null)
                        + ";setEnabled=" + (setEnabled != null)
                        + ";setHidden=" + (setHidden != null)
                        + ";unsuspend=false");
    }

    public static SystemPackageAccess create() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object proxy = activityThread.getMethod("getPackageManager").invoke(null);
            return fromPackageManager(proxy);
        } catch (Throwable error) {
            try {
                Class<?> appGlobals = Class.forName("android.app.AppGlobals");
                return fromPackageManager(appGlobals.getMethod("getPackageManager").invoke(null));
            } catch (Throwable ignored) {
                return unavailable("package-proxy-unavailable");
            }
        }
    }

    /** Public for platform compatibility probes; no Android Context or IceBridge process is used. */
    public static SystemPackageAccess fromPackageManager(Object proxy) {
        if (proxy == null) return unavailable("package-proxy-missing");
        Class<?> owner = proxy.getClass();
        Method info = find(owner, "getApplicationInfo", String.class, Long.TYPE, Integer.TYPE);
        boolean longFlags = info != null;
        if (info == null) info = find(owner, "getApplicationInfo", String.class, Integer.TYPE, Integer.TYPE);
        Method enabled = find(owner, "getApplicationEnabledSetting", String.class, Integer.TYPE);
        Method hidden = find(owner, "getApplicationHiddenSettingAsUser", String.class, Integer.TYPE);
        Method suspended = find(owner, "isPackageSuspendedForUser", String.class, Integer.TYPE);
        if (suspended == null) suspended = find(owner, "isPackageSuspended", String.class, Integer.TYPE);
        Method setter = find(owner, "setApplicationEnabledSetting", String.class, Integer.TYPE,
                Integer.TYPE, Integer.TYPE, String.class);
        Method hiddenSetter = find(owner, "setApplicationHiddenSettingAsUser", String.class,
                Boolean.TYPE, Integer.TYPE);
        if (hiddenSetter == null) hiddenSetter = find(owner, "setApplicationHiddenSettingAsUser",
                String.class, Boolean.TYPE, Integer.TYPE, Integer.TYPE, String.class);
        return new SystemPackageAccess(proxy, info, enabled, hidden, suspended, setter, hiddenSetter,
                longFlags, null);
    }

    @Override public AutonomousBridgeCoordinator.Capabilities capabilities() { return capabilities; }

    public boolean isReady() {
        return packageManager != null && applicationInfo != null && enabledSetting != null
                && (hiddenSetting != null || PRIVATE_FLAGS != null);
    }

    @Override public AutonomousBridgeCoordinator.State read(String packageName, int userId) {
        if (userId < 0 || packageName == null || packageName.isEmpty()) {
            return AutonomousBridgeCoordinator.State.unknown("invalid-package-or-user");
        }
        if (packageManager == null || applicationInfo == null || enabledSetting == null) {
            return AutonomousBridgeCoordinator.State.unknown("package-read-capability-unavailable");
        }
        long identity = Binder.clearCallingIdentity();
        try {
            Object flags;
            // Avoid numeric conditional promotion: reflection requires an Integer for old APIs.
            if (longFlags) flags = Long.valueOf(QUERY_FLAGS);
            else flags = Integer.valueOf((int) QUERY_FLAGS);
            ApplicationInfo info = (ApplicationInfo) applicationInfo.invoke(packageManager,
                    packageName, flags, userId);
            if (info == null || (info.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
                return AutonomousBridgeCoordinator.State.known(false, 0, false, false, false);
            }
            int enabled = (Integer) enabledSetting.invoke(packageManager, packageName, userId);
            boolean hidden = false;
            boolean hiddenKnown = false;
            if (hiddenSetting != null) {
                hidden = (Boolean) hiddenSetting.invoke(packageManager, packageName, userId);
                hiddenKnown = true;
            } else if (PRIVATE_FLAGS != null) {
                hidden = (((Integer) PRIVATE_FLAGS.get(info)) & PRIVATE_FLAG_HIDDEN) != 0;
                hiddenKnown = true;
            }
            // FLAG_SUSPENDED is public since API 24; a failed explicit OEM query fails closed.
            boolean suspended = suspendedSetting == null
                    ? (info.flags & ApplicationInfo.FLAG_SUSPENDED) != 0
                    : (Boolean) suspendedSetting.invoke(packageManager, packageName, userId);
            return new AutonomousBridgeCoordinator.State(true, true, enabled, true, info.enabled,
                    hidden, hiddenKnown, suspended, true, null);
        } catch (Throwable error) {
            return AutonomousBridgeCoordinator.State.unknown("package-read-" + errorName(error));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override public String thaw(String packageName, int userId, AutonomousBridgeCoordinator.State before) {
        if (userId < 0 || packageName == null || before == null || !before.isRecoverable()) {
            return "invalid-state";
        }
        // A management app may have changed state while the worker selected among several
        // candidates. Recheck immediately before mutating, especially installation/suspension.
        AutonomousBridgeCoordinator.State current = read(packageName, userId);
        if (current.isLaunchable()) return null;
        if (!current.isRecoverable()) return "state-changed";
        if ((current.isDisabled() && setEnabled == null) || (current.hidden && setHidden == null)) {
            return "setter-unavailable";
        }
        long identity = Binder.clearCallingIdentity();
        try {
            // Only undo the freeze methods that were positively observed. Suspension and
            // installation state are never changed, including restrictions owned by device policy.
            if (current.isDisabled()) {
                setEnabled.invoke(packageManager, packageName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        0, userId, "android");
            }
            if (current.hidden) {
                Object result;
                if (setHidden.getParameterTypes().length == 3) {
                    result = setHidden.invoke(packageManager, packageName, false, userId);
                } else {
                    result = setHidden.invoke(packageManager, packageName, false, userId,
                            Process.SYSTEM_UID, "android");
                }
                if (Boolean.FALSE.equals(result)) return "unhide-rejected";
            }
            return null;
        } catch (Throwable error) {
            return "setter-" + errorName(error);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static SystemPackageAccess unavailable(String error) {
        return new SystemPackageAccess(null, null, null, null, null, null, null, false, error);
    }

    private static Method find(Class<?> owner, String name, Class<?>... arguments) {
        try {
            Method method = owner.getMethod(name, arguments);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) { return null; }
    }

    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) { return null; }
    }

    private static String errorName(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause() : error;
        return cause.getClass().getSimpleName();
    }
}
