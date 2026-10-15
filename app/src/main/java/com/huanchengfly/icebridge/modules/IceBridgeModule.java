package com.huanchengfly.icebridge.modules;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
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
import com.huanchengfly.icebridge.android16.NativeBridgePolicy;
import com.huanchengfly.icebridge.android16.CustomSchemeTargetResolver;
import com.huanchengfly.icebridge.automation.LinkRuleEntry;
import com.huanchengfly.icebridge.automation.ScopedRuleResolver;
import com.huanchengfly.icebridge.automation.BoundedExpiringDeduplicator;
import com.huanchengfly.icebridge.automation.SnapshotCachePolicy;
import com.huanchengfly.icebridge.android16.PaymentSdkBridgeResolver;
import com.huanchengfly.icebridge.android16.ResolvedLaunch;
import com.huanchengfly.icebridge.android16.SystemServerPackageStateProbe;
import com.huanchengfly.icebridge.android16.SystemAutonomousRuntime;
import com.huanchengfly.icebridge.android16.SystemAutonomousCapture;
import com.huanchengfly.icebridge.android16.AtmsBinderSignatureResolver;
import com.huanchengfly.icebridge.android16.SystemLaunchPreflight;
import com.huanchengfly.icebridge.automation.SystemRuntimeConfig;
import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.UUID;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class IceBridgeModule extends XposedModule {
    private static final String PACKAGE_NAME = "io.github.yudigaga.icebridge";
    private static final String TAG = "IceBridge";
    private static final String CHROME_PACKAGE = "com.android.chrome";
    private static final long CONFIG_RETRY_INTERVAL_MILLIS = 30_000L;
    private static final long FROZEN_CACHE_REFRESH_MILLIS = 60_000L;
    private static final long RECOVERY_DEDUPLICATION_MILLIS = 4_000L;
    private static final long CLIPBOARD_EVENT_DEDUPLICATION_MILLIS = 250L;
    private static final long NATIVE_BRIDGE_DEDUPLICATION_MILLIS = 1_500L;
    private static final long NATIVE_BRIDGE_HANDOFF_MILLIS = 4_000L;
    // The app process must perform Root/IceBox work first. Launching from its receiver is
    // blocked by Android 16 BAL, so system_server retries the original launch after this delay.
    private static final long SYSTEM_AUTO_OPEN_DELAY_MILLIS = 900L;
    private static final String ACTION_RECOVER_LINK = "io.github.yudigaga.icebridge.action.RECOVER_LINK";
    private static final String ACTION_NATIVE_LINK_CAPTURED =
            "io.github.yudigaga.icebridge.action.NATIVE_LINK_CAPTURED";
    private static final String EXTRA_TARGET_PACKAGE = "target_package";
    private static final String EXTRA_ORIGINAL_INTENT = "original_intent";
    private static final String EXTRA_AUTO_OPEN = "auto_open";
    private static final String EXTRA_PLAY_STORE = "play_store";
    private static final String EXTRA_AUTO_BRIDGED = "auto_bridged";
    private static final String EXTRA_NATIVE_AUTO_BRIDGED = "native_auto_bridged";
    private static final String EXTRA_NATIVE_REPLAY_TOKEN = "native_replay_token";
    private static final long REPLAY_TOKEN_WINDOW_MILLIS = 15_000L;
    private static final int MAX_REPLAY_TOKENS = 256;
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
    /** Returned when the framework arguments do not identify the launching user safely. */
    private static final int UNKNOWN_USER_ID = -10000;
    private static final Pattern PACKAGE_NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");

    private volatile SystemServerPackageStateProbe packageStateProbe;
    private volatile RecoveryConfiguration recoveryConfiguration = RecoveryConfiguration.unavailable("not-loaded");
    private volatile long lastRecoveryConfigurationAttemptMillis;
    private volatile long lastSuccessfulRecoveryConfigurationMillis;
    private volatile int recoveryConfigurationFailureCount;
    private volatile boolean expiredConfigurationLogged;
    private volatile boolean chromeHookInstalled;
    private volatile boolean clipboardHookInstalled;
    private volatile Handler systemHandler;
    private volatile HandlerThread systemWorkerThread;
    private volatile ContentObserver configurationObserver;
    private volatile SystemAutonomousRuntime autonomousRuntime;
    private volatile boolean activityInterceptionReady;
    private volatile boolean activityPreflightReady;
    private volatile SystemRuntimeConfig.State compatibilityState;
    private volatile RecoveryConfiguration compatibilityConfiguration;
    private final BoundedExpiringDeduplicator autonomousObservations =
            new BoundedExpiringDeduplicator(1_500L, 256);
    private final Runnable configurationChanged = () -> refreshRecoveryConfiguration("notification", true);
    private volatile Field activityStarterRequestField;
    private volatile Field activityStarterIntentField;
    private final ConcurrentHashMap<String, Long> pendingRecoveries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> replayTokens = new ConcurrentHashMap<>();
    private final BoundedExpiringDeduplicator clipboardEvents =
            new BoundedExpiringDeduplicator(CLIPBOARD_EVENT_DEDUPLICATION_MILLIS, 128);
    private final ConcurrentHashMap<String, Long> nativeBridgeTransactions = new ConcurrentHashMap<>();
    /** Prevent the Android 16 legacy observer from launching the same link after native capture. */
    private final ConcurrentHashMap<String, Long> nativeBridgeHandoffs = new ConcurrentHashMap<>();
    /**
     * ActivityStartInterceptor may not mutate ActivityStarter.mRequest, and HyperOS can expose a
     * different Intent instance to the second hook. Key the short-lived replacement by a stable
     * source fingerprint rather than object identity so the original request cannot continue as a
     * second launch when the two hooks see equivalent Intent instances.
     */
    private final ConcurrentHashMap<String, NativeBridgeReplacement> nativeBridgeReplacements =
            new ConcurrentHashMap<>();

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
        installAtmsBinderPreflight(param.getClassLoader());
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

    private void installAtmsBinderPreflight(ClassLoader loader) {
        Method method = AtmsBinderSignatureResolver.resolve(loader);
        if (method == null) {
            log(5, TAG, "autonomous preflight unavailable: public Binder signature not found");
            return;
        }
        hook(method).setId("icebridge:autonomous-binder-preflight")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    try {
                        SystemAutonomousRuntime runtime = autonomousRuntime;
                        Object original = chain.getArg(AtmsBinderSignatureResolver.INTENT_INDEX);
                        Object user = chain.getArg(AtmsBinderSignatureResolver.USER_ID_INDEX);
                        if (runtime != null && original instanceof Intent && user instanceof Integer) {
                            int callerUser = (Integer) UserHandle.class.getDeclaredMethod("getUserId", Integer.TYPE)
                                    .invoke(null, Binder.getCallingUid());
                            boolean outside = SystemLaunchPreflight.isOutsideGlobalLock(chain.getThisObject(),
                                    Binder.getCallingPid(), android.os.Process.myPid());
                            runtime.prepareOriginalLaunch((Intent) original, (Integer) user, callerUser, outside);
                        }
                    } catch (Throwable error) {
                        log(5, TAG, "autonomous preflight preserved original: " + error.getClass().getSimpleName());
                    }
                    return chain.proceed();
                });
        activityPreflightReady = true;
        log(4, TAG, "autonomous public Binder preflight installed");
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
                    int userId = userIdFromActivityStartInterceptor(
                            chain.getThisObject(), chain.getArgs().toArray());
                    if (thawPaymentPackageAndPassThrough(original, userId)) {
                        // Payment SDK launches must remain untouched. In particular, do not replace
                        // the request with BridgeActivity: ActivityStarter needs the original caller,
                        // resultTo and Activity token to complete the SDK handshake.
                        return chain.proceed();
                    }
                    // The independent path never replaces a request with an IceBridge component.
                    // ActivityStarter observes the same request and queues work after its finally
                    // block, outside the framework launch locks.
                    if (autonomousConfiguration(userId) != null) return chain.proceed();
                    Intent nativeBridge = buildNativeAutoOpenBridge(original, userId, "interceptor");
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
                        AutonomousBridgeCoordinator.Request<Intent> autonomous = null;
                        try {
                            Object request = activityStarterRequestField.get(chain.getThisObject());
                            Intent original = request == null ? null
                                    : (Intent) activityStarterIntentField.get(request);
                            if (isCarrierAlipaySdkActivity(original)) {
                                log(4, TAG, "carrier alipay sdk activity observed: "
                                        + describeIntent(original));
                            }
                            int userId = userIdFromActivityStarterRequest(request);
                            if (thawPaymentPackageAndPassThrough(original, userId)) {
                                // Preserve the original ActivityStarter request and caller chain for
                                // Alipay's signed SDK callback. Only package state is repaired.
                                return chain.proceed();
                            }
                            if (autonomousConfiguration(userId) != null) {
                                autonomous = captureAutonomousRequest(original, userId, request);
                            } else {
                                // The normal mode keeps its existing foreground app trampoline.
                                Intent bridge = buildNativeAutoOpenBridge(original, userId, "activity_starter");
                                if (bridge == null) bridge = consumeNativeBridgeReplacement(original, userId);
                                if (bridge != null) {
                                    activityStarterIntentField.set(request, new Intent(bridge));
                                    log(4, TAG, "native link redirected via ActivityStarter: target="
                                            + bridge.getStringExtra(EXTRA_TARGET_PACKAGE));
                                }
                            }
                        } catch (Throwable error) {
                            log(5, TAG, "global auto-open fallback skipped: "
                                    + error.getClass().getSimpleName());
                        }
                        // Always execute the framework's own finally/cleanup. Only a failed,
                        // result-free direct launch can be retried with the system's identity.
                        Object result = chain.proceed();
                        if (autonomous != null) {
                            boolean web = autonomous.kind == AutonomousBridgeCoordinator.Kind.WEB_RULE
                                    && autonomous.explicitTarget == null;
                            boolean missing = result instanceof Integer
                                    && ((Integer) result == -1 || (Integer) result == -2);
                            if (web || missing) {
                                enqueueAutonomous(autonomous);
                            }
                        }
                        return result;
                    });
            log(4, TAG, "global auto-open fallback hook installed: " + resolved.description);
            activityInterceptionReady = true;
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
    /**
     * Repairs a frozen Alipay package without replacing the SDK's original Activity request.
     *
     * <p>The old BridgeActivity replay path changes the caller UID and loses the framework
     * result/token relationship used by H5PayActivity. Returning true means the request has been
     * recognized as an Alipay SDK handoff and the caller must immediately continue with the
     * untouched Intent.</p>
     */
    private boolean thawPaymentPackageAndPassThrough(Intent intent, int userId) {
        RecoveryConfiguration configuration = currentRecoveryConfiguration();
        if (intent == null || !configuration.available || !configuration.enabled
                || !configuration.paymentSdkBridge) {
            return false;
        }
        if (!matchesSnapshotUser(configuration, userId)) return false;
        PaymentSdkBridgeResolver.Candidate candidate = PaymentSdkBridgeResolver.resolve(intent);
        if (candidate == null || !candidate.prewarmOnly
                || !PaymentSdkBridgeResolver.ALIPAY_PACKAGE.equals(candidate.targetPackage)) {
            return false;
        }
        log(4, TAG, "payment_sdk_detected: target=" + candidate.targetPackage
                + ", user=" + userId + ", component=" + componentOf(intent));
        if (userId == UNKNOWN_USER_ID) {
            // Never thaw a package in user 0 merely because an OEM changed the interceptor
            // signature. Preserve the SDK's original request and let the framework report any
            // launch failure rather than changing the wrong user's package state.
            log(4, TAG, "payment_sdk_system_thaw_skipped: target=" + candidate.targetPackage
                    + ", reason=user-unknown");
            return true;
        }
        SystemServerPackageStateProbe probe = usablePackageStateProbe();
        if (probe == null || !probe.canEnableDisabledPackage()) {
            log(4, TAG, "payment_sdk_system_thaw_fail: target=" + candidate.targetPackage
                    + ", reason=setter-unavailable");
            return true;
        }
        SystemServerPackageStateProbe.State state = probe.read(candidate.targetPackage, userId);
        if (!state.isAvailable() || !state.suspendedKnown) {
            log(4, TAG, "payment_sdk_system_thaw_fail: target=" + candidate.targetPackage
                    + ", reason=state-unavailable");
            return true;
        }
        if (LaunchRecoveryPolicy.isRecoverableState(
                state.enabledSetting, state.hidden, state.hiddenKnown, state.suspended)) {
            log(4, TAG, "payment_sdk_system_thaw_attempt: target=" + candidate.targetPackage
                    + ", user=" + userId);
            String failure = probe.enableDisabledPackage(candidate.targetPackage, userId);
            if (failure != null) {
                log(4, TAG, "payment_sdk_system_thaw_fail: target=" + candidate.targetPackage
                        + ", reason=" + failure);
            } else {
                log(4, TAG, "payment_sdk_system_thaw_ok: target=" + candidate.targetPackage
                        + ", user=" + userId);
            }
        }
        log(4, TAG, "payment_sdk_original_intent_passed_through: target="
                + candidate.targetPackage + ", component=" + componentOf(intent));
        return true;
    }

    private int userIdFromActivityStartInterceptor(Object receiver, Object[] args) {
        // AOSP and OEM builds have kept the interceptor's user field more stable than the
        // positional argument list. Prefer it when present, and never infer user 0 on failure.
        int reflectedUserId = userIdFromField(receiver);
        if (reflectedUserId != UNKNOWN_USER_ID) {
            return reflectedUserId;
        }

        // Android 16 currently exposes (callingPid, callingUid) at indexes 6 and 7. The UID is
        // converted with the framework helper instead of assuming the 100000-per-user layout.
        if (args != null && args.length > 7 && args[7] instanceof Integer) {
            int callingUid = (Integer) args[7];
            if (callingUid >= 0) {
                try {
                    // getUserId is hidden from the app compile SDK, but is available in the
                    // system_server runtime. Resolve it reflectively to keep the module buildable
                    // while avoiding a hard-coded UID layout.
                    Method method = UserHandle.class.getDeclaredMethod("getUserId", Integer.TYPE);
                    method.setAccessible(true);
                    Object value = method.invoke(null, callingUid);
                    if (value instanceof Integer && (Integer) value >= 0) {
                        return (Integer) value;
                    }
                } catch (Throwable error) {
                    log(5, TAG, "activity interceptor user lookup failed: "
                            + error.getClass().getSimpleName());
                }
            }
        }
        log(4, TAG, "activity interceptor user unknown: argument shape unavailable");
        return UNKNOWN_USER_ID;
    }

    private int userIdFromField(Object object) {
        if (object == null) {
            return UNKNOWN_USER_ID;
        }
        for (String fieldName : new String[]{"mUserId", "userId"}) {
            Field field = findField(object.getClass(), fieldName);
            if (field == null) {
                continue;
            }
            try {
                Object value = field.get(object);
                if (value instanceof Integer && (Integer) value >= 0) {
                    return (Integer) value;
                }
            } catch (Throwable ignored) {
                // Try the next known field, then fall back to the calling UID.
            }
        }
        return UNKNOWN_USER_ID;
    }

    private int userIdFromActivityStarterRequest(Object request) {
        int userId = userIdFromField(request);
        if (userId == UNKNOWN_USER_ID) {
            log(4, TAG, "activity starter user unknown: request field unavailable");
        }
        return userId;
    }

    private boolean isCarrierAlipaySdkActivity(Intent intent) {
        if (intent == null || intent.getComponent() == null) return false;
        // The host is intentionally not restricted to China Unicom: Unicom, Telecom and
        // other apps embed the same Alipay SDK Activity classes.
        return intent.getComponent().getClassName().startsWith("com.alipay.sdk.app.");
    }

    private SystemRuntimeConfig.State autonomousConfiguration(int userId) {
        SystemAutonomousRuntime runtime = autonomousRuntime;
        return runtime == null ? null : runtime.current(userId);
    }

    /** Only value inspection runs in the hook. No Binder, disk, network or package query. */
    private AutonomousBridgeCoordinator.Request<Intent> captureAutonomousRequest(
            Intent original, int userId, Object frameworkRequest) {
        SystemRuntimeConfig.State configuration = autonomousConfiguration(userId);
        if (original == null || configuration == null || !configuration.recoveryEnabled
                || !configuration.autoOpenLinks) return null;
        String token = original.getStringExtra(EXTRA_NATIVE_REPLAY_TOKEN);
        if (token != null && consumeReplayToken(token)) return null;
        String caller = null;
        boolean hasSourceToken = true;
        Integer requestCode = null;
        if (frameworkRequest != null) {
            try {
                Field source = findField(frameworkRequest.getClass(), "resultTo");
                Field code = findField(frameworkRequest.getClass(), "requestCode");
                Field callingPackage = findField(frameworkRequest.getClass(), "callingPackage");
                if (source != null) hasSourceToken = source.get(frameworkRequest) != null;
                Object value = code == null ? null : code.get(frameworkRequest);
                if (value instanceof Integer) requestCode = (Integer) value;
                Object name = callingPackage == null ? null : callingPackage.get(frameworkRequest);
                if (name instanceof String) caller = (String) name;
            } catch (IllegalAccessException ignored) {
                requestCode = null;
                hasSourceToken = true;
            }
        }
        SystemAutonomousCapture.Result capture = SystemAutonomousCapture.capture(original,
                userId, configuration, caller, hasSourceToken, requestCode);
        if (capture.recognizedSource) {
            log(4, TAG, "autonomous web observation: reason=" + capture.reason
                    + ", source=" + (original.getComponent() == null ? "none"
                    : original.getComponent().getPackageName()) + ", user=" + userId
                    + ", sourceToken=" + hasSourceToken + ", requestCode="
                    + (requestCode == null ? "unknown" : requestCode));
        }
        return capture.request;
    }

    private void enqueueAutonomous(AutonomousBridgeCoordinator.Request<Intent> request) {
        Handler worker = systemHandler;
        if (worker == null || autonomousRuntime == null) return;
        String key = request.userId + ":" + request.sourcePackage + ":" + request.fingerprint;
        if (!autonomousObservations.claim(key, SystemClock.elapsedRealtime())) return;
        worker.post(() -> {
            SystemAutonomousRuntime runtime = autonomousRuntime;
            if (runtime != null) runtime.execute(request);
        });
    }

    private void ensureAutonomousRuntime(Context context) {
        if (autonomousRuntime != null || systemHandler == null || context == null) return;
        SystemAutonomousRuntime runtime = new SystemAutonomousRuntime(context, systemHandler,
                (intent, user, source) -> {
                    String token = UUID.randomUUID().toString();
                    pruneReplayTokens(SystemClock.elapsedRealtime());
                    replayTokens.put(token, SystemClock.elapsedRealtime() + REPLAY_TOKEN_WINDOW_MILLIS);
                    intent.putExtra(EXTRA_NATIVE_REPLAY_TOKEN, token);
                }, message -> log(4, TAG, message));
        // Context can exist before PackageManager finishes bootstrapping. Retry next tick instead
        // of permanently publishing an unavailable Binder adapter into the independent runtime.
        if (!runtime.isReady()) return;
        runtime.restore();
        autonomousRuntime = runtime;
    }

    private Intent buildNativeAutoOpenBridge(Intent original, int userId, String hookName) {
        if (original == null) {
            return null;
        }
        RecoveryConfiguration configuration = currentRecoveryConfiguration();
        if (!matchesSnapshotUser(configuration, userId)) return null;
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
        String conversion = LinkRuleEntry.CONVERSION_NONE;
        if (captured != null) {
            rawUrl = captured.url;
            bridgeSource = captured.source;
            try {
                Uri uri = Uri.parse(rawUrl);
                List<LinkRuleEntry> matches = ScopedRuleResolver.matchingRules(configuration.scopedRules,
                        captured.sourcePackage, uri.getHost());
                List<String> candidates = ScopedRuleResolver.targetPackages(matches);
                LinkRuleEntry selected = ScopedRuleResolver.selectFrozenRule(matches, configuration.frozenPackages);
                target = selected == null ? null : selected.targetPackage;
                conversion = selected == null ? LinkRuleEntry.CONVERSION_NONE : selected.urlConversion;
                log(4, TAG, "native target candidates: host=" + uri.getHost()
                        + ", candidates=" + candidates
                        + ", selected=" + target
                        + ", frozenSnapshotContains="
                        + (target != null && configuration.frozenPackages != null
                        && configuration.frozenPackages.contains(target))
                        + ", hook=" + hookName);
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
        // A non-empty frozen-package snapshot lets us distinguish a target that still needs
        // thawing from one that is already enabled (or whose snapshot is stale). Preserve the
        // original Intent for the latter; only frozen targets need BridgeActivity. The UUID replay
        // token above remains the loop guard for the bridge path and is one-shot/short-lived.
        if (NativeBridgePolicy.shouldBypass(prewarmOnly, configuration.frozenPackages, target)) {
            log(4, TAG, "native bridge skipped: target already enabled or snapshot stale: target="
                    + target + ", source=" + bridgeSource);
            // Preserve the original intent for targets that are not frozen according to the
            // current snapshot. Replacing it with BridgeActivity changes the caller/task/flags
            // and can crash or otherwise confuse apps with sensitive deep-link activities.
            return null;
        }
        String sourcePackage = captured == null ? packageOf(original)
                : (captured.sourcePackage == null ? packageOf(original) : captured.sourcePackage);
        String sourceActivity = captured == null ? componentOf(original)
                : captured.sourceActivity;
        if (!claimNativeBridgeTransaction(rawUrl, target, sourcePackage, sourceActivity, userId, hookName)) {
            return null;
        }
        // The saved source Intent must carry the loop guard: if we deliberately replay it for an
        // already-enabled target or after a failure, system_server must not capture it a second time.
        Intent sourcePayload = new Intent(original);
        String token = UUID.randomUUID().toString();
        long tokenExpiresAt = SystemClock.elapsedRealtime() + REPLAY_TOKEN_WINDOW_MILLIS;
        pruneReplayTokens(SystemClock.elapsedRealtime());
        replayTokens.put(token, tokenExpiresAt);
        sourcePayload.removeExtra(EXTRA_NATIVE_AUTO_BRIDGED);
        sourcePayload.putExtra(EXTRA_NATIVE_REPLAY_TOKEN, token);
        log(4, TAG, "native replay token created=" + shortToken(token));
        Intent bridge = new Intent(Intent.ACTION_VIEW)
                .setComponent(new ComponentName(PACKAGE_NAME,
                        "com.huanchengfly.icebridge.activities.BridgeActivity"))
                .putExtra(EXTRA_AUTO_BRIDGED, true)
                .putExtra(EXTRA_NATIVE_AUTO_BRIDGED, true)
                .putExtra(EXTRA_NATIVE_DIRECT_INTENT, directIntent)
                .putExtra(EXTRA_NATIVE_PREWARM_ONLY, prewarmOnly)
                .putExtra(EXTRA_NATIVE_BRIDGE_SOURCE, bridgeSource)
                .putExtra(EXTRA_USER_ID, userId)
                .putExtra("rule_conversion", conversion)
                .putExtra(EXTRA_SOURCE_PACKAGE, sourcePackage)
                .putExtra(EXTRA_SOURCE_ACTIVITY, sourceActivity)
                .putExtra(EXTRA_TARGET_PACKAGE, target)
                .putExtra(EXTRA_NATIVE_LINK_URL, rawUrl)
                .putExtra(EXTRA_ORIGINAL_INTENT, sourcePayload);
        if (!prewarmOnly) {
            // Ordinary links use an isolated temporary task so leaving the target does not expose
            // IceBridge's UI. A payment SDK request is different: ActivityStarter may carry the
            // merchant H5 Activity's result token. Keep the bridge in that caller task so the
            // replay below can forward the payment result to the original SDK caller.
            bridge.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        // Only the interceptor needs this hand-off. When its local argument is replaced, the
        // enclosing ActivityStarter still owns the old Intent and will call this method again.
        markNativeBridgeHandoff(rawUrl, target, sourcePackage, sourceActivity, userId);
        if ("interceptor".equals(hookName)) {
            rememberNativeBridgeReplacement(original, bridge, userId);
        }
        return bridge;
    }

    private void rememberNativeBridgeReplacement(Intent original, Intent bridge, int userId) {
        if (original == null || bridge == null) return;
        long now = SystemClock.elapsedRealtime();
        pruneNativeBridgeReplacements(now);
        String fingerprint = userId + ":" + sourceFingerprint(original);
        nativeBridgeReplacements.put(fingerprint,
                new NativeBridgeReplacement(new Intent(bridge), now, fingerprint));
    }

    private Intent consumeNativeBridgeReplacement(Intent original, int userId) {
        if (original == null) return null;
        if (!matchesSnapshotUser(currentRecoveryConfiguration(), userId)) return null;
        long now = SystemClock.elapsedRealtime();
        String fingerprint = userId + ":" + sourceFingerprint(original);
        NativeBridgeReplacement replacement = nativeBridgeReplacements.remove(fingerprint);
        if (replacement == null) {
            pruneNativeBridgeReplacements(now);
            return null;
        }
        long age = now - replacement.createdAt;
        if (age < 0L || age >= NATIVE_BRIDGE_DEDUPLICATION_MILLIS) {
            log(4, TAG, "native bridge replacement expired ageMs=" + age);
            return null;
        }
        if (!fingerprint.equals(replacement.sourceFingerprint)) {
            log(4, TAG, "native bridge replacement source mismatch");
            return null;
        }
        return new Intent(replacement.bridge);
    }

    private void pruneNativeBridgeReplacements(long now) {
        for (Map.Entry<String, NativeBridgeReplacement> entry
                : nativeBridgeReplacements.entrySet()) {
            NativeBridgeReplacement replacement = entry.getValue();
            if (replacement == null || now < replacement.createdAt
                    || now - replacement.createdAt >= NATIVE_BRIDGE_DEDUPLICATION_MILLIS) {
                nativeBridgeReplacements.remove(entry.getKey(), replacement);
            }
        }
    }

    /** Stable identity for the source request, excluding IceBridge transport extras. */
    private static String sourceFingerprint(Intent intent) {
        if (intent == null) return "<null>";
        StringBuilder result = new StringBuilder(192)
                .append(intent.getAction()).append('|')
                .append(intent.getDataString()).append('|')
                .append(intent.getPackage()).append('|')
                .append(intent.getComponent() == null ? null
                        : intent.getComponent().flattenToString()).append('|')
                .append(intent.getType()).append('|');
        Set<String> categories = intent.getCategories();
        if (categories != null && !categories.isEmpty()) {
            java.util.ArrayList<String> sorted = new java.util.ArrayList<>(categories);
            java.util.Collections.sort(sorted);
            for (String category : sorted) result.append(category).append(',');
        }
        return result.toString();
    }

    private void markNativeBridgeHandoff(String url, String targetPackage,
                                         String sourcePackage, String sourceActivity, int userId) {
        String key = userId + ":" + nativeBridgeHandoffKey(url, targetPackage, sourcePackage, sourceActivity);
        long now = SystemClock.elapsedRealtime();
        nativeBridgeHandoffs.put(key, now);
        pruneNativeBridgeHandoffs(now);
    }

    private boolean isNativeBridgeHandoffRecent(Intent original, String targetPackage, int userId) {
        if (original == null || targetPackage == null) return false;
        String url = original.getDataString();
        if (url == null) url = IntentUrlExtractor.findEmbeddedUrl(original.getExtras());
        String sourcePackage = packageOf(original);
        String sourceActivity = componentOf(original);
        String key = userId + ":" + nativeBridgeHandoffKey(url, targetPackage, sourcePackage, sourceActivity);
        Long previous = nativeBridgeHandoffs.get(key);
        long now = SystemClock.elapsedRealtime();
        if (previous == null) return false;
        if (now < previous || now - previous >= NATIVE_BRIDGE_HANDOFF_MILLIS) {
            nativeBridgeHandoffs.remove(key, previous);
            return false;
        }
        return true;
    }

    private String nativeBridgeHandoffKey(String url, String targetPackage,
                                          String sourcePackage, String sourceActivity) {
        // The same system launch can be represented by different Intent objects and source
        // metadata at the two hook points. URL + target is the stable transaction identity;
        // source fields are intentionally not part of the key.
        return (url == null ? "" : url) + "|" + (targetPackage == null ? "" : targetPackage);
    }

    private void pruneNativeBridgeHandoffs(long now) {
        if (nativeBridgeHandoffs.size() <= 128) return;
        for (Map.Entry<String, Long> entry : nativeBridgeHandoffs.entrySet()) {
            Long timestamp = entry.getValue();
            if (timestamp == null || now < timestamp
                    || now - timestamp >= NATIVE_BRIDGE_HANDOFF_MILLIS) {
                nativeBridgeHandoffs.remove(entry.getKey(), timestamp);
            }
        }
    }

    private boolean claimNativeBridgeTransaction(String url, String targetPackage,
                                                 String sourcePackage, String sourceActivity,
                                                  int userId, String hookName) {
        String key = userId + ":" + (url == null ? "" : url)
                + "|" + (targetPackage == null ? "" : targetPackage)
                + "|" + (sourcePackage == null ? "" : sourcePackage)
                + "|" + (sourceActivity == null ? "" : sourceActivity);
        long now = SystemClock.elapsedRealtime();
        // Do not use put() here: a duplicate request must not refresh its timestamp and extend
        // the suppression window indefinitely while ActivityStartInterceptor and ActivityStarter
        // both observe the same launch. CAS also keeps the first claimant authoritative under
        // concurrent hooks.
        while (true) {
            Long previous = nativeBridgeTransactions.get(key);
            if (previous != null
                    && now >= previous
                    && now - previous < NATIVE_BRIDGE_DEDUPLICATION_MILLIS) {
                log(4, TAG, "native duplicate suppressed: hook=" + hookName
                        + ", target=" + targetPackage + ", source=" + sourcePackage);
                return false;
            }
            if (previous == null) {
                if (nativeBridgeTransactions.putIfAbsent(key, now) == null) {
                    break;
                }
            } else if (nativeBridgeTransactions.replace(key, previous, now)) {
                break;
            }
        }
        if (nativeBridgeTransactions.size() > 128) {
            for (Map.Entry<String, Long> entry : nativeBridgeTransactions.entrySet()) {
                Long timestamp = entry.getValue();
                if (timestamp == null || timestamp > now
                        || now - timestamp >= NATIVE_BRIDGE_DEDUPLICATION_MILLIS) {
                    nativeBridgeTransactions.remove(entry.getKey(), timestamp);
                }
            }
        }
        return true;
    }

    private static final class NativeBridgeReplacement {
        final Intent bridge;
        final long createdAt;
        final String sourceFingerprint;

        NativeBridgeReplacement(Intent bridge, long createdAt, String sourceFingerprint) {
            this.bridge = bridge;
            this.createdAt = createdAt;
            this.sourceFingerprint = sourceFingerprint;
        }
    }

    private boolean consumeReplayToken(String token) {
        long now = SystemClock.elapsedRealtime();
        pruneReplayTokens(now);
        Long expires = replayTokens.remove(token);
        if (expires == null) return false;
        if (expires <= now) {
            log(4, TAG, "native replay token expired=" + shortToken(token));
            return false;
        }
        return true;
    }

    private void pruneReplayTokens(long now) {
        for (Map.Entry<String, Long> entry : replayTokens.entrySet()) {
            Long expires = entry.getValue();
            if (expires == null || expires <= now) {
                replayTokens.remove(entry.getKey(), expires);
            }
        }
        // A burst of malformed/external launches must not grow this map without bound. Entries
        // are one-shot and short-lived; removing an arbitrary entry is safer than retaining
        // unbounded replay material.
        while (replayTokens.size() >= MAX_REPLAY_TOKENS) {
            String key = replayTokens.keys().hasMoreElements()
                    ? replayTokens.keys().nextElement() : null;
            if (key == null || replayTokens.remove(key) == null) {
                break;
            }
        }
    }

    private static String shortToken(String token) {
        return token == null ? "none" : token.substring(0, Math.min(8, token.length()));
    }

    private Intent buildGlobalAutoOpenBridge(Intent original, int userId) {
        if (original == null || original.getBooleanExtra(EXTRA_AUTO_BRIDGED, false)) {
            return null;
        }
        RecoveryConfiguration configuration = currentRecoveryConfiguration();
        if (!configuration.available || !configuration.enabled || !configuration.autoOpenLinks) {
            return null;
        }
        if (userId == UNKNOWN_USER_ID) {
            log(4, TAG, "global auto-open skipped: user unknown");
            return null;
        }
        String target = cachedFrozenTarget(original, userId, configuration.frozenPackages, configuration.onlineHostCandidates);
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
                    int userId = chain.getArg(2) instanceof Integer
                            ? (Integer) chain.getArg(2) : UNKNOWN_USER_ID;
                    if (userId == UNKNOWN_USER_ID) {
                        log(4, TAG, "frozen-link observer skipped: user unknown");
                        return chain.proceed();
                    }
                    String targetPackage = cachedFrozenTarget(intent, userId, configuration.frozenPackages, configuration.onlineHostCandidates);
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

    private String cachedFrozenTarget(Intent intent, int userId, Set<String> frozenPackages, Map<String, List<String>> onlineHostCandidates) {
        if (frozenPackages == null || frozenPackages.isEmpty()) {
            return null;
        }
        String target = LaunchRecoveryPolicy.getExplicitTargetPackage(intent, userId);
        if (target == null) {
            target = playStoreTargetPackage(intent);
        }
        if (target == null && intent != null && intent.getData() != null) {
            Uri data = intent.getData();
            List<String> candidates = CustomSchemeTargetResolver.findTargetPackagesForUri(
                    data.getScheme(), data.getHost(), onlineHostCandidates);
            // Keep the legacy observer consistent with native capture: one frozen candidate is
            // safe, while multiple frozen packages are ambiguous and must be left to Android's
            // normal resolver instead of waking whichever package happens to be first in a rule.
            target = NativeBridgePolicy.selectCandidate(candidates, frozenPackages);
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
                registerConfigurationObserver();
                RecoveryConfiguration configuration = refreshRecoveryConfiguration("frozen-cache", true);
                SystemAutonomousRuntime runtime = autonomousRuntime;
                if (runtime != null) runtime.tick();
                log(4, TAG, "frozen package cache: status=" + configuration.status
                        + ", packages=" + configuration.frozenPackages.size());
                Handler currentWorker = systemHandler;
                if (currentWorker != null) {
                    currentWorker.postDelayed(this, FROZEN_CACHE_REFRESH_MILLIS);
                }
            }
        }, delayMillis);
    }

    /** Provider access and observer setup stay on the worker, outside Activity/clipboard locks. */
    private void registerConfigurationObserver() {
        if (configurationObserver != null || systemHandler == null) return;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            if (context == null) return;
            ContentObserver observer = new ContentObserver(systemHandler) {
                @Override public void onChange(boolean selfChange) {
                    Handler worker = systemHandler;
                    if (worker == null) return;
                    worker.removeCallbacks(configurationChanged);
                    worker.postDelayed(configurationChanged, 150L);
                }
            };
            context.getContentResolver().registerContentObserver(
                    Uri.parse("content://" + SystemServerConfigContract.AUTHORITY + "/config"), true, observer);
            configurationObserver = observer;
            log(4, TAG, "configuration observer registered");
        } catch (Throwable error) {
            log(5, TAG, "configuration observer unavailable: " + error.getClass().getSimpleName());
        }
    }

    private void scheduleNativeActivityLinkCapture(Intent original, int userId) {
        RecoveryConfiguration configuration = currentRecoveryConfiguration();
        if (!configuration.available || !configuration.enabled || !configuration.autoOpenLinks
                || !configuration.nativeLinkCapture || userId == UNKNOWN_USER_ID) {
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
                captured.sourceActivity, userId));
    }

    private void dispatchLinkToApp(String url, String source, String sourcePackage,
                                   String sourceActivity, int userId) {
        if (userId == UNKNOWN_USER_ID) {
            log(4, TAG, "native link dispatch skipped: user unknown");
            return;
        }
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
        if (linkKey == null && original != null) {
            linkKey = IntentUrlExtractor.findEmbeddedUrl(original.getExtras());
        }
        if (linkKey == null) linkKey = sourceFingerprint(original);
        String sourcePackage = original == null ? "" : original.getPackage();
        if (sourcePackage == null && original != null && original.getComponent() != null) {
            sourcePackage = original.getComponent().getPackageName();
        }
        String key = userId + ":" + sourcePackage + ":" + targetPackage + ":" + linkKey;
        long now = SystemClock.elapsedRealtime();
        if (isNativeBridgeHandoffRecent(original, targetPackage, userId)) {
            log(4, TAG, "legacy recovery skipped: native bridge already claimed target="
                    + targetPackage);
            return;
        }
        Long previous = pendingRecoveries.put(key, now);
        if (previous != null && now - previous < RECOVERY_DEDUPLICATION_MILLIS) {
            return;
        }
        final long recoveryScheduledAt = now;
        worker.post(() -> {
            try {
                // Native capture can win after this recovery was queued but before the worker
                // runs. Check again here so the legacy path cannot thaw/open the same request.
                if (isNativeBridgeHandoffRecent(original, targetPackage, userId)) {
                    log(4, TAG, "legacy recovery worker skipped: native bridge already claimed target="
                            + targetPackage);
                    return;
                }
                boolean dispatched = dispatchRecoveryToApp(
                        original, targetPackage, userId, autoOpen, playStoreFallback);
                if (dispatched && autoOpen) {
                    // Root/IceBox activation runs in the app process. Do not start the target from
                    // that BroadcastReceiver: Android 16 blocks it as a background activity launch.
                    worker.postDelayed(() -> launchRecoveredTargetFromSystem(
                            original, targetPackage, userId, playStoreFallback),
                            SYSTEM_AUTO_OPEN_DELAY_MILLIS);
                }
            } finally {
                // Do not remove a newer request that reused the same key while this worker ran.
                pendingRecoveries.remove(key, recoveryScheduledAt);
            }
        });
    }

    private boolean dispatchRecoveryToApp(Intent original, String targetPackage, int userId,
                                          boolean autoOpen, boolean playStoreFallback) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            Context systemContext = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            if (systemContext == null) {
                log(5, TAG, "frozen-link dispatch skipped: system context unavailable");
                return false;
            }
            Intent request = new Intent(ACTION_RECOVER_LINK)
                    .setComponent(new ComponentName(PACKAGE_NAME, RECOVERY_RECEIVER))
                    .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                    .putExtra(EXTRA_ORIGINAL_INTENT, original)
                    .putExtra(EXTRA_AUTO_OPEN, autoOpen)
                    .putExtra(EXTRA_PLAY_STORE, playStoreFallback)
                    .putExtra(EXTRA_SOURCE_PACKAGE,
                            original == null ? null : (original.getPackage() != null
                                    ? original.getPackage()
                                    : original.getComponent() == null ? null
                                    : original.getComponent().getPackageName()))
                    .putExtra(EXTRA_USER_ID, userId)
                    .putExtra("original_url", original == null ? null : original.getDataString());
            UserHandle userHandle = (UserHandle) UserHandle.class
                    .getMethod("of", Integer.TYPE).invoke(null, userId);
            Context.class.getMethod("sendBroadcastAsUser", Intent.class, UserHandle.class)
                    .invoke(systemContext, request, userHandle);
            log(4, TAG, "frozen-link recovery dispatched: target=" + targetPackage
                    + ", user=" + userId + ", autoOpen=" + autoOpen);
            return true;
        } catch (Throwable error) {
            log(5, TAG, "frozen-link dispatch failed: target=" + targetPackage
                    + ", reason=" + error.getClass().getSimpleName());
            return false;
        }
    }

    private void launchRecoveredTargetFromSystem(Intent original, String targetPackage, int userId,
                                                 boolean playStoreFallback) {
        if (isNativeBridgeHandoffRecent(original, targetPackage, userId)) {
            log(4, TAG, "legacy recovery launch skipped: native bridge already claimed target="
                    + targetPackage);
            return;
        }
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
        SystemRuntimeConfig.State retained = autonomousConfiguration(0);
        if (retained != null) {
            if (compatibilityState != retained) {
                compatibilityConfiguration = RecoveryConfiguration.autonomous(retained);
                compatibilityState = retained;
            }
            return compatibilityConfiguration;
        }
        RecoveryConfiguration current = recoveryConfiguration;
        if (current.available && !SnapshotCachePolicy.isUsable(
                lastSuccessfulRecoveryConfigurationMillis, SystemClock.elapsedRealtime())) {
            if (!expiredConfigurationLogged) {
                expiredConfigurationLogged = true;
                log(5, TAG, "configuration expired; preserving original requests until refresh succeeds");
            }
            return RecoveryConfiguration.unavailable("snapshot-expired");
        }
        return current;
    }

    private boolean matchesSnapshotUser(RecoveryConfiguration configuration, int userId) {
        if (!configuration.available) return false;
        if (SnapshotCachePolicy.matchesUser(configuration.userId, userId)) return true;
        log(4, TAG, "request skipped: snapshot-user=" + configuration.userId
                + ", request-user=" + userId + ", reason=unsupported-user");
        return false;
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
        if (loaded.available) {
            recoveryConfiguration = loaded;
            recoveryConfigurationFailureCount = 0;
            lastSuccessfulRecoveryConfigurationMillis = now;
            expiredConfigurationLogged = false;
            log(4, TAG, "resolve recovery configuration: source=" + source
                    + ", state=available"
                    + ", status=" + loaded.status
                    + ", enabled=" + loaded.enabled
                    + ", autoOpen=" + loaded.autoOpenLinks
                    + ", nativeCapture=" + loaded.nativeLinkCapture
                    + ", paymentSdkBridge=" + loaded.paymentSdkBridge);
            return loaded;
        }

        recoveryConfigurationFailureCount++;
        // Never replace a last-known-good snapshot with a transient provider failure.
        // A system_server restart or LitePal initialization race can make the provider
        // unavailable for a short period; retaining the previous snapshot prevents a
        // temporary read failure from silently disabling all recovery behavior.
        if (current.available) {
            long age = lastSuccessfulRecoveryConfigurationMillis <= 0L
                    ? -1L : now - lastSuccessfulRecoveryConfigurationMillis;
            log(5, TAG, "resolve recovery configuration: source=" + source
                    + ", state=" + (SnapshotCachePolicy.isUsable(lastSuccessfulRecoveryConfigurationMillis, now)
                    ? "stale-cache" : "expired-cache")
                    + ", status=" + loaded.status
                    + ", failures=" + recoveryConfigurationFailureCount
                    + ", cacheAgeMs=" + age);
            return SnapshotCachePolicy.isUsable(lastSuccessfulRecoveryConfigurationMillis, now)
                    ? current : RecoveryConfiguration.unavailable("snapshot-expired");
        }

        recoveryConfiguration = loaded;
        log(5, TAG, "resolve recovery configuration: source=" + source
                + ", state=unavailable"
                + ", status=" + loaded.status
                + ", failures=" + recoveryConfigurationFailureCount);
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
            ensureAutonomousRuntime(systemContext);
            SystemAutonomousRuntime runtime = autonomousRuntime;
            if (runtime != null && runtime.ownerFrozen()) {
                SystemRuntimeConfig.State retained = runtime.current(0);
                return retained == null ? RecoveryConfiguration.unavailable("owner-frozen-no-retained-config")
                        : RecoveryConfiguration.autonomous(retained);
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
            if (parsed.available && runtime != null) {
                runtime.accept(parsed, activityInterceptionReady && activityPreflightReady
                        && (!parsed.clipboardAutoOpen || clipboardHookInstalled));
                SystemRuntimeConfig.State retained = runtime.current(parsed.userId);
                if (retained != null) return RecoveryConfiguration.autonomous(retained);
            }
            return parsed.available
                    ? RecoveryConfiguration.available(parsed.recoveryEnabled, parsed.autoOpenLinks,
                    parsed.nativeLinkCapture, parsed.clipboardAutoOpen, parsed.paymentSdkBridge,
                    parsed.frozenPackages, parsed.onlineHostRules, parsed.userId)
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
        final List<LinkRuleEntry> scopedRules;
        final int userId;
        /** Parsed once when the immutable configuration snapshot is published. */
        final Map<String, List<String>> onlineHostCandidates;

        private RecoveryConfiguration(boolean available, boolean enabled, boolean autoOpenLinks,
                                      boolean nativeLinkCapture, boolean clipboardAutoOpen,
                                      boolean paymentSdkBridge, String status,
                                      Set<String> frozenPackages, String[] onlineHostRules, int userId) {
            this.available = available;
            this.enabled = enabled;
            this.autoOpenLinks = autoOpenLinks;
            this.nativeLinkCapture = nativeLinkCapture;
            this.clipboardAutoOpen = clipboardAutoOpen;
            this.paymentSdkBridge = paymentSdkBridge;
            this.status = status;
            this.frozenPackages = frozenPackages;
            this.onlineHostRules = onlineHostRules == null ? new String[0] : onlineHostRules.clone();
            this.scopedRules = ScopedRuleResolver.decode(this.onlineHostRules);
            this.userId = userId;
            // Retained for inactive legacy paths: they have no trustworthy source, so wildcard only.
            Map<String, List<String>> wildcard = new java.util.LinkedHashMap<>();
            for (LinkRuleEntry entry : scopedRules) {
                if (!"*".equals(entry.sourcePackage)) continue;
                List<String> packages = wildcard.get(entry.host);
                if (packages == null) { packages = new java.util.ArrayList<>(); wildcard.put(entry.host, packages); }
                if (!packages.contains(entry.targetPackage)) packages.add(entry.targetPackage);
            }
            this.onlineHostCandidates = Collections.unmodifiableMap(wildcard);
        }

        static RecoveryConfiguration available(boolean enabled, boolean autoOpenLinks,
                                               boolean nativeLinkCapture, boolean clipboardAutoOpen,
                                               boolean paymentSdkBridge, Set<String> frozenPackages,
                                               String[] onlineHostRules, int userId) {
            return new RecoveryConfiguration(true, enabled, autoOpenLinks, nativeLinkCapture,
                    clipboardAutoOpen, paymentSdkBridge, SystemServerConfigContract.STATUS_OK,
                    frozenPackages == null ? Collections.emptySet() : frozenPackages, onlineHostRules, userId);
        }

        static RecoveryConfiguration unavailable(String status) {
            return new RecoveryConfiguration(false, false, false, false, false, false, status,
                    Collections.emptySet(), new String[0], -1);
        }

        private RecoveryConfiguration(SystemRuntimeConfig.State state) {
            available = true;
            enabled = state.recoveryEnabled;
            autoOpenLinks = state.autoOpenLinks;
            nativeLinkCapture = state.nativeLinkCapture;
            clipboardAutoOpen = state.clipboardAutoOpen;
            paymentSdkBridge = state.paymentSdkBridge;
            status = "autonomous-persisted";
            userId = state.userId;
            frozenPackages = Collections.emptySet(); // Independent routing always reads live PM state.
            onlineHostRules = new String[0];
            scopedRules = state.scopedRules;
            onlineHostCandidates = Collections.emptyMap();
        }

        static RecoveryConfiguration autonomous(SystemRuntimeConfig.State state) {
            return new RecoveryConfiguration(state);
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
                                    int userId = clipboardUserId(method, args, capturedClipIndex);
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

    private static int clipboardUserId(Method method, List<Object> args, int clipIndex) {
        if (method == null || args == null) return UNKNOWN_USER_ID;
        // Prefer an explicit UserHandle on OEM implementations.
        for (Object arg : args) {
            if (arg instanceof UserHandle) {
                try {
                    Method identifier = UserHandle.class.getDeclaredMethod("getIdentifier");
                    identifier.setAccessible(true);
                    Object value = identifier.invoke(arg);
                    if (value instanceof Integer && isValidUserId((Integer) value)) return (Integer) value;
                } catch (Throwable ignored) { }
            }
        }

        Class<?>[] types = method.getParameterTypes();
        if (clipIndex < 0 || clipIndex >= types.length || args.size() != types.length) {
            return UNKNOWN_USER_ID;
        }
        // Android 16's setPrimaryClipAsPackage family places userId at the first int after
        // ClipData and the calling package/attribution strings. Later ints are deviceId/flags;
        // never scan from the tail because that silently maps those values to user 0/other users.
        if (method.getName().equals("setPrimaryClipAsPackage")
                || method.getName().equals("setPrimaryClip")) {
            int firstInt = -1;
            for (int i = clipIndex + 1; i < types.length; i++) {
                if (types[i] == Integer.TYPE || types[i] == Integer.class) {
                    firstInt = i;
                    break;
                }
            }
            if (firstInt >= 0 && firstInt < args.size() && args.get(firstInt) instanceof Integer) {
                int value = (Integer) args.get(firstInt);
                if (isValidUserId(value)) return value;
            }
        }
        return UNKNOWN_USER_ID;
    }

    private static boolean isValidUserId(int value) {
        return value >= 0 && value <= 999;
    }

    private void scheduleClipboardObservation(String raw, int userId) {
        Handler worker = systemHandler;
        if (worker == null || raw == null) return;
        if (userId == UNKNOWN_USER_ID) {
            log(4, TAG, "clipboard skipped: user unknown");
            return;
        }
        RecoveryConfiguration configuration = currentRecoveryConfiguration();
        if (!matchesSnapshotUser(configuration, userId) || !configuration.enabled
                || !configuration.autoOpenLinks || !configuration.clipboardAutoOpen) return;
        String key = BoundedExpiringDeduplicator.fingerprint(userId, raw);
        long now = SystemClock.elapsedRealtime();
        if (!clipboardEvents.claim(key, now)) {
            log(4, TAG, "clipboard event coalesced: user=" + userId);
            return;
        }
        worker.post(() -> observeClipboard(raw, userId));
    }

    /** Runs off ClipboardService's lock; it only dispatches an explicit event to the app process. */
    private void observeClipboard(String raw, int userId) {
        SystemRuntimeConfig.State autonomous = autonomousConfiguration(userId);
        if (autonomous != null) {
            if (!autonomous.recoveryEnabled || !autonomous.autoOpenLinks || !autonomous.clipboardAutoOpen) return;
            String candidate = IntentUrlExtractor.extractUrl(raw);
            if (candidate == null) candidate = raw;
            if (candidate == null || candidate.length() > 16_384) return;
            Uri uri = Uri.parse(candidate.trim());
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null) return;
            Intent payload = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
            enqueueAutonomous(new AutonomousBridgeCoordinator.Request<>(AutonomousBridgeCoordinator.Kind.WEB_RULE,
                    userId, "*", uri.getHost(), null,
                    "clipboard:" + BoundedExpiringDeduplicator.fingerprint(userId, candidate), payload,
                    false, true, autonomous.onlyFrozen));
            return;
        }
        RecoveryConfiguration configuration = currentRecoveryConfiguration();
        if (!matchesSnapshotUser(configuration, userId)) return;
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
        List<LinkRuleEntry> matches = ScopedRuleResolver.matchingRules(configuration.scopedRules, "*", host);
        List<String> candidates = ScopedRuleResolver.targetPackages(matches);
        LinkRuleEntry selected = ScopedRuleResolver.selectFrozenRule(matches, configuration.frozenPackages);
        String target = selected == null ? null : selected.targetPackage;
        if (target == null) {
            log(4, TAG, "clipboard skipped: no_rule host=" + host);
            return;
        }
        log(4, TAG, "clipboard link captured: scheme=" + scheme + ", host=" + host
                + ", candidates=" + candidates + ", target=" + target
                + ", frozenSnapshotContains="
                + (configuration.frozenPackages != null
                && configuration.frozenPackages.contains(target))
                + ", user=" + userId);
        launchClipboardBridge(uri, target, userId, selected.urlConversion);
    }
    /** system_server starts the same foreground bridge used for tapped links, avoiding Android 16 BAL. */
    private void launchClipboardBridge(Uri uri, String targetPackage, int userId, String conversion) {
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
            long tokenExpiresAt = SystemClock.elapsedRealtime() + REPLAY_TOKEN_WINDOW_MILLIS;
        pruneReplayTokens(SystemClock.elapsedRealtime());
        replayTokens.put(token, tokenExpiresAt);
            source.putExtra(EXTRA_NATIVE_REPLAY_TOKEN, token);
            Intent bridge = new Intent(Intent.ACTION_VIEW)
                    .setComponent(new ComponentName(PACKAGE_NAME,
                            "com.huanchengfly.icebridge.activities.BridgeActivity"))
                    .putExtra(EXTRA_AUTO_BRIDGED, true)
                    .putExtra(EXTRA_NATIVE_AUTO_BRIDGED, true)
                    .putExtra(EXTRA_NATIVE_DIRECT_INTENT, false)
                    .putExtra(EXTRA_NATIVE_PREWARM_ONLY, false)
                    .putExtra(EXTRA_NATIVE_BRIDGE_SOURCE, "clipboard")
                    .putExtra(EXTRA_USER_ID, userId)
                    .putExtra("rule_conversion", conversion)
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
        boolean chromeBound = CHROME_PACKAGE.equals(original.getPackage())
                || (original.getComponent() != null
                && CHROME_PACKAGE.equals(original.getComponent().getPackageName()));
        if (!chromeBound) {
            return original;
        }

        // Chrome receives normal HTTP(S) links before the generic activity-start resolver.  Do
        // not use the resolver's legacy no-snapshot overload here: HTTP(S) routing is intentionally
        // sourced only from the current online/manual rule snapshot.  Custom schemes keep their
        // stable built-in mappings (including Alipay) and are resolved by the generic resolver.
        RecoveryConfiguration configuration = currentRecoveryConfiguration();
        if (!configuration.available) {
            configuration = refreshRecoveryConfiguration("chrome-auto-open", true);
        }
        String target = null;
        String scheme = data.getScheme();
        boolean httpLink = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (httpLink) {
            List<String> candidates = CustomSchemeTargetResolver.findTargetPackagesForUri(
                    scheme, data.getHost(), configuration.onlineHostCandidates);
            target = NativeBridgePolicy.selectCandidate(candidates, configuration.frozenPackages);
            log(4, TAG, "Chrome HTTP(S) candidates: host=" + data.getHost()
                    + ", candidates=" + candidates + ", selected=" + target);
            // Chrome is only an observation point for ordinary web links. Do not rewrite a link
            // merely because a rule names a package: only a package present in the current frozen
            // snapshot needs the foreground thaw bridge. This also prevents stale/unknown state
            // from selecting the first shared-host candidate and causing a second launch.
            if (target == null || NativeBridgePolicy.shouldBypass(
                    false, configuration.frozenPackages, target)) {
                log(4, TAG, "Chrome HTTP(S) pass-through: target not frozen or state unknown"
                        + ", host=" + data.getHost() + ", target=" + target);
                return original;
            }
        } else {
            target = CustomSchemeTargetResolver.findTargetPackage(original);
        }
        if (target == null) {
            target = playStoreTargetPackage(original);
        }
        if (target == null || CHROME_PACKAGE.equals(target)) {
            return original;
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
