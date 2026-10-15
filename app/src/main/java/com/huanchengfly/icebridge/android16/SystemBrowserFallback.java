package com.huanchengfly.icebridge.android16;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;

import com.huanchengfly.icebridge.BuildConfig;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Worker-only browser fallback. Selection never sends a public URL to an arbitrary deep-link app. */
public final class SystemBrowserFallback {
    private static final String BROWSER_ROLE = "android.app.role.BROWSER";
    private static final Field ALL_WEB_DATA = allWebDataField();

    private SystemBrowserFallback() { }

    public static boolean open(Context context, int userId, String url,
                               SystemAutonomousLauncher.IntentDecorator decorator) {
        return open(context, userId, url, null, decorator);
    }

    /** excludedTarget prevents a failed browser target from being selected a second time. */
    public static boolean open(Context context, int userId, String url, String excludedTarget,
                               SystemAutonomousLauncher.IntentDecorator decorator) {
        if (context == null || decorator == null || userId < 0 || userId > 999
                || Process.myUid() != Process.SYSTEM_UID
                || Looper.myLooper() == Looper.getMainLooper()) return false;
        long identity = Binder.clearCallingIdentity();
        try {
            Uri web = publicWebUri(url);
            UserHandle user = (UserHandle) UserHandle.class.getMethod("of", Integer.TYPE).invoke(null, userId);
            Context scoped = (Context) Context.class.getMethod("createContextAsUser", UserHandle.class, Integer.TYPE)
                    .invoke(context, user, 0);
            if (scoped == null) return false;
            // A Context implementation may return itself rather than create a scoped Context.
            Object actualUser = Context.class.getMethod("getUserId").invoke(scoped);
            if (!(actualUser instanceof Integer) || (Integer) actualUser != userId) return false;
            PackageManager pm = scoped.getPackageManager();
            Intent query = new Intent(Intent.ACTION_VIEW, web).addCategory(Intent.CATEGORY_BROWSABLE);
            List<ResolveInfo> matches = pm.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY);
            String defaultBrowser = defaultBrowser(scoped, pm, user, userId);
            ComponentName selected = selectBrowser(matches, defaultBrowser, excludedTarget);
            if (selected == null) return false;
            Intent launch = buildBrowserIntent(url, selected);
            decorator.registerReplay(launch, userId, "*");
            // Explicit component, explicit user, no retained caller extras, selector or URI grants.
            SystemActivityLauncher.start(context, launch, userId);
            return true;
        } catch (Exception | LinkageError failure) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /** Multiple eligible browsers require a real configured browser role/default package. */
    public static ComponentName selectBrowser(List<ResolveInfo> matches, String defaultPackage,
                                               String excludedTarget) {
        Map<ComponentName, ActivityInfo> browsers = new LinkedHashMap<>();
        if (matches != null) for (ResolveInfo match : matches) {
            ActivityInfo activity = match == null ? null : match.activityInfo;
            if (activity == null || !handlesAllWebData(match) || !match.isDefault || !activity.enabled || !activity.exported
                    || activity.applicationInfo == null || !activity.applicationInfo.enabled
                    || activity.packageName == null || activity.name == null
                    || activity.packageName.isEmpty() || activity.name.isEmpty()
                    || BuildConfig.APPLICATION_ID.equals(activity.packageName)
                    || activity.packageName.equals(excludedTarget)) continue;
            ComponentName component = new ComponentName(activity.packageName, activity.name);
            browsers.put(component, activity);
        }
        if (browsers.size() == 1) return browsers.keySet().iterator().next();
        ComponentName selected = null;
        if (defaultPackage != null && !defaultPackage.isEmpty()) {
            for (ComponentName component : browsers.keySet()) {
                if (!defaultPackage.equals(component.getPackageName())) continue;
                // Multiple eligible activities inside the default package are also ambiguous.
                if (selected != null) return null;
                selected = component;
            }
        }
        return selected;
    }

    private static Field allWebDataField() {
        try {
            Field field = ResolveInfo.class.getField("handleAllWebDataURI");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean handlesAllWebData(ResolveInfo info) {
        if (ALL_WEB_DATA == null) return false;
        try { return ALL_WEB_DATA.getBoolean(info); }
        catch (IllegalAccessException | RuntimeException unavailable) { return false; }
    }

    /** Exposed for instrumentation checks without starting an Activity or changing package state. */
    public static Intent buildBrowserIntent(String url, ComponentName browser) {
        if (browser == null || BuildConfig.APPLICATION_ID.equals(browser.getPackageName())) {
            throw new IllegalArgumentException("browser-target-invalid");
        }
        return new Intent(Intent.ACTION_VIEW, publicWebUri(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setComponent(browser)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static Uri publicWebUri(String url) {
        if (url == null || url.isEmpty() || url.length() > 16_384) {
            throw new IllegalArgumentException("browser-url-invalid");
        }
        for (int i = 0; i < url.length(); i++) {
            char value = url.charAt(i);
            if (value <= 0x20 || value == '\\' || Character.isISOControl(value)) {
                throw new IllegalArgumentException("browser-url-invalid");
            }
        }
        Uri web = Uri.parse(url);
        if (!("http".equalsIgnoreCase(web.getScheme()) || "https".equalsIgnoreCase(web.getScheme()))
                || web.isOpaque() || web.getHost() == null || web.getHost().isEmpty()) {
            throw new IllegalArgumentException("browser-url-invalid");
        }
        return web;
    }

    private static String defaultBrowser(Context scoped, PackageManager pm, UserHandle user, int userId) {
        // API 29+: query the role explicitly for this Android user. System UID owns this operation.
        try {
            Object roles = Build.VERSION.SDK_INT >= 29 ? scoped.getSystemService(Context.ROLE_SERVICE) : null;
            if (roles != null) {
                Method getter = roles.getClass().getMethod("getRoleHoldersAsUser", String.class, UserHandle.class);
                Object holders = getter.invoke(roles, BROWSER_ROLE, user);
                if (holders instanceof List) {
                    List<?> values = (List<?>) holders;
                    if (values.size() == 1 && values.get(0) instanceof String) return (String) values.get(0);
                    if (!values.isEmpty()) return null;
                }
            }
        } catch (Exception | LinkageError ignored) { }
        // Pre-role Android/OEM compatibility. The explicit user argument is mandatory.
        try {
            Object value = pm.getClass().getMethod("getDefaultBrowserPackageNameAsUser", Integer.TYPE)
                    .invoke(pm, userId);
            return value instanceof String ? (String) value : null;
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }
}
