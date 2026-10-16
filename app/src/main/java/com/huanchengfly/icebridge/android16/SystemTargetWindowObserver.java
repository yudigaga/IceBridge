package com.huanchengfly.icebridge.android16;

import android.content.Intent;
import android.os.SystemClock;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/** Reads only bounded launch metadata from ActivityRecord; no UI or PM work under ATMS locks. */
public final class SystemTargetWindowObserver {
    public static final String EXTRA_TRANSIT_ID = "io.github.yudigaga.icebridge.extra.TRANSIT_ID";
    private static final long TTL = 20_000L;
    private final WeakHashMap<Object, Delivery> delivered = new WeakHashMap<>();

    public static final class Event {
        public final long id;
        public final int userId;
        public final String packageName;
        public final boolean systemPrompt;
        public final long observedAt;
        public final String presentation;
        Event(long id, int userId, String packageName, boolean systemPrompt) {
            this(id, userId, packageName, systemPrompt, "app-window");
        }
        Event(long id, int userId, String packageName, boolean systemPrompt, String presentation) {
            this.id = id; this.userId = userId; this.packageName = packageName; this.systemPrompt = systemPrompt;
            this.observedAt = SystemClock.elapsedRealtime();
            this.presentation = presentation;
        }
    }

    /** A reused ActivityRecord retains its old intent; remember the newly delivered token. */
    public synchronized void onNewIntent(Object record, Intent intent) {
        long id = idOf(intent);
        if (record == null) return;
        prune();
        if (id <= 0) {
            try {
                Object initial = field(record, "intent");
                if (!delivered.containsKey(record)
                        && idOf(initial instanceof Intent ? (Intent) initial : null) <= 0) return;
            } catch (Throwable ignored) { return; }
            // An app router can deliver an untagged Intent to an old tagged Activity. Remember
            // the absence, rather than attributing its new window to the initial launch forever.
            id = 0;
        }
        while (delivered.size() >= 64) {
            Iterator<Object> keys = delivered.keySet().iterator();
            keys.next(); keys.remove();
        }
        delivered.put(record, new Delivery(id, SystemClock.elapsedRealtime()));
    }

    /** The task observer shares the delivery override for reused Activities. */
    public synchronized long transitId(Object record) {
        try {
            if (record == null) return 0;
            prune();
            Delivery delivery = delivered.get(record);
            if (delivery != null) return delivery.id;
            Object raw = field(record, "intent");
            return idOf(raw instanceof Intent ? (Intent) raw : null);
        } catch (Throwable ignored) { return 0; }
    }

    /** Called after onWindowsDrawn, or after resume/delivery only for already visible drawn windows. */
    public synchronized Event read(Object record, boolean requireVisible) {
        try {
            // onWindowsDrawn is itself the draw signal. AOSP assigns mReportedDrawn only after
            // this inner callback returns; only resume/new-intent probes may require that flag.
            if (record == null || !booleanMethod(record, "isVisibleRequested")
                    || (requireVisible && !booleanMethod(record, "isReportedDrawn"))) return null;
            if (requireVisible && !"RESUMED".equals(String.valueOf(invoke(record, "getState")))) return null;
            Object pkg = field(record, "packageName");
            Object user = field(record, "mUserId");
            if (!(user instanceof Integer)) user = field(record, "userId");
            if (!(pkg instanceof String) || !(user instanceof Integer) || (Integer) user < 0) return null;
            prune();
            Delivery delivery = delivered.get(record);
            Object raw = field(record, "intent");
            long id = delivery == null ? idOf(raw instanceof Intent ? (Intent) raw : null) : delivery.id;
            String name = (String) pkg;
            // Permission windows interrupt opening. Hide the card without claiming the target drew.
            boolean prompt = ("com.android.permissioncontroller".equals(name)
                    || "com.google.android.permissioncontroller".equals(name)
                    || "com.lbe.security.miui".equals(name) || "com.android.packageinstaller".equals(name));
            if (prompt && booleanMethod(record, "isVisibleRequested")
                    && "RESUMED".equals(String.valueOf(invoke(record, "getState")))) {
                return new Event(0, (Integer) user, name, true);
            }
            if (id > 0) return new Event(id, (Integer) user, name, false);
            // App-internal routers may drop unknown extras. id=0 is only a window candidate;
            // the runtime must still correlate package, user, operation phase and event time.
            return booleanMethod(record, "isVisibleRequested")
                    && "RESUMED".equals(String.valueOf(invoke(record, "getState")))
                    ? new Event(0, (Integer) user, name, false) : null;
        } catch (Throwable ignored) { return null; }
    }

    /** Called only after WindowState.performShowLocked returns true for a shown window. */
    public synchronized Event readShownWindow(Object window) {
        try {
            if (window == null || !Boolean.FALSE.equals(field(window, "mHidden"))) return null;
            Object attrs = field(window, "mAttrs");
            if (!(attrs instanceof WindowManager.LayoutParams)) return null;
            int type = ((WindowManager.LayoutParams) attrs).type;
            if (type != WindowManager.LayoutParams.TYPE_APPLICATION_STARTING
                    && type != WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                    && type != WindowManager.LayoutParams.TYPE_APPLICATION) return null;
            Object record = field(window, "mActivityRecord");
            // performShowLocked(true) is the presentation signal, including a starting window
            // before ActivityRecord reaches RESUMED or commits mReportedDrawn for its content.
            if (record == null || !booleanMethod(record, "isVisibleRequested")) return null;
            Object pkg = field(record, "packageName");
            Object user = field(record, "mUserId");
            if (!(user instanceof Integer)) user = field(record, "userId");
            if (!(pkg instanceof String) || !(user instanceof Integer) || (Integer) user < 0) return null;
            prune();
            Delivery delivery = delivered.get(record);
            Object raw = field(record, "intent");
            long id = delivery == null ? idOf(raw instanceof Intent ? (Intent) raw : null) : delivery.id;
            String name = (String) pkg;
            boolean prompt = "com.android.permissioncontroller".equals(name)
                    || "com.google.android.permissioncontroller".equals(name)
                    || "com.lbe.security.miui".equals(name) || "com.android.packageinstaller".equals(name);
            // Untagged app routers remain candidates; runtime correlation still checks package,
            // user, operation phase and observation time before dismissing a progress session.
            return new Event(prompt || id <= 0 ? 0 : id, (Integer) user, name, prompt,
                    type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING
                            ? "starting-window" : "app-window");
        } catch (Throwable ignored) { return null; }
    }

    public static Method zeroArg(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try { Method method = type.getDeclaredMethod(name); method.setAccessible(true); return method; }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        return null;
    }

    private static long idOf(Intent intent) {
        try { return intent == null ? 0 : intent.getLongExtra(EXTRA_TRANSIT_ID, 0); }
        catch (RuntimeException ignored) { return 0; }
    }
    private static Object field(Object record, String name) throws IllegalAccessException {
        for (Class<?> type = record.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(record); }
            catch (NoSuchFieldException ignored) { }
        }
        return null;
    }
    private static Object invoke(Object record, String name) throws Exception {
        Method method = zeroArg(record.getClass(), name);
        return method == null ? null : method.invoke(record);
    }
    private static boolean booleanMethod(Object record, String name) throws Exception {
        return Boolean.TRUE.equals(invoke(record, name));
    }
    private void prune() {
        long now = SystemClock.elapsedRealtime();
        delivered.entrySet().removeIf(entry -> now < entry.getValue().at || now - entry.getValue().at > TTL);
    }
    private static final class Delivery {
        final long id, at;
        Delivery(long id, long at) { this.id = id; this.at = at; }
    }
}
