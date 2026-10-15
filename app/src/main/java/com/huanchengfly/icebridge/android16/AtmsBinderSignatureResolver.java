package com.huanchengfly.icebridge.android16;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Exact AOSP 14/15/16 public Binder entry, before ActivityStarter acquires launch locks. */
public final class AtmsBinderSignatureResolver {
    public static final int CALLING_PACKAGE_INDEX = 1;
    public static final int INTENT_INDEX = 3;
    public static final int RESULT_TO_INDEX = 5;
    public static final int USER_ID_INDEX = 11;
    private static final String[] PARAMETERS = {
            "android.app.IApplicationThread", "java.lang.String", "java.lang.String",
            "android.content.Intent", "java.lang.String", "android.os.IBinder", "java.lang.String",
            "int", "int", "android.app.ProfilerInfo", "android.os.Bundle", "int"
    };

    private AtmsBinderSignatureResolver() { }

    public static Method resolve(ClassLoader loader) {
        try {
            Class<?> owner = loader.loadClass("com.android.server.wm.ActivityTaskManagerService");
            for (Method method : owner.getDeclaredMethods()) {
                String[] names = new String[method.getParameterTypes().length];
                for (int i = 0; i < names.length; i++) names[i] = method.getParameterTypes()[i].getName();
                if (matches(method.getName(), method.getModifiers(), method.getReturnType().getName(), names)) {
                    method.setAccessible(true);
                    return method;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public static boolean matches(String name, int modifiers, String returnType, String[] parameters) {
        return "startActivityAsUser".equals(name) && "int".equals(returnType)
                && Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers)
                && !Modifier.isSynchronized(modifiers) && Arrays.equals(PARAMETERS, parameters);
    }
}
