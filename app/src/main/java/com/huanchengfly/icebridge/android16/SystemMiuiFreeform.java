package com.huanchengfly.icebridge.android16;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Optional MIUI geometry capabilities. All reads operate on local framework objects. */
public final class SystemMiuiFreeform {
    private SystemMiuiFreeform() { }

    /** Keeps launch bounds in display pixels instead of applying MIUI's default extra scale. */
    public static boolean configureUnscaledLaunch(Object options) {
        try {
            if (options == null) return false;
            Object injector = invoke(options, "getActivityOptionsInjector");
            if (injector == null) return false;
            Method setter = method(injector.getClass(), "setFreeformScale", float.class);
            if (setter == null) return false;
            setter.invoke(injector, 1f);
            Method getter = method(injector.getClass(), "getFreeformScale");
            Object value = getter == null ? field(injector, "mFreeformScale")
                    : getter.invoke(injector);
            return value instanceof Number && ((Number) value).floatValue() == 1f;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** Reads the native stack scale without looking up a service or making a Binder call. */
    public static Float taskScale(Object task, int taskId) {
        try {
            if (task == null || taskId < 0) return null;
            Object atms = field(task, "mAtmService");
            return atmsScale(atms, taskId);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** The worker may pass the in-process ATMS service; remote Binder proxies have no vendor fields. */
    public static Float atmsScale(Object atms, int taskId) {
        try {
            if (atms == null || taskId < 0) return null;
            Object manager = field(atms, "mMiuiFreeFormManagerService");
            if (manager == null) return null;
            Method getter = method(manager.getClass(), "getMiuiFreeFormActivityStack", int.class);
            return getter == null ? null : stackScale(getter.invoke(manager, taskId));
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** Returns null when the vendor stack cannot supply a finite, plausible scale. */
    public static Float stackScale(Object stack) {
        try {
            if (stack == null) return null;
            Object raw = invoke(stack, "getFreeFormScale");
            if (!(raw instanceof Number)) return null;
            float scale = ((Number) raw).floatValue();
            // Native mini windows can be 0.25. Reject corrupt/extreme geometry rather
            // than moving a system window based on an unusable vendor value.
            return Float.isFinite(scale) && scale >= 0.05f && scale <= 2f ? scale : null;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    private static Object invoke(Object owner, String name) throws Exception {
        Method method = method(owner.getClass(), name);
        return method == null ? null : method.invoke(owner);
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    private static Object field(Object owner, String name) throws IllegalAccessException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
}
