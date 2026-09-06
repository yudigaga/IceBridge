package com.huanchengfly.icebridge.modules;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;

import com.catchingnow.icebox.sdk_client.IceBox;
import com.huanchengfly.icebridge.SystemServerConfigContract;
import com.huanchengfly.icebridge.android16.AtmsSignatureResolver;
import com.huanchengfly.icebridge.android16.CapabilityProbe;
import com.huanchengfly.icebridge.android16.LaunchRecoveryPolicy;
import com.huanchengfly.icebridge.android16.IntentUrlExtractor;
import com.huanchengfly.icebridge.android16.NativeActivityLinkCapture;
import com.huanchengfly.icebridge.android16.CustomSchemeTargetResolver;
import com.huanchengfly.icebridge.automation.OnlineLinkRuleRepository;
import com.huanchengfly.icebridge.android16.PaymentSdkBridgeResolver;
import com.huanchengfly.icebridge.android16.ResolvedLaunch;
import com.huanchengfly.icebridge.android16.SystemServerPackageStateProbe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.UUID;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class IceBridgeModule extends XposedModule {
    private static final String PACKAGE_NAME = "com.huanchengfly.icebridge";
    private static final String TAG = "IceBridge";
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final long CONFIG_RETRY_INTERVAL_MILLIS = 30_000L;
    private static final long FROZEN_CACHE_REFRESH_MILLIS = 60_000L;
    private static final long RECOVERY_DEDUPLICATION_MILLIS = 4_000L;
    private static final long CLIPBOARD_EVENT_DEDUPLICATION_MILLIS = 250L;
    // The app process must perform Root/IceBox work first. Launching from its receiver is
    // blocked by Android 16 BAL, so system_server retries the original launch after this delay.
    private static final long SYSTEM_AUTO_OPEN_DELAY_MILLIS = 900L;
    private static final String ACTION_RECOVER_LINK = "com.huanchengfly.icebridge.action.RECOVER_LINK";
    private static final String ACTION_NATIVE_LINK_CAPTURED =
            "com.huanchengfly.icebridge.action.NATIVE_LINK_CAPTURED";
    private static final String EXTRA_TARGET_PACKAGE = "target_package";
    private static final String EXTRA_ORIGINAL_INTENT = "original_intent";
    private static final String EXTRA_AUTO_OPEN = "auto_open";
    private static final String EXTRA_PLAY_STORE = "play_store";
    private static final String EXTRA_AUTO_BRIDGED = "auto_bridged";
    private static final String EXTRA_NATIVE_AUTO_BRIDGED = "native_auto_bridged";
    private static final String EXTRA_NATIVE_REPLAY_TOKEN = "native_replay_token";
    private static final long REPLAY_TOKEN_WINDOW_MILLIS = 15_000L;
    private static final String EXTRA_NATIVE_LINK_URL = "native_link_url";
    private static final String EXTRA_NATIVE_DIRECT_INTENT = "native_direct_intent";
    private static final String EXTRA_NATIVE_PREWARM_ONLY = "native_prewarm_only";
    private static final String EXTRA_NATIVE_BRIDGE_SOURCE = "native_bridge_source";
    private static final String RECOVERY_RECEIVER = "com.huanchengfly.icebridge.receivers.LinkRecoveryReceiver";
    private static final String NATIVE_LINK_RECEIVER = "com.huanchengfly.icebridge.receivers.NativeLinkReceiver";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_SOURCE = "source";
    private static final String EXTRA_SOURCE_PACKAGE = "source_package";
    private static final String EXTRA_SOURCE_ACTIVITY = "source_activity";
    private static final String EXTRA_USER_ID = "user_id";
    private static final String EXTRA_CAPTURED_AT = "captured_at";
    private static final Pattern PACKAGE_NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");

    private volatile SystemServerPackageStateProbe packageStateProbe;
    private volatile RecoveryConfiguration recoveryConfiguration = RecoveryConfiguration.unavailable("not-loaded");
    private volatile long lastRecoveryConfigurationAttemptMillis;
    private volatile boolean chromeHookInstalled;
    private volatile boolean clipboardHookInstalled;
    private volatile Handler systemHandler;
    private volatile HandlerThread systemWorkerThread;
    private volatile Field activityStarterRequestField;
    private volatile Field activityStarterIntentField;
    private final ConcurrentHashMap<String, Long> pendingRecoveries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> replayTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> clipboardEvents = new ConcurrentHashMap<>();

    public IceBridgeModule() {
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        if (param.isSystemServer()) {
            log(4, TAG, "system_server module loaded: " + CapabilityProbe.fingerprint());
        } else {
            log(5, TAG, "module loaded outside system_server: " + param.getProcessName());
        }
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        packageStateProbe = SystemServerPackageStateProbe.create();
        ensureSystemWorker();
        scheduleRecoveryConfigurationRefresh(2_000L);
        log(4, TAG, "package-state capability: " + packageStateProbe.getCapability());
        installActivityStartInterceptorHook(param.getClassLoader());
        installActivityStarterExecuteHook(param.getClassLoader());
        // ClipboardService may hold a global system lock. The hook only snapshots plain text,
        // returns through the original method, then posts all parsing and app dispatch work.
        installSystemClipboardHook(param.getClassLoader());
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String packageName = param.getPackageName();
        if (PACKAGE_NAME.equals(packageName)) {
            installActivationHook(param.getClassLoader());
        }
    }

    private void installActivityStartInterceptorHook(ClassLoader classLoader) {
        AtmsSignatureResolver.ResolvedMethod resolved =
                AtmsSignatureResolver.resolveActivityStartInterceptor(classLoader);
        if (resolved == null) {
            log(5, TAG, "global auto-open unavailable: ActivityStartInterceptor signature not found");
            return;
        }
        hook(resolved.method)
                .setId("icebridge:global-auto-open")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object first = chain.getArg(0);
                    if (!(first instanceof Intent)) {
                        return chain.proceed();
                    }
                    Intent original = (Intent) first;
                    Intent nativeBridge = buildNativeAutoOpenBridge(original);
                    if (nativeBridge != null) {
                        log(4, TAG, "native link redirected: target="
                                + nativeBridge.getStringExtra(EXTRA_TARGET_PACKAGE));
                        // Never mutate the caller's Intent. WebView and payment hosts commonly
                        // recycle the same object for the next tap; replacing its fields here
                        // made subsequent taps silently inherit the BridgeActivity payload.
                        Object[] args = chain.getArgs().toArray();
                        args[0] = new Intent(nativeBridge);
                        return chain.proceedWith(chain.getThisObject(), args);
                    }
                    return chain.proceed();
                });
        log(4, TAG, "global auto-open hook installed: " + resolved.description);
    }

    /**
     * HyperOS routes a number of WebView and explicit SDK launches directly through
     * ActivityStarter.execute(), bypassing ActivityStartInterceptor. This is the reliable
     * system-wide fallback: it swaps only a recognized, frozen target to the foreground bridge
     * before package resolution can fail.
     */
    private void installActivityStarterExecuteHook(ClassLoader classLoader) {
        AtmsSignatureResolver.ResolvedMethod resolved =
                AtmsSignatureResolver.resolveActivityStarterExecute(classLoader);
        if (resolved == null) {
            log(5, TAG, "global auto-open fallback unavailable: ActivityStarter.execute not found");
            return;
        }
        try {
            Class<?> starterClass = resolved.method.getDeclaringClass();
            Field requestField = findField(starterClass, "mRequest");
            if (requestField == null) {
                log(5, TAG, "global auto-open fallback unavailable: request field not found");
                return;
            }
            Field intentField = findField(requestField.getType(), "intent");
            if (intentField == null) {
                log(5, TAG, "global auto-open fallback unavailable: intent field not found");
                return;
            }
            activityStarterRequestField = requestField;
            activityStarterIntentField = intentField;
            hook(resolved.method)
                    .setId("icebridge:global-auto-open-activity-starter")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        try {
                            Object request = activityStarterRequestField.get(chain.getThisObject());
                            Intent original = request == null ? null
                                    : (Intent) activityStarterIntentField.get(request);
                            if (isCarrierAlipaySdkActivity(original)) {
                                log(4, TAG, "carrier alipay sdk activity observed: "
                                        + describeIntent(original));
                            }
                            // Keep this callback lock-safe: identify values already in memory and
                            // replace only a recognized WebView link with our foreground trampoline.
                            // No package query, provider, root, IceBox, or Activity launch runs here.
                            Intent bridge = buildNativeAutoOpenBridge(original);
                            if (bridge != null) {
                                // Keep the framework request's Intent isolated from the source
                                // object's instance. A few launchers/webviews reuse that object.
                                activityStarterIntentField.set(request, new Intent(bridge));
                                log(4, TAG, "native link redirected via ActivityStarter: target="
                                        + bridge.getStringExtra(EXTRA_TARGET_PACKAGE));
                            }
                        } catch (Throwable error) {
                            log(5, TAG, "global auto-open fallback skipped: "
                                    + error.getClass().getSimpleName());
                        }
                        return chain.proceed();
                    });
            log(4, TAG, "global auto-open fallback hook installed: " + resolved.description);
        } catch (Throwable error) {
            logError("global auto-open fallback hook installation failed", error);
        }
    }

    /**
     * Routes a recognized first WebView click through a foreground IceBridge Activity. Android 16
     * permits the replacement because it happens during the user-initiated Activity start; the
     * BridgeActivity then performs IceBox/root work in the app process and launches the target
     * without the background-activity-launch restriction that affects BroadcastReceiver.
     */
    private boolean isCarrierAlipaySdkActivity(Intent intent) {
        if (intent == null || intent.getComponent() == null) return false;
        // The host is intentionally not restricted to China Unicom: Unicom, Telecom and
        // other apps embed the same Alipay SDK Activity classes.
        return intent.getComponent().getClassName().startsWith("com.alipay.sdk.app.");
    }

    private Intent buildNativeAutoOpenBridge(Intent original) {
        if (original == null) {
            return null;
        }
        // Only bypass the Intent that IceBridge itself replays. Do not apply a target-wide
        // cooldown here: an app may be frozen again shortly after the first handoff, and a new
        // user click must still be able to wake it. This explicit marker is the loop guard for
        // the immediate replay/fallback path.
        String replayToken = original.getStringExtra(EXTRA_NATIVE_REPLAY_TOKEN);
        if (replayToken != null && consumeReplayToken(replayToken)) {
            log(4, TAG, "native link skipped: replay guard consumed token=" + shortToken(replayToken));
            return null;
        }
        // Do not use the old boolean marker as a permanent guard. Some source apps recycle
        // their Intent object, which made every later tap on the same URL disappear. Only the
        // one-time UUID above identifies an IceBridge replay.
        RecoveryConfiguration configuration = currentRecoveryConfiguration();
        if (!configuration.available || !configuration.enabled || !configuration.autoOpenLinks) {
            return null;
        }

        // Payment SDK prewarming is independent from the WeChat/QQ WebView observer.  The two
        // bridges share the same safe foreground trampoline, but disabling WebView capture must
        // not silently disable a host app's signed payment SDK flow.
        NativeActivityLinkCapture.CapturedLink captured = configuration.nativeLinkCapture
                ? NativeActivityLinkCapture.capture(original) : null;
        String target = null;
        String rawUrl;
        boolean directIntent = false;
        boolean prewarmOnly = false;
        String bridgeSource = "unknown";
        if (captured != null) {
            rawUrl = captured.url;
            bridgeSource = captured.source;
            try {
                Uri uri = Uri.parse(rawUrl);
                target = CustomSchemeTargetResolver.findFirstTargetPackageForUri(uri.getScheme(), uri.getHost(), OnlineLinkRuleRepository.parseSystemServerCandidateEntries(configuration.onlineHostRules));
            } catch (Throwable ignored) {
                return null;
            }
        } else {
            PaymentSdkBridgeResolver.Candidate paymentCandidate = configuration.paymentSdkBridge
                    ? PaymentSdkBridgeResolver.resolve(original) : null;
            if (paymentCandidate != null) {
                // Do not replace a payment SDK's signed request with a fabricated wallet intent.
                // Prewarm restores the wallet, then replays the untouched SDK Activity.
                target = paymentCandidate.targetPackage;
                rawUrl = IntentUrlExtractor.findEmbeddedUrl(original.getExtras());
                if (rawUrl == null && original.getData() != null) {
                    rawUrl = original.getData().toString();
                }
                // Some carrier SDK requests carry the signed order only in opaque Parcelable
                // extras and have no URL at all.  The URL is diagnostic/transport metadata here;
                // the payment path replays the untouched SDK Intent, so use a non-sensitive
                // placeholder instead of dropping the candidate at validation time.
                if (rawUrl == null) rawUrl = "alipay://sdk";
                prewarmOnly = paymentCandidate.prewarmOnly;
                bridgeSource = paymentCandidate.source;
            } else {
                // A direct bridge is intentionally limited to known non-HTTP(S) schemes (notably
                // Alipay). Ordinary HTTPS links are only captured from known WebView Activities;
                // otherwise an HTTPS launch performed by BridgeActivity could recurse.
                Uri data = original.getData();
                rawUrl = data == null ? IntentUrlExtractor.findEmbeddedUrl(original.getExtras())
                        : data.toString();
                try {
                    Uri directUri = CustomSchemeTargetResolver.findCustomSchemeUri(original);
                    if (directUri != null) {
                        rawUrl = directUri.toString();
                        target = CustomSchemeTargetResolver.findTargetPackageForCustomSchemeUri(directUri);
                        directIntent = target != null;
                        bridgeSource = "custom_scheme";
                    }
                } catch (Throwable ignored) {
                    // Leave malformed or HTTP(S) values untouched.
                }
            }
        }
        if (directIntent) {
            Uri diagnosticUri = original.getData();
            log(4, TAG, "direct intent candidate: action=" + original.getAction()
                    + ", scheme=" + (diagnosticUri == null ? "embedded" : diagnosticUri.getScheme())
                    + ", component=" + (original.getComponent() == null ? "none"
                    : original.getComponent().flattenToShortString())
                    + ", target=" + target);
        }
        if (prewarmOnly) {
            log(4, TAG, "payment sdk bridge candidate: source=" + bridgeSource
                    + ", target=" + target + ", " + describeIntent(original));
        }
        if (target == null || rawUrl == null || PACKAGE_NAME.equals(target)
                || !PACKAGE_NAME_PATTERN.matcher(target).matches()) {
            return null;
        }
        // A link carried by an explicit Activity/package that already belongs to the resolved
        // target is an in-app navigation request, not a cross-app handoff. This is the important
        // loop breaker for Xiaohongshu post/detail pages: they may contain an xhs URL while the
        // destination component is already com.xingin.xhs.
        if (!prewarmOnly && isExplicitTarget(original, target)) {
            log(4, TAG, "native bridge skipped: target already explicit target=" + target
                    + ", component=" + componentOf(original) + ", package=" + packageOf(original));
            return null;
        }
        // Do not gate a new user event on the last frozen-package snapshot.  IceBox removes a
        // package from that snapshot immediately after the first successful thaw; keeping this
        // filter made every subsequent tap on the same link disappear.  BridgeActivity performs
        // the current enabled-state check and either thaws or launches on every event.  The UUID
        // replay token above remains the only loop guard, and it is one-shot/short-lived.
        if (!configuration.frozenPackages.isEmpty()
                && !configuration.frozenPackages.contains(target)) {
            log(4, TAG, "native bridge target already enabled or snapshot stale: target=" + target
                    + ", source=" + bridgeSource);
        }
        // The saved source Intent must carry the loop guard: if we deliberately replay it for an
        // already-enabled target or after a failure, system_server must not capture it a second time.
        Intent sourcePayload = new Intent(original);
        String token = UUID.randomUUID().toString();
        replayTokens.put(token, SystemClock.elapsedRealtime() + REPLAY_TOKEN_WINDOW_MILLIS);
        sourcePayload.removeExtra(EXTRA_NATIVE_AUTO_BRIDGED);
        sourcePayload.putExtra(EXTRA_NATIVE_REPLAY_TOKEN, token);
        log(4, TAG, "native replay token created=" + shortToken(token));
        return new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(PACKAGE_NAME,
                        "com.huanchengfly.icebridge.activities.BridgeActivity"))
                .putExtra(EXTRA_AUTO_BRIDGED, true)
                .putExtra(EXTRA_NATIVE_AUTO_BRIDGED, true)
                .putExtra(EXTRA_NATIVE_DIRECT_INTENT, directIntent)
                .putExtra(EXTRA_NATIVE_PREWARM_ONLY, prewarmOnly)
                .putExtra(EXTRA_NATIVE_BRIDGE_SOURCE, bridgeSource)
                .putExtra(EXTRA_SOURCE_PACKAGE, captured == null ? packageOf(original)
                        : (captured.sourcePackage == null ? packageOf(original) : captured.sourcePackage))
                .putExtra(EXTRA_SOURCE_ACTIVITY, captured == null ? componentOf(original)
                        : captured.sourceActivity)
                .putExtra(EXTRA_TARGET_PACKAGE, target)
                .putExtra(EXTRA_NATIVE_LINK_URL, rawUrl)
                .putExtra(EXTRA_ORIGINAL_INTENT, sourcePayload)
                // A clean temporary task prevents IceBridge's MainActivity from becoming the
                // destination when the user later leaves the target app.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    private boolean consumeReplayToken(String token) {
        Long expires = replayTokens.remove(token);
        long now = SystemClock.elapsedRealtime();
        if (expires == null) return false;
        if (expires < now) {
            log(4, TAG, "native replay token expired=" + shortToken(token));
            return false;
        }
        return true;
    }

    private static String shortToken(String token) {
        return token == null ? "none" : token.substring(0, Math.min(8, token.length()));
    }

    private Intent buildGlobalAutoOpenBridge(Intent original) {
        if (original == null || original.getBooleanExtra(EXTRA_AUTO_BRIDGED, false)) {
            return null;
        }
        RecoveryConfiguration configuration = currentRecoveryConfiguration();
        if (!configuration.available || !configuration.enabled || !configuration.autoOpenLinks) {
            return null;
        }
        int userId = 0;
        String target = cachedFrozenTarget(original, userId, configuration.frozenPackages, configuration.onlineHostRules);
        if (target == null || PACKAGE_NAME.equals(target)
                || !isSafeRecoveryCandidate(original)) {
            return null;
        }
        // Keep an explicit SDK callback component and all extras intact. BridgeActivity will replay
        // this exact intent only after the target package has been enabled.
        Intent payload = new Intent(original);
        return new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(PACKAGE_NAME,
                        "com.huanchengfly.icebridge.activities.BridgeActivity"))
                .putExtra(EXTRA_AUTO_BRIDGED, true)
                .putExtra(EXTRA_TARGET_PACKAGE, target)
                .putExtra(EXTRA_ORIGINAL_INTENT, payload)
                .putExtra(EXTRA_PLAY_STORE, playStoreTargetPackage(original) != null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * The callback itself is deliberately limited to data already held in memory.  It must never
     * call a provider, PackageManager, Root, or IceBox while ActivityTaskSupervisor is resolving
     * an Activity.  Expensive work is posted to a dedicated worker after the resolver returns.
     */
    private void installAndroid16ObservationHook(ClassLoader classLoader) {
        if (!CapabilityProbe.isAndroid16()) {
            log(5, TAG, "frozen-link observer unavailable: unsupported SDK " + CapabilityProbe.fingerprint());
            return;
        }
        AtmsSignatureResolver.ResolvedMethod resolveIntent =
                AtmsSignatureResolver.resolveActivityTaskSupervisorResolveIntent(classLoader);
        if (resolveIntent == null) {
            log(5, TAG, "frozen-link observer unavailable: resolveIntent signature not found");
            return;
        }
        hook(resolveIntent.method)
                .setId("icebridge:android16:frozen-link-dispatch")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Intent intent = (Intent) chain.getArg(0);
                    RecoveryConfiguration configuration = currentRecoveryConfiguration();
                    if (!configuration.available || !configuration.enabled) {
                        return chain.proceed();
                    }
                    int userId = chain.getArg(2) instanceof Integer ? (Integer) chain.getArg(2) : 0;
                    String targetPackage = cachedFrozenTarget(intent, userId, configuration.frozenPackages, configuration.onlineHostRules);
                    // Only URI-based ACTION_VIEW launches may enter this legacy asynchronous
                    // recovery path.  Explicit app-internal activities are never observed here.
                    if (targetPackage != null && isSafeRecoveryCandidate(intent)) {
                        // Copying an Intent and posting one message are the only operations in the
                        // task-manager path.  The original resolution is always allowed to proceed.
                        scheduleFrozenLinkRecovery(new Intent(intent), targetPackage, userId,
                                configuration.autoOpenLinks,
                                playStoreTargetPackage(intent) != null);
                    }
                    return chain.proceed();
                });
        log(4, TAG, "frozen-link observer installed: " + resolveIntent.description);
    }

    private String cachedFrozenTarget(Intent intent, int userId, Set<String> frozenPackages, String[] onlineHostRules) {
        if (frozenPackages == null || frozenPackages.isEmpty()) {
            return null;
        }
        String target = LaunchRecoveryPolicy.getExplicitTargetPackage(intent, userId);
        if (target == null) {
            target = playStoreTargetPackage(intent);
        }
        if (target == null && intent != null && intent.getData() != null) {
            Uri data = intent.getData();
            target = CustomSchemeTargetResolver.findFirstTargetPackageForUri(
                    data.getScheme(), data.getHost(),
                    OnlineLinkRuleRepository.parseSystemServerCandidateEntries(onlineHostRules));
        }
        if (target == null) {
            target = CustomSchemeTargetResolver.findTargetPackage(intent);
        }
        return target != null && frozenPackages.contains(target) ? target : null;
    }

    private boolean isExplicitTarget(Intent intent, String targetPackage) {
        if (intent == null || targetPackage == null) {
            return false;
        }
        ComponentName component = intent.getComponent();
        return targetPackage.equals(intent.getPackage())
                || (component != null && targetPackage.equals(component.getPackageName()));
    }

    private boolean isSafeRecoveryCandidate(Intent intent) {
        // Never schedule recovery for an explicit component/package launch. In particular,
        // Xiaohongshu opens posts through explicit internal Activities that may still carry an
        // HTTPS URL. Treating that URL as a frozen-app handoff sends the click back through
        // BridgeActivity and makes every post appear unclickable. Dedicated native WebView and
        // payment paths run before this observer and remain unaffected.
        if (intent == null) {
            return false;
        }
        if (intent.getComponent() != null || intent.getPackage() != null) {
            log(4, TAG, "recovery skipped: explicit internal launch component="
                    + componentOf(intent) + ", package=" + packageOf(intent));
            return false;
        }
        return isSafeLinkResolution(intent);
    }

    private void ensureSystemWorker() {
        if (systemHandler != null) {
            return;
        }
        synchronized (this) {
            if (systemHandler == null) {
                HandlerThread worker = new HandlerThread("IceBridge-SystemWorker");
                worker.start();
                systemWorkerThread = worker;
                systemHandler = new Handler(worker.getLooper());
            }
        }
    }

    private void scheduleRecoveryConfigurationRefresh(long delayMillis) {
        Handler worker = systemHandler;
        if (worker == null) {
            return;
        }
        worker.postDelayed(new Runnable() {
            @Override
            public void run() {
                RecoveryConfiguration configuration = refreshRecoveryConfiguration("frozen-cache", true);
                log(4, TAG, "frozen package cache: status=" + configuration.status
                        + ", packages=" + configuration.frozenPackages.size());
                Handler currentWorker = systemHandler;
                if (currentWorker != null) {
                    currentWorker.postDelayed(this, FROZEN_CACHE_REFRESH_MILLIS);
                }
            }
        }, delayMillis);
    }

    private void scheduleNativeActivityLinkCapture(Intent original) {
        RecoveryConfiguration configuration = currentRecoveryConfiguration();
        if (!configuration.available || !configuration.enabled || !configuration.autoOpenLinks
                || !configuration.nativeLinkCapture) {
            return;
        }
        NativeActivityLinkCapture.CapturedLink captured = NativeActivityLinkCapture.capture(original);
        if (captured == null) {
            return;
        }
        Handler worker = systemHandler;
        if (worker == null) {
            return;
        }
        // Do not retain the original Intent: it may contain app-private extras and a mutable Bundle.
        worker.post(() -> dispatchLinkToApp(captured.url, captured.source, captured.sourcePackage,
                captured.sourceActivity, 0));
    }

    private void dispatchLinkToApp(String url, String source, String sourcePackage,
                                   String sourceActivity, int userId) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            Context systemContext = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            if (systemContext == null) {
                log(5, TAG, "native link dispatch skipped: system context unavailable");
                return;
            }
            Intent event = new Intent(ACTION_NATIVE_LINK_CAPTURED)
                    .setComponent(new ComponentName(PACKAGE_NAME, NATIVE_LINK_RECEIVER))
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_SOURCE, source)
                    .putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
                    .putExtra(EXTRA_SOURCE_ACTIVITY, sourceActivity)
                    .putExtra(EXTRA_USER_ID, userId)
                    .putExtra(EXTRA_CAPTURED_AT, System.currentTimeMillis());
            UserHandle userHandle = (UserHandle) UserHandle.class
                    .getMethod("of", Integer.TYPE).invoke(null, userId);
            Context.class.getMethod("sendBroadcastAsUser", Intent.class, UserHandle.class)
                    .invoke(systemContext, event, userHandle);
            log(4, TAG, "link dispatched: source=" + source + ", user=" + userId);
        } catch (Throwable error) {
            log(5, TAG, "native link dispatch failed: reason="
                    + error.getClass().getSimpleName());
        }
    }

    private void scheduleFrozenLinkRecovery(Intent original, String targetPackage, int userId,
                                            boolean autoOpen, boolean playStoreFallback) {
        Handler worker = systemHandler;
        if (worker == null) {
            return;
        }
        // De-duplicate only the same source transaction. Keying solely by target package
        // drops legitimate consecutive links (for example two different posts opened quickly).
        String linkKey = original == null ? "" : original.getDataString();
        if (linkKey == null) linkKey = "";
        String sourcePackage = original == null ? "" : original.getPackage();
        if (sourcePackage == null && original != null && original.getComponent() != null) {
            sourcePackage = original.getComponent().getPackageName();
        }
        String key = userId + ":" + sourcePackage + ":" + targetPackage + ":" + linkKey;
        long now = SystemClock.elapsedRealtime();
        Long previous = pendingRecoveries.put(key, now);
        if (previous != null && now - previous < RECOVERY_DEDUPLICATION_MILLIS) {
            return;
        }
        worker.post(() -> {
            try {
                dispatchRecoveryToApp(original, targetPackage, userId, autoOpen, playStoreFallback);
                if (autoOpen) {
                    // Root/IceBox activation runs in the app process. Do not start the target from
                    // that BroadcastReceiver: Android 16 blocks it as a background activity launch.
                    worker.postDelayed(() -> launchRecoveredTargetFromSystem(
                            original, targetPackage, userId, playStoreFallback),
                            SYSTEM_AUTO_OPEN_DELAY_MILLIS);
                }
            } finally {
                pendingRecoveries.remove(key);
            }
        });
    }

    private void dispatchRecoveryToApp(Intent original, String targetPackage, int userId,
                                       boolean autoOpen, boolean playStoreFallback) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            Context systemContext = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            if (systemContext == null) {
                log(5, TAG, "frozen-link dispatch skipped: system context unavailable");
                return;
            }
            Intent request = new Intent(ACTION_RECOVER_LINK)
                    .setComponent(new ComponentName(PACKAGE_NAME, RECOVERY_RECEIVER))
                    .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                    .putExtra(EXTRA_ORIGINAL_INTENT, original)
                    .putExtra(EXTRA_AUTO_OPEN, autoOpen)
                    .putExtra(EXTRA_PLAY_STORE, playStoreFallback);
            UserHandle userHandle = (UserHandle) UserHandle.class
                    .getMethod("of", Integer.TYPE).invoke(null, userId);
            Context.class.getMethod("sendBroadcastAsUser", Intent.class, UserHandle.class)
                    .invoke(systemContext, request, userHandle);
            log(4, TAG, "frozen-link recovery dispatched: target=" + targetPackage
                    + ", user=" + userId + ", autoOpen=" + autoOpen);
        } catch (Throwable error) {
            log(5, TAG, "frozen-link dispatch failed: target=" + targetPackage
                    + ", reason=" + error.getClass().getSimpleName());
        }
    }

    private void launchRecoveredTargetFromSystem(Intent original, String targetPackage, int userId,
                                                 boolean playStoreFallback) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            Context systemContext = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            if (systemContext == null) {
                log(5, TAG, "frozen-link system launch skipped: system context unavailable");
                return;
            }
            Intent launch = buildAutoOpenIntent(original, targetPackage, playStoreFallback);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            UserHandle userHandle = (UserHandle) UserHandle.class
                    .getMethod("of", Integer.TYPE).invoke(null, userId);
            long identityToken = Binder.clearCallingIdentity();
            try {
                Context.class.getMethod("startActivityAsUser", Intent.class, UserHandle.class)
                        .invoke(systemContext, launch, userHandle);
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            log(4, TAG, "frozen-link system launch requested: target=" + targetPackage
                    + ", user=" + userId);
        } catch (Throwable error) {
            log(5, TAG, "frozen-link system launch failed: target=" + targetPackage
                    + ", reason=" + error.getClass().getSimpleName());
        }
    }

    private boolean isSafeLinkResolution(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) {
            return false;
        }
        Uri data = intent.getData();
        if (data != null && !TextUtils.isEmpty(data.getScheme())) {
            return true;
        }
        // Some apps place an HTTPS URL in extras. Limit parsing to a structured Bundle.
        return IntentUrlExtractor.findEmbeddedUrl(intent.getExtras()) != null;
    }

    private Intent buildAutoOpenIntent(
            Intent resolutionIntent,
            String targetPackage,
            boolean playStoreFallback
    ) {
        if (playStoreFallback) {
            return new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(targetPackage)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        Intent autoOpenIntent = new Intent(resolutionIntent);
        // An explicit app-SDK callback must retain its component and extras. For a URI/App Link,
        // clear any stale resolver component and bind the link to the recovered package.
        if (autoOpenIntent.getComponent() == null) {
            autoOpenIntent.setPackage(targetPackage);
            autoOpenIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        }
        return autoOpenIntent;
    }

    private RecoveryConfiguration currentRecoveryConfiguration() {
        return recoveryConfiguration;
    }

    private RecoveryConfiguration refreshRecoveryConfiguration(String source, boolean force) {
        long now = SystemClock.elapsedRealtime();
        RecoveryConfiguration current = recoveryConfiguration;
        if (!force && current.available) {
            return current;
        }
        if (!force && now - lastRecoveryConfigurationAttemptMillis < CONFIG_RETRY_INTERVAL_MILLIS) {
            return current;
        }
        lastRecoveryConfigurationAttemptMillis = now;
        RecoveryConfiguration loaded = loadRecoveryConfiguration();
        recoveryConfiguration = loaded;
        log(4, TAG, "resolve recovery configuration: source=" + source
                + ", status=" + loaded.status
                + ", enabled=" + loaded.enabled
                + ", autoOpen=" + loaded.autoOpenLinks
                + ", nativeCapture=" + loaded.nativeLinkCapture
                + ", paymentSdkBridge=" + loaded.paymentSdkBridge);
        return loaded;
    }

    private RecoveryConfiguration loadRecoveryConfiguration() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return RecoveryConfiguration.unavailable("activity-thread-null");
            }
            Context systemContext = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            if (systemContext == null) {
                return RecoveryConfiguration.unavailable("system-context-null");
            }
            // This hook executes while system_server is handling another app's Binder call.
            // Drop that inherited identity so ConfigProvider correctly sees system_server (UID 1000).
            long identityToken = Binder.clearCallingIdentity();
            Bundle snapshot;
            try {
                snapshot = systemContext.getContentResolver().call(
                        Uri.parse("content://" + SystemServerConfigContract.AUTHORITY),
                        SystemServerConfigContract.METHOD_GET_SNAPSHOT,
                        null,
                        null
                );
            } finally {
                Binder.restoreCallingIdentity(identityToken);
            }
            SystemServerConfigContract.Snapshot parsed = SystemServerConfigContract.parse(snapshot);
            return parsed.available
                    ? RecoveryConfiguration.available(parsed.recoveryEnabled, parsed.autoOpenLinks,
                    parsed.nativeLinkCapture, parsed.clipboardAutoOpen, parsed.paymentSdkBridge,
                    parsed.frozenPackages, parsed.onlineHostRules)
                    : RecoveryConfiguration.unavailable(parsed.status);
        } catch (Throwable error) {
            return RecoveryConfiguration.unavailable("provider-call-" + error.getClass().getSimpleName());
        }
    }

    private SystemServerPackageStateProbe usablePackageStateProbe() {
        SystemServerPackageStateProbe probe = packageStateProbe;
        if (probe == null || !probe.isReady()) {
            probe = SystemServerPackageStateProbe.create();
            packageStateProbe = probe;
            log(4, TAG, "package-state capability refresh: " + probe.getCapability());
        }
        return probe;
    }

    private static final class RecoveryConfiguration {
        final boolean available;
        final boolean enabled;
        final boolean autoOpenLinks;
        final boolean nativeLinkCapture;
        final boolean clipboardAutoOpen;
        final boolean paymentSdkBridge;
        final String status;
        final Set<String> frozenPackages;
        final String[] onlineHostRules;

        private RecoveryConfiguration(boolean available, boolean enabled, boolean autoOpenLinks,
                                      boolean nativeLinkCapture, boolean clipboardAutoOpen,
                                      boolean paymentSdkBridge, String status,
                                      Set<String> frozenPackages, String[] onlineHostRules) {
            this.available = available;
            this.enabled = enabled;
            this.autoOpenLinks = autoOpenLinks;
            this.nativeLinkCapture = nativeLinkCapture;
            this.clipboardAutoOpen = clipboardAutoOpen;
            this.paymentSdkBridge = paymentSdkBridge;
            this.status = status;
            this.frozenPackages = frozenPackages;
            this.onlineHostRules = onlineHostRules == null ? new String[0] : onlineHostRules;
        }

        static RecoveryConfiguration available(boolean enabled, boolean autoOpenLinks,
                                               boolean nativeLinkCapture, boolean clipboardAutoOpen,
                                               boolean paymentSdkBridge, Set<String> frozenPackages,
                                               String[] onlineHostRules) {
            return new RecoveryConfiguration(true, enabled, autoOpenLinks, nativeLinkCapture,
                    clipboardAutoOpen, paymentSdkBridge, SystemServerConfigContract.STATUS_OK,
                    frozenPackages == null ? Collections.emptySet() : frozenPackages, onlineHostRules);
        }

        static RecoveryConfiguration unavailable(String status) {
            return new RecoveryConfiguration(false, false, false, false, false, false, status,
                    Collections.emptySet(), new String[0]);
        }
    }

    private String findUniqueRecoverableImplicitTarget(
            Intent intent,
            int userId,
            SystemServerPackageStateProbe probe
    ) {
        if (intent == null || intent.getAction() == null) {
            return null;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            Context systemContext = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            Method query = systemContext.getPackageManager().getClass().getMethod(
                    "queryIntentActivitiesAsUser", Intent.class, Integer.TYPE, Integer.TYPE);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? PackageManager.MATCH_DISABLED_COMPONENTS : PackageManager.GET_DISABLED_COMPONENTS;
            List<?> candidates = (List<?>) query.invoke(systemContext.getPackageManager(), intent, flags, userId);
            Set<String> inspectedPackages = new LinkedHashSet<>();
            String linkHost = implicitLinkHost(intent);
            String target = null;
            for (Object candidate : candidates) {
                if (!(candidate instanceof android.content.pm.ResolveInfo)) {
                    continue;
                }
                android.content.pm.ResolveInfo resolveInfo = (android.content.pm.ResolveInfo) candidate;
                if (resolveInfo.activityInfo == null || resolveInfo.activityInfo.packageName == null) {
                    continue;
                }
                String packageName = resolveInfo.activityInfo.packageName;
                if (!inspectedPackages.add(packageName)) {
                    continue;
                }
                SystemServerPackageStateProbe.State state = probe.read(packageName, userId);
                if (!state.isAvailable() || !state.suspendedKnown
                        || !LaunchRecoveryPolicy.isRecoverableState(
                        state.enabledSetting, state.hidden, state.hiddenKnown, state.suspended)) {
                    continue;
                }
                if (target != null && !target.equals(packageName)) {
                    log(4, TAG, "implicit recovery skipped: decision=AMBIGUOUS_DISABLED_TARGETS");
                    return null;
                }
                target = packageName;
            }
            if (linkHost != null) {
                log(4, TAG, "implicit recovery candidates: host=" + linkHost
                        + ", packages=" + inspectedPackages
                        + ", recoverable=" + (target == null ? "none" : target));
            }
            return target;
        } catch (Throwable error) {
            log(4, TAG, "implicit recovery skipped: candidate query unavailable="
                    + error.getClass().getSimpleName());
            return null;
        }
    }

    private String playStoreTargetPackage(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) {
            return null;
        }
        return playStoreTargetPackage(intent.getData().toString());
    }

    private String playStoreTargetPackage(String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) {
            return null;
        }
        try {
            Uri uri = Uri.parse(rawUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean isMarketDetails = "market".equalsIgnoreCase(scheme)
                    && "details".equalsIgnoreCase(host);
            boolean isGooglePlayDetails = ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && host != null
                    && ("play.google.com".equalsIgnoreCase(host) || "market.android.com".equalsIgnoreCase(host))
                    && "/store/apps/details".equals(uri.getPath());
            if (!isMarketDetails && !isGooglePlayDetails) {
                return null;
            }
            String packageName = uri.getQueryParameter("id");
            return packageName != null && PACKAGE_NAME_PATTERN.matcher(packageName).matches()
                    ? packageName : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
    private String implicitLinkHost(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) {
            return null;
        }
        String scheme = intent.getData().getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return null;
        }
        return intent.getData().getHost();
    }

    /**
     * Produces a privacy-conscious description of an Intent.  Some hosts put the real URL in
     * extras (for example rawUrl/S.rawUrl) rather than in Intent.data, so diagnostics must show
     * where the URL came from without dumping the complete query string.
     */
    private Intent intentForEmbeddedUrl(Intent original) {
        Intent promoted = IntentUrlExtractor.promoteToData(original);
        if (promoted != original) {
            log(4, TAG, "embedded URL promoted to Intent.data: " + safeUri(promoted.getData()));
        }
        return promoted;
    }

    private String describeIntent(Intent intent) {
        if (intent == null) {
            return "intent=null";
        }
        StringBuilder out = new StringBuilder();
        out.append("action=").append(intent.getAction())
                .append(", data=").append(safeUri(intent.getData()))
                .append(", component=").append(componentOf(intent))
                .append(", package=").append(packageOf(intent));
        Bundle extras = intent.getExtras();
        if (extras != null) {
            Set<String> urlKeys = new LinkedHashSet<>();
            collectUrlKeys(extras, "", urlKeys);
            if (!urlKeys.isEmpty()) {
                out.append(", urlExtras=").append(urlKeys);
            }
        }
        return out.toString();
    }

    private void collectUrlKeys(Bundle bundle, String prefix, Set<String> result) {
        for (String key : bundle.keySet()) {
            Object value;
            try {
                value = bundle.get(key);
            } catch (Throwable ignored) {
                continue;
            }
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Bundle) {
                collectUrlKeys((Bundle) value, fullKey, result);
            } else if (value instanceof String && looksLikeUrl((String) value)) {
                result.add(fullKey + "=" + safeUri(Uri.parse((String) value)));
            }
        }
    }

    private boolean looksLikeUrl(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        String candidate = value.trim();
        return candidate.startsWith("http://") || candidate.startsWith("https://")
                || candidate.startsWith("market://") || candidate.startsWith("intent://")
                || candidate.contains("S.url=") || candidate.contains("S.rawUrl=")
                || candidate.contains("rawUrl=");
    }

    private String safeUri(Uri uri) {
        if (uri == null) {
            return "none";
        }
        try {
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            if (scheme == null && host == null) {
                return "non-uri";
            }
            StringBuilder value = new StringBuilder();
            if (scheme != null) {
                value.append(scheme).append("://");
            }
            if (host != null) {
                value.append(host);
            }
            if (path != null && path.length() <= 80) {
                value.append(path);
            } else if (path != null) {
                value.append(path, 0, 80).append("...");
            }
            return value.toString();
        } catch (Throwable ignored) {
            return "uri-error";
        }
    }
    private String componentOf(Intent intent) {
        return intent.getComponent() == null ? "none" : intent.getComponent().flattenToShortString();
    }

    private String packageOf(Intent intent) {
        return intent.getPackage() == null ? "none" : intent.getPackage();
    }

    private void installSystemClipboardHook(ClassLoader classLoader) {
        if (clipboardHookInstalled) return;
        int installed = 0;
        String[] candidates = new String[] {
                "com.android.server.clipboard.ClipboardService",
                "com.android.server.clipboard.ClipboardServiceImpl",
                "com.android.server.clipboard.ClipboardService$ClipboardImpl",
                "com.android.server.clipboard.ClipboardService$ClipboardImplExt",
                "com.android.server.clipboard.ClipboardManagerService",
                "com.android.server.clipboard.ClipboardManagerServiceImpl",
                "com.android.server.clipboard.SemClipboardService",
                "com.android.server.clipboard.MiuiClipboardService",
                "com.android.server.clipboard.HwClipboardService"
        };
        for (String name : candidates) {
            try {
                Class<?> service = Class.forName(name, false, classLoader);
                log(4, TAG, "clipboard class found: " + name);
                for (Method method : service.getDeclaredMethods()) {
                    String methodName = method.getName();
                    if (!methodName.toLowerCase(java.util.Locale.ROOT).contains("setprimaryclip")) continue;
                    Class<?>[] types = method.getParameterTypes();
                    int clipIndex = -1;
                    for (int i = 0; i < types.length; i++) {
                        if (ClipData.class.isAssignableFrom(types[i])) { clipIndex = i; break; }
                    }
                    if (clipIndex < 0) {
                        log(4, TAG, "clipboard method skipped: " + name + "#" + methodName
                                + " reason=no_clipdata_parameter");
                        continue;
                    }
                    try {
                        method.setAccessible(true);
                        final int capturedClipIndex = clipIndex;
                        hook(method)
                                .setId("icebridge:clipboard:" + method.toGenericString().hashCode())
                                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                                .intercept(chain -> {
                                    List<Object> args = chain.getArgs();
                                    String raw = clipboardText(args, capturedClipIndex);
                                    int userId = clipboardUserId(args);
                                    Object result = chain.proceed();
                                    if (raw != null) scheduleClipboardObservation(raw, userId);
                                    return result;
                                });
                        installed++;
                        log(4, TAG, "clipboard hook installed: " + name + "#" + method.toGenericString());
                    } catch (Throwable error) {
                        log(5, TAG, "clipboard method hook failed: " + name + "#" + methodName
                                + "/" + error.getClass().getSimpleName());
                    }
                }
            } catch (ClassNotFoundException ignored) {
                // OEMs move this implementation between framework and server jars.
            } catch (Throwable error) {
                log(5, TAG, "clipboard class scan failed: " + name + "/" + error.getClass().getSimpleName());
            }
        }
        clipboardHookInstalled = installed > 0;
        log(4, TAG, "system clipboard hook "
                + (installed > 0 ? "installed: methods=" + installed : "unavailable; tried=" + candidates.length));
    }

    private static String clipboardText(List<Object> args, int clipIndex) {
        if (args == null || clipIndex < 0 || clipIndex >= args.size() || !(args.get(clipIndex) instanceof ClipData)) return null;
        ClipData clip = (ClipData) args.get(clipIndex);
        if (clip.getItemCount() == 0 || clip.getItemAt(0) == null) return null;
        ClipData.Item item = clip.getItemAt(0);
        CharSequence text = item.getText();
        if (text != null && text.length() > 0) return bounded(text.toString());
        CharSequence html = item.getHtmlText();
        if (html != null && html.length() > 0) return bounded(html.toString());
        Uri uri = item.getUri();
        if (uri != null) return bounded(uri.toString());
        return null;
    }

    private static String bounded(String value) {
        String raw = value == null ? null : value.trim();
        return raw == null || raw.isEmpty() || raw.length() > 8192 ? null : raw;
    }

    private static int clipboardUserId(List<Object> args) {
        if (args == null) return 0;
        // OEM signatures differ: the trailing int may be uid, device id, or flags rather than
        // userId. Prefer an actual UserHandle, then only accept a small user-id-shaped integer.
        for (Object arg : args) {
            if (arg instanceof UserHandle) {
                try {
                    Method identifier = UserHandle.class.getDeclaredMethod("getIdentifier");
                    identifier.setAccessible(true);
                    Object value = identifier.invoke(arg);
                    if (value instanceof Integer) return (Integer) value;
                } catch (Throwable ignored) { }
            }
        }
        for (int i = args.size() - 1; i >= 1; i--) {
            Object arg = args.get(i);
            if (arg instanceof Integer) {
                int value = (Integer) arg;
                if (value >= 0 && value <= 999) return value;
            }
        }
        return 0;
    }

    private void scheduleClipboardObservation(String raw, int userId) {
        Handler worker = systemHandler;
        if (worker == null || raw == null) return;
        String key = userId + ":" + raw;
        long now = SystemClock.elapsedRealtime();
        Long previous = clipboardEvents.put(key, now);
        if (previous != null && now - previous < CLIPBOARD_EVENT_DEDUPLICATION_MILLIS) {
            log(4, TAG, "clipboard event coalesced: user=" + userId);
            return;
        }
        worker.post(() -> {
            try { observeClipboard(raw, userId); }
            finally { clipboardEvents.remove(key, now); }
        });
    }

    /** Runs off ClipboardService's lock; it only dispatches an explicit event to the app process. */
    private void observeClipboard(String raw, int userId) {
        RecoveryConfiguration configuration = currentRecoveryConfiguration();
        if (!configuration.available) {
            log(4, TAG, "clipboard skipped: configuration_unavailable");
            return;
        }
        if (!configuration.enabled) {
            log(4, TAG, "clipboard skipped: bridge_disabled");
            return;
        }
        if (!configuration.autoOpenLinks) {
            log(4, TAG, "clipboard skipped: auto_open_disabled");
            return;
        }
        if (!configuration.clipboardAutoOpen) {
            log(4, TAG, "clipboard skipped: clipboard_auto_open_disabled");
            return;
        }
        String candidate = IntentUrlExtractor.extractUrl(raw);
        if (candidate == null) candidate = raw;
        if (candidate == null || candidate.trim().isEmpty()) {
            log(4, TAG, "clipboard skipped: empty_text");
            return;
        }
        Uri uri;
        try {
            uri = Uri.parse(candidate.trim());
        } catch (Throwable error) {
            log(4, TAG, "clipboard skipped: invalid_uri=" + error.getClass().getSimpleName());
            return;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || host == null
                || host.trim().isEmpty()) {
            log(4, TAG, "clipboard skipped: not_http_url");
            return;
        }
        String target = CustomSchemeTargetResolver.findFirstTargetPackageForUri(scheme, host,
                OnlineLinkRuleRepository.parseSystemServerCandidateEntries(configuration.onlineHostRules));
        if (target == null) {
            log(4, TAG, "clipboard skipped: no_rule host=" + host);
            return;
        }
        log(4, TAG, "clipboard link captured: scheme=" + scheme + ", host=" + host
                + ", target=" + target + ", user=" + userId);
        launchClipboardBridge(uri, target, userId);
    }
    /** system_server starts the same foreground bridge used for tapped links, avoiding Android 16 BAL. */
    private void launchClipboardBridge(Uri uri, String targetPackage, int userId) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            Context systemContext = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            if (systemContext == null) {
                log(5, TAG, "clipboard bridge skipped: system context unavailable");
                return;
            }
            Intent source = new Intent(Intent.ACTION_VIEW, uri);
            source.addCategory(Intent.CATEGORY_BROWSABLE);
            String token = UUID.randomUUID().toString();
            replayTokens.put(token, SystemClock.elapsedRealtime() + REPLAY_TOKEN_WINDOW_MILLIS);
            source.putExtra(EXTRA_NATIVE_REPLAY_TOKEN, token);
            Intent bridge = new Intent(Intent.ACTION_VIEW)
                    .setComponent(new ComponentName(PACKAGE_NAME,
                            "com.huanchengfly.icebridge.activities.BridgeActivity"))
                    .putExtra(EXTRA_AUTO_BRIDGED, true)
                    .putExtra(EXTRA_NATIVE_AUTO_BRIDGED, true)
                    .putExtra(EXTRA_NATIVE_DIRECT_INTENT, false)
                    .putExtra(EXTRA_NATIVE_PREWARM_ONLY, false)
                    .putExtra(EXTRA_NATIVE_BRIDGE_SOURCE, "clipboard")
                    .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                    .putExtra(EXTRA_NATIVE_LINK_URL, uri.toString())
                    .putExtra(EXTRA_ORIGINAL_INTENT, source)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            UserHandle userHandle = (UserHandle) UserHandle.class
                    .getMethod("of", Integer.TYPE).invoke(null, userId);
            Context.class.getMethod("startActivityAsUser", Intent.class, UserHandle.class)
                    .invoke(systemContext, bridge, userHandle);
            log(4, TAG, "clipboard bridge started: target=" + targetPackage + ", user=" + userId);
        } catch (Throwable error) {
            log(5, TAG, "clipboard bridge failed: target=" + targetPackage
                    + ", reason=" + error.getClass().getSimpleName());
        }
    }

    private void recoverPackageFromSignal(String targetPackage, int userId, String source) {
        RecoveryConfiguration configuration = currentRecoveryConfiguration();
        if (!configuration.available) {
            configuration = refreshRecoveryConfiguration(source, true);
        }
        if (!configuration.available || !configuration.enabled) {
            log(4, TAG, "signal recovery skipped: target=" + targetPackage
                    + ", decision=" + (configuration.available ? "CONFIG_DISABLED" : "CONFIG_UNAVAILABLE"));
            return;
        }
        SystemServerPackageStateProbe probe = usablePackageStateProbe();
        if (probe == null || !probe.canEnableDisabledPackage()) {
            log(5, TAG, "signal recovery skipped: target=" + targetPackage + ", decision=SETTER_UNAVAILABLE");
            return;
        }
        SystemServerPackageStateProbe.State state = probe.read(targetPackage, userId);
        if (!state.isAvailable() || !state.suspendedKnown) {
            log(4, TAG, "signal recovery skipped: target=" + targetPackage + ", decision=STATE_UNAVAILABLE");
            return;
        }
        if (!LaunchRecoveryPolicy.isRecoverableState(
                state.enabledSetting, state.hidden, state.hiddenKnown, state.suspended)) {
            log(4, TAG, "signal recovery skipped: target=" + targetPackage
                    + ", decision=STATE_NO_RECOVERY_NEEDED");
            return;
        }
        log(4, TAG, "signal recovery enable attempt: source=" + source
                + ", target=" + targetPackage + ", user=" + userId);
        String failure = probe.enableDisabledPackage(targetPackage, userId);
        log(4, TAG, "signal recovery result: target=" + targetPackage
                + ", resolved=" + (failure == null)
                + (failure == null ? "" : ", reason=" + failure));
    }

    private void installChromeIntentHook(ClassLoader classLoader) {
        if (chromeHookInstalled) {
            return;
        }
        try {
            Class<?> activity = Class.forName("android.app.Activity", false, classLoader);
            Method startActivity = activity.getDeclaredMethod("startActivity", Intent.class);
            hook(startActivity)
                    .setId("icebridge:chrome:start-activity")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Intent original = (Intent) chain.getArg(0);
                        Intent replacement = prepareChromeIntent((Activity) chain.getThisObject(), original);
                        return replacement == original
                                ? chain.proceed()
                                : chain.proceedWith(chain.getThisObject(), new Object[]{replacement});
                    });
            Method startActivityForResult = activity.getDeclaredMethod(
                    "startActivityForResult", Intent.class, Integer.TYPE);
            hook(startActivityForResult)
                    .setId("icebridge:chrome:start-activity-for-result")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Intent original = (Intent) chain.getArg(0);
                        Intent replacement = prepareChromeIntent((Activity) chain.getThisObject(), original);
                        if (replacement == original) {
                            return chain.proceed();
                        }
                        Object[] args = chain.getArgs().toArray();
                        args[0] = replacement;
                        return chain.proceedWith(chain.getThisObject(), args);
                    });
            Method startActivityForResultWithOptions = activity.getDeclaredMethod(
                    "startActivityForResult", Intent.class, Integer.TYPE, Bundle.class);
            hook(startActivityForResultWithOptions)
                    .setId("icebridge:chrome:start-activity-for-result-options")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Intent original = (Intent) chain.getArg(0);
                        Intent replacement = prepareChromeIntent((Activity) chain.getThisObject(), original);
                        if (replacement == original) {
                            return chain.proceed();
                        }
                        Object[] args = chain.getArgs().toArray();
                        args[0] = replacement;
                        return chain.proceedWith(chain.getThisObject(), args);
                    });
            chromeHookInstalled = true;
            log(4, TAG, "Chrome intent hook installed");
        } catch (Throwable error) {
            logError("Chrome intent hook installation failed", error);
        }
    }

    private Intent prepareChromeIntent(Activity activity, Intent original) {
        if (original == null || !Intent.ACTION_VIEW.equals(original.getAction())) {
            return original;
        }
        Uri data = original.getData();
        if (data == null) {
            return original;
        }
        String target = CustomSchemeTargetResolver.findTargetPackage(original);
        if (target == null) {
            target = playStoreTargetPackage(original);
        }
        if (target == null || CHROME_PACKAGE.equals(target)) {
            return original;
        }
        boolean chromeBound = CHROME_PACKAGE.equals(original.getPackage())
                || (original.getComponent() != null
                && CHROME_PACKAGE.equals(original.getComponent().getPackageName()));
        if (!chromeBound) {
            return original;
        }
        RecoveryConfiguration configuration = currentRecoveryConfiguration();
        if (!configuration.available) {
            configuration = refreshRecoveryConfiguration("chrome-auto-open", true);
        }
        if (configuration.enabled && configuration.autoOpenLinks) {
            Intent replacement = new Intent(original);
            replacement.setComponent(null);
            replacement.setPackage(target);
            replacement.addCategory(Intent.CATEGORY_BROWSABLE);
            log(4, TAG, "Chrome auto-open target: host=" + data.getHost()
                    + ", scheme=" + data.getScheme() + ", target=" + target);
            return replacement;
        }
        if (playStoreTargetPackage(original) != null && activity != null) {
            try {
                Intent launcher = activity.getPackageManager().getLaunchIntentForPackage(target);
                if (launcher != null) {
                    launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    log(4, TAG, "Chrome Play Store fallback bridged: target=" + target);
                    return launcher;
                }
            } catch (Throwable error) {
                log(5, TAG, "Chrome Play Store fallback unavailable: "
                        + error.getClass().getSimpleName());
            }
        }
        Intent replacement = new Intent(original);
        replacement.setComponent(null);
        replacement.setPackage(null);
        replacement.addCategory(Intent.CATEGORY_BROWSABLE);
        log(4, TAG, "Chrome App Link released: host=" + data.getHost()
                + ", scheme=" + data.getScheme() + ", target=" + target);
        return replacement;
    }

    private void installActivationHook(ClassLoader classLoader) {
        try {
            Class<?> util = Class.forName("com.huanchengfly.icebridge.utils.Util", false, classLoader);
            Method method = util.getDeclaredMethod("isXposedActive");
            method.setAccessible(true);
            hook(method)
                    .setId("icebridge:is-xposed-active")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> true);
        } catch (Throwable error) {
            logError("activation hook installation failed", error);
        }
    }

    private void logDebug(Context context, String message) {
        log(4, TAG, message);
    }

    private void logError(String message, Throwable error) {
        log(6, TAG, message, error);
    }
}

















