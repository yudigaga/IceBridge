package com.huanchengfly.icebridge.android16;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/** Local field/method reads only: safe to snapshot after Task configuration under the WM lock. */
public final class SystemTaskBoundsObserver {
    private final WeakHashMap<Object, Long> instances = new WeakHashMap<>();
    private final SystemTargetWindowObserver launches;
    private long nextInstance;

    public SystemTaskBoundsObserver() { this(new SystemTargetWindowObserver()); }
    public SystemTaskBoundsObserver(SystemTargetWindowObserver launches) { this.launches = launches; }

    public TransitTaskBounds readActivity(Object activity) {
        try { return activity == null ? null : read(invoke(activity, "getTask")); }
        catch (Throwable unavailable) { return null; }
    }

    public synchronized TransitTaskBounds read(Object task) {
        try {
            if (task == null || !Boolean.TRUE.equals(invoke(task, "isLeafTask"))) return null;
            Object activity = invoke(task, "getTopNonFinishingActivity");
            if (activity == null) return null;
            Object pkg = field(activity, "packageName");
            Object taskUser = field(task, "mUserId");
            Object activityUser = field(activity, "mUserId");
            if (!(activityUser instanceof Integer)) activityUser = field(activity, "userId");
            if (!(pkg instanceof String) || ((String) pkg).isEmpty()
                    || !(taskUser instanceof Integer) || !taskUser.equals(activityUser)) return null;
            Object id = field(task, "mTaskId");
            Object mode = invoke(task, "getWindowingMode");
            Object display = invoke(task, "getDisplayId");
            Object rawBounds = invoke(task, "getBounds");
            if (!(id instanceof Integer) || !(mode instanceof Integer)
                    || !(display instanceof Integer) || !(rawBounds instanceof Rect)) return null;
            Rect bounds = (Integer) mode == 5
                    ? visualBounds((Rect) rawBounds, SystemMiuiFreeform.taskScale(task, (Integer) id))
                    : new Rect((Rect) rawBounds);
            long transit = launches.transitId(activity);
            Long instance = instances.get(task);
            if (instance == null) {
                // Identity must stay stable while a Task is alive, including during a busy
                // multi-window session. Weak keys release removed tasks without invalidating others.
                instance = ++nextInstance;
                instances.put(task, instance);
            }
            return new TransitTaskBounds(transit, instance, (Integer) id, (Integer) taskUser,
                    (String) pkg, (Integer) display, (Integer) mode, bounds.left, bounds.top,
                    bounds.right, bounds.bottom, SystemClock.elapsedRealtime());
        } catch (Throwable unavailable) { return null; }
    }

    /** Worker-only public task query supplies the initial position of an already existing window. */
    public static TransitTaskBounds runningTask(ActivityManager.RunningTaskInfo info) {
        try {
            if (info == null || info.topActivity == null
                    || !Boolean.TRUE.equals(field(info, "isVisible"))) return null;
            Object user = field(info, "userId");
            Object display = field(info, "displayId");
            Object config = field(info, "configuration");
            Object windowConfig = config == null ? null : field(config, "windowConfiguration");
            Object mode = windowConfig == null ? null : invoke(windowConfig, "getWindowingMode");
            Object bounds = windowConfig == null ? null : invoke(windowConfig, "getBounds");
            if (!(user instanceof Integer) || !(display instanceof Integer)
                    || !(mode instanceof Integer) || !(bounds instanceof Rect)) return null;
            Rect rect = (Rect) bounds;
            if ((Integer) mode == 5) {
                // Worker-only path. In system_server getService returns the local ATMS; in an
                // ordinary app it returns a proxy and the optional vendor field read is a no-op.
                Float scale = null;
                try {
                    Object service = Class.forName("android.app.ActivityTaskManager")
                            .getMethod("getService").invoke(null);
                    scale = SystemMiuiFreeform.atmsScale(service, info.id);
                } catch (Throwable unavailable) { /* Keep unscaled AOSP geometry. */ }
                rect = visualBounds(rect, scale);
            }
            ComponentName top = info.topActivity;
            return new TransitTaskBounds(0, 0, info.id, (Integer) user, top.getPackageName(),
                    (Integer) display, (Integer) mode, rect.left, rect.top, rect.right, rect.bottom,
                    SystemClock.elapsedRealtime());
        } catch (Throwable unavailable) { return null; }
    }

    /** MIUI scales the extent around the logical top-left, not around the display origin. */
    static Rect visualBounds(Rect logical, Float scale) {
        Rect result = new Rect(logical);
        if (scale == null || !Float.isFinite(scale) || scale <= 0 || scale > 4
                || logical.isEmpty()) return result;
        long width = Math.round((double) logical.width() * scale);
        long height = Math.round((double) logical.height() * scale);
        long right = (long) logical.left + width;
        long bottom = (long) logical.top + height;
        if (width > 0 && height > 0 && right <= Integer.MAX_VALUE && bottom <= Integer.MAX_VALUE) {
            result.right = (int) right;
            result.bottom = (int) bottom;
        }
        return result;
    }

    private static Object invoke(Object owner, String name) throws Exception {
        Method method = SystemTargetWindowObserver.zeroArg(owner.getClass(), name);
        return method == null ? null : method.invoke(owner);
    }

    private static Object field(Object owner, String name) throws IllegalAccessException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
            catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
}
