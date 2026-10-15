package com.huanchengfly.icebridge.android16;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.huanchengfly.icebridge.BuildConfig;

import java.util.HashMap;
import java.util.Map;

/** A non-interactive native status card owned entirely by system_server. */
public final class SystemTransitPresenter {
    public enum Stage { THAWING, OPENING }
    public interface Logger { void event(String message); }

    interface Dispatcher {
        void post(Runnable task);
        void postDelayed(Runnable task, long delayMillis);
        default boolean isCurrentThread() { return false; }
    }
    interface WindowHost {
        boolean canShow();
        void show(TransitSessionPolicy.Session session) throws Exception;
        void dismiss() throws Exception;
    }
    interface Clock { long uptimeMillis(); }
    interface ListenerRegistration { void register(SystemTransitPresenter presenter) throws Exception; }

    private final Dispatcher ui;
    private final WindowHost window;
    private final Logger logger;
    private final Clock clock;
    private final ListenerRegistration registration;
    private final TransitSessionPolicy sessions = new TransitSessionPolicy();
    private boolean listening;
    // The framework retains this receiver for the system_server lifetime.
    private BroadcastReceiver systemEvents;

    SystemTransitPresenter(Dispatcher ui, WindowHost window, Logger logger, Clock clock) {
        this(ui, window, logger, clock, presenter -> { });
    }

    SystemTransitPresenter(Dispatcher ui, WindowHost window, Logger logger, Clock clock,
                           ListenerRegistration registration) {
        this.ui = ui;
        this.window = window;
        this.logger = logger == null ? message -> { } : logger;
        this.clock = clock;
        this.registration = registration;
    }

    /** Failure to construct a presenter never changes or blocks the actual bridge operation. */
    public static SystemTransitPresenter create(Context systemContext, Logger logger) {
        ClassLoader fallbackLoader = SystemTransitPresenter.class.getClassLoader();
        try {
            if (systemContext != null && systemContext.getClassLoader() != null) {
                fallbackLoader = systemContext.getClassLoader();
            }
        } catch (Throwable ignored) { }
        return create(systemContext, logger, fallbackLoader);
    }

    /** The system-server loader also contains services.jar, unlike the module APK loader. */
    public static SystemTransitPresenter create(Context systemContext, Logger logger, ClassLoader serverLoader) {
        try {
            if (Process.myUid() != Process.SYSTEM_UID) throw new SecurityException("system UID required");
            Handler handler = (Handler) Class.forName("com.android.server.UiThread", true, serverLoader)
                    .getMethod("getHandler").invoke(null);
            Dispatcher dispatcher = dispatcher(handler);
            NativeWindowHost host = new NativeWindowHost(systemContext, null);
            SystemTransitPresenter presenter = new SystemTransitPresenter(dispatcher, host, logger,
                    SystemClock::uptimeMillis, owner -> owner.listen(systemContext, handler));
            return presenter;
        } catch (Throwable error) {
            if (logger != null) {
                try { logger.event("transit UI unavailable: " + error.getClass().getSimpleName()); }
                catch (Throwable ignored) { }
            }
            return new SystemTransitPresenter(new Dispatcher() {
                @Override public void post(Runnable task) { }
                @Override public void postDelayed(Runnable task, long delayMillis) { }
            }, new WindowHost() {
                @Override public boolean canShow() { return false; }
                @Override public void show(TransitSessionPolicy.Session session) { }
                @Override public void dismiss() { }
            }, logger, () -> 0);
        }
    }

    public void onStage(long id, int userId, String targetPackage, Stage stage, int seed, String appearance) {
        onStage(id, userId, null, targetPackage, stage, seed, appearance);
    }

    public void onStage(long id, int userId, String sourcePackage, String targetPackage,
                        Stage stage, int seed, String appearance) {
        if (stage == null) return;
        post(() -> {
            boolean newSession = sessions.active() == null || sessions.active().id != id;
            if (!sessions.stage(id, userId, sourcePackage, targetPackage, stage == Stage.THAWING
                    ? TransitSessionPolicy.THAWING : TransitSessionPolicy.OPENING,
                    seed, appearance, clock.uptimeMillis())) return;
            if (!listening) {
                try {
                    registration.register(this);
                    listening = true;
                } catch (Throwable error) {
                    sessions.finish(id);
                    dismiss("registration-unavailable:" + error.getClass().getSimpleName());
                    return;
                }
            }
            if (userId != 0 || !window.canShow()) {
                sessions.finish(id);
                dismiss("display-not-available");
                return;
            }
            try {
                window.show(sessions.active());
                if (newSession) {
                    log("transit shown: id=" + id + ", user=" + userId);
                    long remaining = Math.max(0, sessions.active().deadline - clock.uptimeMillis());
                    ui.postDelayed(() -> guarded(() -> {
                        if (sessions.timeout(id, clock.uptimeMillis())) dismiss("timeout");
                    }), remaining);
                }
            } catch (Throwable error) {
                sessions.finish(id);
                dismiss("show-failed:" + error.getClass().getSimpleName());
            }
        });
    }

