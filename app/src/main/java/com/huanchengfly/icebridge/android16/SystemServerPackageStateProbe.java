package com.huanchengfly.icebridge.android16;

import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Process;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class SystemServerPackageStateProbe {
    private static final Field APPLICATION_PRIVATE_FLAGS = findApplicationPrivateFlags();
    private final Object packageManager;
    private final Method getApplicationEnabledSetting;
    private final Method setApplicationEnabledSetting;
    private final Method getApplicationInfo;
    private final Method setApplicationHiddenSettingAsUser;
    private final Method isPackageSuspended;
    private final String capability;

    private SystemServerPackageStateProbe(
            Object packageManager,
            Method getApplicationEnabledSetting,
            Method setApplicationEnabledSetting,
            Method getApplicationInfo,
            Method setApplicationHiddenSettingAsUser,
            Method isPackageSuspended,
            String capability
    ) {
        this.packageManager = packageManager;
        this.getApplicationEnabledSetting = getApplicationEnabledSetting;
        this.setApplicationEnabledSetting = setApplicationEnabledSetting;
        this.getApplicationInfo = getApplicationInfo;
        this.setApplicationHiddenSettingAsUser = setApplicationHiddenSettingAsUser;
        this.isPackageSuspended = isPackageSuspended;
        this.capability = capability;
    }

    public static SystemServerPackageStateProbe create() {
        try {
            Object proxy = findPackageManagerProxy();
            if (proxy == null) {
                return unavailable("package-proxy=missing");
            }
            Method enabled = find(proxy.getClass(), "getApplicationEnabledSetting", String.class, Integer.TYPE);
            Method setEnabled = find(proxy.getClass(), "setApplicationEnabledSetting", String.class, Integer.TYPE, Integer.TYPE, Integer.TYPE, String.class);
            Method applicationInfo = find(proxy.getClass(), "getApplicationInfo", String.class, Integer.TYPE, Integer.TYPE);
            Method setHidden = find(proxy.getClass(), "setApplicationHiddenSettingAsUser",
                    String.class, Boolean.TYPE, Integer.TYPE, Integer.TYPE, String.class);
            Method suspended = findFirst(proxy.getClass(), String.class, Integer.TYPE,
                    "isPackageSuspendedForUser", "isPackageSuspended");
            String capability = "package-binder=ready;proxy=" + proxy.getClass().getName()
                    + ";enabled=" + (enabled != null)
                    + ";suspended=" + (suspended != null)
                    + ";setEnabled=" + (setEnabled != null)
                    + ";hidden=" + (applicationInfo != null)
                    + ";setHidden=" + (setHidden != null)
                    + ";unsuspend=" + hasMethod(proxy.getClass(), "setPackagesSuspendedAsUser");
            return new SystemServerPackageStateProbe(proxy, enabled, setEnabled, applicationInfo, setHidden, suspended, capability);
        } catch (Throwable error) {
            return unavailable("package-probe-error=" + error.getClass().getSimpleName());
        }
    }

    public String getCapability() {
        return capability;
    }

    public boolean isReady() {
        return packageManager != null && getApplicationEnabledSetting != null;
    }

    public boolean canEnableDisabledPackage() {
        return isReady() && (setApplicationEnabledSetting != null || setApplicationHiddenSettingAsUser != null);
    }

    public String enableDisabledPackage(String packageName, int userId) {
        if (!canEnableDisabledPackage()) {
            return "setter-unavailable";
        }
        // resolveIntent runs under the launching app's Binder identity. PackageManager must see
        // system_server here; otherwise it rejects the state change as if the target app requested it.
        long identityToken = Binder.clearCallingIdentity();
        try {
            if (setApplicationEnabledSetting != null) {
                setApplicationEnabledSetting.invoke(packageManager, packageName, 1, 0, userId, "android");
            }
            if (setApplicationHiddenSettingAsUser != null) {
                setApplicationHiddenSettingAsUser.invoke(
                        packageManager, packageName, false, userId, Process.SYSTEM_UID, "android");
            }
            State state = read(packageName, userId);
            if (!state.isAvailable()) {
                return "readback-" + state.error;
            }
            return state.isLaunchable() ? null : "readback-state-" + state.enabledSetting
                    + (state.hidden ? ",hidden" : "");
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException && ((InvocationTargetException) error).getCause() != null
                    ? ((InvocationTargetException) error).getCause() : error;
            return "setter-" + cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ":" + cause.getMessage());
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
    }

    public State read(String packageName, int userId) {
        if (packageManager == null || getApplicationEnabledSetting == null) {
            return State.unavailable();
        }
        try {
            int enabledSetting = (Integer) getApplicationEnabledSetting.invoke(packageManager, packageName, userId);
            boolean suspended = false;
            boolean suspendedKnown = false;
            if (isPackageSuspended != null) {
                suspended = (Boolean) isPackageSuspended.invoke(packageManager, packageName, userId);
                suspendedKnown = true;
            }
            boolean hidden = false;
            boolean hiddenKnown = false;
            if (getApplicationInfo != null) {
                ApplicationInfo applicationInfo = (ApplicationInfo) getApplicationInfo.invoke(
                        packageManager, packageName, 0, userId);
                if (applicationInfo != null) {
                    if (APPLICATION_PRIVATE_FLAGS != null) {
                        hidden = ((((Integer) APPLICATION_PRIVATE_FLAGS.get(applicationInfo)) & 1) != 0);
                        hiddenKnown = true;
                    }
                }
            }
            return new State(enabledSetting, hidden, hiddenKnown, suspended, suspendedKnown, null);
        } catch (Throwable error) {
            return new State(-1, false, false, false, false, error.getClass().getSimpleName());
        }
    }

    private static Object findPackageManagerProxy() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object proxy = activityThread.getMethod("getPackageManager").invoke(null);
            if (proxy != null) {
                return proxy;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            Object proxy = appGlobals.getMethod("getPackageManager").invoke(null);
            if (proxy != null) {
                return proxy;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
            Class<?> binderClass = Class.forName("android.os.IBinder");
            Object binder = findPackageBinder();
            return binder == null ? null : stub.getMethod("asInterface", binderClass).invoke(null, binder);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object findPackageBinder() {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Object binder = serviceManager.getMethod("getService", String.class).invoke(null, "package");
            if (binder != null) {
                return binder;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getServiceOrThrow = serviceManager.getMethod("getServiceOrThrow", String.class);
            return getServiceOrThrow.invoke(null, "package");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static SystemServerPackageStateProbe unavailable(String capability) {
        return new SystemServerPackageStateProbe(null, null, null, null, null, null, capability);
    }

    private static Method findFirst(Class<?> owner, Class<?> firstParameter, Class<?> secondParameter, String... names) {
        for (String name : names) {
            Method method = find(owner, name, firstParameter, secondParameter);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    private static Method find(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            Method method = owner.getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findApplicationPrivateFlags() {
        try {
            Field field = ApplicationInfo.class.getDeclaredField("privateFlags");
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasMethod(Class<?> owner, String name) {
        for (Method method : owner.getMethods()) {
            if (name.equals(method.getName())) {
                return true;
            }
        }
        return false;
    }

    public static final class State {
        public final int enabledSetting;
        public final boolean hidden;
        public final boolean hiddenKnown;
        public final boolean suspended;
        public final boolean suspendedKnown;
        public final String error;

        State(int enabledSetting, boolean hidden, boolean hiddenKnown, boolean suspended,
              boolean suspendedKnown, String error) {
            this.enabledSetting = enabledSetting;
            this.hidden = hidden;
            this.hiddenKnown = hiddenKnown;
            this.suspended = suspended;
            this.suspendedKnown = suspendedKnown;
            this.error = error;
        }

        static State unavailable() {
            return new State(-1, false, false, false, false, "capability-unavailable");
        }

        public boolean isAvailable() {
            return error == null;
        }

        public boolean isLaunchable() {
            return !hidden && (enabledSetting == 0 || enabledSetting == 1);
        }
    }
}
