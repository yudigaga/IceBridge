package com.huanchengfly.icebridge.android16;

import android.content.Intent;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public final class AtmsSignatureResolver {
    private static final List<String> INTERCEPT_PARAMETERS = Arrays.asList(
            Intent.class.getName(),
            "android.content.pm.ResolveInfo",
            "android.content.pm.ActivityInfo",
            String.class.getName(),
            "com.android.server.wm.Task",
            "com.android.server.wm.TaskFragment",
            "int",
            "int",
            "android.app.ActivityOptions",
            "com.android.server.wm.TaskDisplayArea",
            "boolean"
    );

    private AtmsSignatureResolver() {
    }

    public static ResolvedMethod resolveActivityStartInterceptor(ClassLoader classLoader) {
        try {
            Class<?> owner = classLoader.loadClass("com.android.server.wm.ActivityStartInterceptor");
            for (Method method : owner.getDeclaredMethods()) {
                if (!"intercept".equals(method.getName()) || method.getReturnType() != Boolean.TYPE) {
                    continue;
                }
                Class<?>[] p = method.getParameterTypes();
                // Android 12-16 changed the tail repeatedly (Task/TaskFragment/options and
                // componentSpecified were added). The stable prefix identifies this entry point.
                if (p.length < 4 || p[0] != Intent.class
                        || !"android.content.pm.ResolveInfo".equals(p[1].getName())
                        || !"android.content.pm.ActivityInfo".equals(p[2].getName())
                        || p[3] != String.class) {
                    continue;
                }
                method.setAccessible(true);
                return new ResolvedMethod(method, method.toGenericString());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static ResolvedMethod resolveActivityStarterExecute(ClassLoader classLoader) {
        try {
            Class<?> owner = classLoader.loadClass("com.android.server.wm.ActivityStarter");
            for (Method method : owner.getDeclaredMethods()) {
                if ("execute".equals(method.getName()) && method.getParameterTypes().length == 0) {
                    method.setAccessible(true);
                    return new ResolvedMethod(method, method.toGenericString());
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static ResolvedMethod resolveActivityTaskSupervisorResolveIntent(ClassLoader classLoader) {
        try {
            Class<?> owner = classLoader.loadClass("com.android.server.wm.ActivityTaskSupervisor");
            Method method = owner.getDeclaredMethod(
                    "resolveIntent",
                    Intent.class,
                    String.class,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE
            );
            method.setAccessible(true);
            return new ResolvedMethod(method, method.toGenericString());
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static final class ResolvedMethod {
        public final Method method;
        public final String description;

        ResolvedMethod(Method method, String description) {
            this.method = method;
            this.description = description;
        }
    }
}
