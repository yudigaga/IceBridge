package com.huanchengfly.icebridge.android16;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Process;

import com.huanchengfly.icebridge.BuildConfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** System-UID launch with no unregistered ActivityThread caller and a checked ATMS result. */
public final class SystemActivityLauncher {
    private SystemActivityLauncher() { }

    public static final class StartRejectedException extends Exception {
        public final int result;

        StartRejectedException(int result) {
            super("system-start-rejected:" + result);
            this.result = result;
        }
    }

    public static int start(Context systemContext, Intent intent, int userId) throws Exception {
        if (Process.myUid() != Process.SYSTEM_UID) throw new SecurityException("system-uid-required");
        if (systemContext == null || intent == null || userId < 0) {
            throw new IllegalArgumentException("invalid-system-launch");
        }
        String target = intent.getComponent() == null ? intent.getPackage()
                : intent.getComponent().getPackageName();
        if (BuildConfig.APPLICATION_ID.equals(target)) throw new IllegalArgumentException("self-launch-prohibited");
        long identity = Binder.clearCallingIdentity();
        try {
            Class<?> activityTaskManager = Class.forName("android.app.ActivityTaskManager");
            Object service = activityTaskManager.getMethod("getService").invoke(null);
            if (service == null) throw new IllegalStateException("activity-task-service-unavailable");
            Method start = findStartMethod(service.getClass());
            if (start == null) throw new IllegalStateException("activity-task-start-signature-unavailable");
            // A standalone system-UID process (and some system contexts) has no AMS-registered
            // IApplicationThread. Passing it can return START_PERMISSION_DENIED (-94) silently
            // through Context's void wrapper. A null caller retains the authenticated system UID.
            Object result = start.invoke(service, null, "android", null, intent,
                    intent.resolveTypeIfNeeded(systemContext.getContentResolver()),
                    null, null, -1, 0, null, null, userId);
            return requireSuccessfulResult(result);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    static Method findStartMethod(Class<?> serviceClass) {
        for (Method method : serviceClass.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            String[] names = new String[parameters.length];
            for (int i = 0; i < names.length; i++) names[i] = parameters[i].getName();
            if (AtmsBinderSignatureResolver.matches(method.getName(), method.getModifiers(),
                    method.getReturnType().getName(), names)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    /** AOSP's success interval excludes positive nonfatal failures such as START_ABORTED=102. */
    public static int requireSuccessfulResult(Object value) throws StartRejectedException {
        if (!(value instanceof Integer)) throw new IllegalStateException("activity-task-result-unavailable");
        int result = (Integer) value;
        if (result < 0 || result > 99) throw new StartRejectedException(result);
        return result;
    }
}