    /** A framework launch request can return before the target has drawn its first window. */
    public void onSubmitted(long id) { post(() -> sessions.submitted(id)); }

    public void onTargetDrawn(long id, int userId, String targetPackage) {
        post(() -> {
            if (sessions.targetDrawn(id, userId, targetPackage)) dismiss("target-drawn");
        }, true);
    }

    public void finish(long id, String reason) {
        post(() -> { if (sessions.finish(id)) dismiss(reason); });
    }

    public void cancelAll(String reason) {
        post(() -> { if (sessions.cancelAll()) dismiss(reason); });
    }

    private void listen(Context context, Handler handler) {
        BroadcastReceiver candidateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                    String reason = intent.getStringExtra("reason");
                    if (!"homekey".equals(reason) && !"recentapps".equals(reason)
                            && !"globalactions".equals(reason)) return;
                }
                cancelAll("system-visibility-changed");
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction("android.intent.action.USER_SWITCHED");
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        // Protected system broadcasts only; no external control endpoint is introduced.
        long identity = Binder.clearCallingIdentity();
        try {
            int flags = Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0;
            context.registerReceiver(candidateReceiver, filter, null, handler, flags);
            systemEvents = candidateReceiver;
        }
        finally { Binder.restoreCallingIdentity(identity); }
    }

    private void post(Runnable task) {
        post(task, false);
    }

    private void post(Runnable task, boolean immediateOnUiThread) {
        try {
            if (immediateOnUiThread && ui.isCurrentThread()) guarded(task);
            else ui.post(() -> guarded(task));
        }
        catch (Throwable error) { log("transit dispatch unavailable: " + error.getClass().getSimpleName()); }
    }

    private void guarded(Runnable task) {
        try { task.run(); }
        catch (Throwable error) {
            sessions.cancelAll();
            dismiss("UI-failed:" + error.getClass().getSimpleName());
        }
    }

    private void dismiss(String reason) {
        try { window.dismiss(); }
        catch (Throwable error) { log("transit removal unavailable: " + error.getClass().getSimpleName()); }
        log("transit dismissed: " + reason);
    }

    private void log(String message) {
        try { logger.event(message); } catch (Throwable ignored) { }
    }

    private static Dispatcher dispatcher(Handler handler) {
        return new Dispatcher() {
            @Override public void post(Runnable task) { handler.post(task); }
            @Override public void postDelayed(Runnable task, long delayMillis) { handler.postDelayed(task, delayMillis); }
            @Override public boolean isCurrentThread() { return handler.getLooper().isCurrentThread(); }
        };
    }

    /** Package-private debug-only rendering seam; it cannot request a production system window. */
    static SystemTransitPresenter createPreview(Activity activity, Logger logger) {
        if (!BuildConfig.DEBUG) throw new SecurityException("preview requires debug build");
        IBinder token = activity.getWindow().getDecorView().getWindowToken();
        if (token == null) throw new IllegalStateException("preview Activity window not attached");
        Handler handler = new Handler(activity.getMainLooper());
        return new SystemTransitPresenter(dispatcher(handler),
                new NativeWindowHost(activity, token),
                logger, SystemClock::uptimeMillis);
    }

    View previewCard() {
        if (!BuildConfig.DEBUG || !(window instanceof NativeWindowHost)) return null;
        return ((NativeWindowHost) window).attached ? ((NativeWindowHost) window).card : null;
    }

    private static final class NativeWindowHost implements WindowHost {
        private final Context suppliedContext;
        private final IBinder previewToken;
        private final Map<String, AppVisual> applications = new HashMap<>();
        private Context windowContext;
        private WindowManager manager;
        private LinearLayout card;
        private TransitFlowView flow;
        private boolean attached;

        NativeWindowHost(Context context, IBinder previewToken) {
            suppliedContext = context;
            this.previewToken = previewToken;
        }

        @Override public boolean canShow() {
            if (previewToken != null) return true;
            try {
                int user = (Integer) Class.forName("android.app.ActivityManager")
                        .getMethod("getCurrentUser").invoke(null);
                KeyguardManager keyguard = suppliedContext.getSystemService(KeyguardManager.class);
                PowerManager power = suppliedContext.getSystemService(PowerManager.class);
                return user == 0 && keyguard != null && !keyguard.isKeyguardLocked()
                        && power != null && power.isInteractive();
            } catch (Throwable ignored) { return false; }
        }

        private void ensureWindowContext() throws Exception {
            if (windowContext != null) return;
            Context candidateContext;
            if (previewToken != null) {
                candidateContext = suppliedContext;
            } else {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
                Context uiContext = (Context) activityThread.getMethod("getSystemUiContext").invoke(thread);
                candidateContext = Build.VERSION.SDK_INT >= 30
                        ? uiContext.createWindowContext(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG, null) : uiContext;
            }
            WindowManager candidateManager = candidateContext.getSystemService(WindowManager.class);
            if (candidateManager == null) throw new IllegalStateException("window manager missing");
            windowContext = candidateContext;
            manager = candidateManager;
        }

        @Override public void show(TransitSessionPolicy.Session session) throws Exception {
            ensureWindowContext();
            boolean dark = "dark".equals(session.appearance) || (!"light".equals(session.appearance)
                    && (windowContext.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
            TransitSessionPolicy.Palette palette = TransitSessionPolicy.Palette.fromSeed(session.seed, dark);
            if (card == null) createCard();
            card.setBackground(shape(palette.card, 20, palette.outline));
            AppVisual source = application(session.sourcePackage, "发起应用");
            AppVisual target = application(session.targetPackage, "目标应用");
            flow.bind(source.icon, source.label, target.icon, target.label, palette);
            String stage = session.stage == TransitSessionPolicy.THAWING ? "正在解冻" : "正在打开";
            card.setContentDescription(source.label + "，前往" + target.label + "，" + stage);
            if (!attached) {
                WindowManager.LayoutParams params = parameters();
                manager.addView(card, params);
                attached = true;
            } else manager.updateViewLayout(card, parameters());
        }

        private WindowManager.LayoutParams parameters() throws Exception {
            int available = windowContext.getResources().getDisplayMetrics().widthPixels - dp(48);
            int width = Math.max(dp(160), Math.min(dp(208), available));
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(width,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    previewToken != null ? WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                            : WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.CENTER;
            params.windowAnimations = 0;
            params.setTitle("IceBridge Transit");
            if (previewToken != null) params.token = previewToken;
            else {
                params.packageName = "android";
                // TYPE_SYSTEM_DIALOG is not automatically trusted. Preserve touch-through to apps.
                if (Build.VERSION.SDK_INT >= 31) {
                    WindowManager.LayoutParams.class.getMethod("setTrustedOverlay").invoke(params);
                }
            }
            return params;
        }

        private void createCard() {
            card = new LinearLayout(windowContext);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(8), dp(12), dp(8));
            card.setElevation(dp(12));
            card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            card.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

            flow = new TransitFlowView(windowContext);
            LinearLayout.LayoutParams flowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            card.addView(flow, flowParams);
        }

        private AppVisual application(String packageName, String fallbackLabel) {
            AppVisual cached = applications.get(packageName);
            if (cached != null) return cached;
            PackageManager pm = windowContext.getPackageManager();
            String name = packageName == null ? fallbackLabel : packageName;
            Drawable icon = pm.getDefaultActivityIcon();
            try {
                if (packageName != null) {
                    ApplicationInfo info = pm.getApplicationInfo(packageName,
                            PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES);
                    CharSequence label = info.loadLabel(pm);
                    if (label != null && label.length() != 0) name = label.toString();
                    Drawable loaded = info.loadIcon(pm);
                    if (loaded != null) icon = loaded;
                }
            } catch (Throwable ignored) { }
            if (applications.size() >= 64) applications.clear();
            AppVisual result = new AppVisual(name, icon);
            applications.put(packageName, result);
            return result;
        }

        private static final class AppVisual {
            final String label;
            final Drawable icon;
            AppVisual(String label, Drawable icon) { this.label = label; this.icon = icon; }
        }

        private GradientDrawable shape(int color, int radius, int stroke) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(color);
            background.setCornerRadius(dp(radius));
            if (stroke != 0) background.setStroke(dp(1), stroke);
            return background;
        }

        private int dp(int value) {
            return Math.round(value * windowContext.getResources().getDisplayMetrics().density);
        }

        @Override public void dismiss() {
            if (attached && card != null && manager != null) {
                try { manager.removeViewImmediate(card); }
                finally { attached = false; }
            }
        }
    }
}
