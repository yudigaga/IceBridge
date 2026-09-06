package com.huanchengfly.icebridge.activities;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.huanchengfly.about.utils.DisplayUtil;
import com.huanchengfly.icebridge.R;
import com.huanchengfly.icebridge.adapters.GridChooseResolveInfoAdapter;
import com.huanchengfly.icebridge.dividers.SpacesItemDecoration;
import com.huanchengfly.icebridge.automation.LinkAutomationConfig;
import com.huanchengfly.icebridge.automation.LinkAutomationDiagnostics;
import com.huanchengfly.icebridge.automation.FluidCloudRuleCatalog;
import com.huanchengfly.icebridge.engines.BaseEngine;
import com.huanchengfly.icebridge.engines.EngineManager;
import com.huanchengfly.icebridge.utils.BridgeUtil;
import com.huanchengfly.icebridge.utils.RecentTaskVisibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BridgeActivity extends BaseActivity {
    public static final String TAG = "Bridge";
    private static final String EXTRA_AUTO_BRIDGED = "auto_bridged";
    private static final String EXTRA_TARGET_PACKAGE = "target_package";
    private static final String EXTRA_ORIGINAL_INTENT = "original_intent";
    private static final String EXTRA_PLAY_STORE = "play_store";
    private static final String EXTRA_NATIVE_AUTO_BRIDGED = "native_auto_bridged";
    private static final String EXTRA_NATIVE_LINK_URL = "native_link_url";
    private static final String EXTRA_NATIVE_DIRECT_INTENT = "native_direct_intent";
    private static final String EXTRA_NATIVE_PREWARM_ONLY = "native_prewarm_only";
    private static final String EXTRA_NATIVE_BRIDGE_SOURCE = "native_bridge_source";

    private BaseEngine mEngine;

    private void start() {
        mEngine = EngineManager.getEngine(this);
        if (mEngine.checkPermission() != PackageManager.PERMISSION_GRANTED) {
            mEngine.requestPermission(new BaseEngine.Callback() {
                @Override
                public void onSuccess() {
                    bridge();
                }

                @Override
                public void onFailure() {
                    Toast.makeText(BridgeActivity.this, R.string.request_permission_failure, Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
        } else bridge();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BridgeUtil.init(this);
        RecentTaskVisibility.apply(this, LinkAutomationConfig.isBridgeHiddenFromRecents(this));
        if (getIntent().getBooleanExtra(EXTRA_AUTO_BRIDGED, false)) {
            if (getIntent().getBooleanExtra(EXTRA_NATIVE_AUTO_BRIDGED, false)) {
                nativeAutoBridge();
            } else {
                autoBridge();
            }
        } else {
            start();
        }
    }

    /**
     * Executes from a foreground Activity that replaced the user-initiated WeChat/QQ WebView
     * start. This is intentional: Android 16 can accept a receiver's startActivity() call but
     * suppress the foreground transition under BAL, whereas this trampoline is foreground.
     */
    private void nativeAutoBridge() {
        String target = getIntent().getStringExtra(EXTRA_TARGET_PACKAGE);
        String rawUrl = getIntent().getStringExtra(EXTRA_NATIVE_LINK_URL);
        Intent sourceIntent = getIntent().getParcelableExtra(EXTRA_ORIGINAL_INTENT);
        boolean directIntent = getIntent().getBooleanExtra(EXTRA_NATIVE_DIRECT_INTENT, false);
        boolean prewarmOnly = getIntent().getBooleanExtra(EXTRA_NATIVE_PREWARM_ONLY, false);
        String bridgeSource = getIntent().getStringExtra(EXTRA_NATIVE_BRIDGE_SOURCE);
        mEngine = EngineManager.getEngine(this);
        if (target == null || rawUrl == null || sourceIntent == null) {
            LinkAutomationDiagnostics.record(this, "native_bridge_fail", rawUrl, "INVALID_PAYLOAD");
            finishBridgeTask();
            return;
        }
        LinkAutomationDiagnostics.record(this, "native_bridge", rawUrl, "target=" + target
                + " source=" + (bridgeSource == null ? "unknown" : bridgeSource)
                + " prewarm=" + prewarmOnly);
        updateNativeBridgeIcons(sourceIntent, target);
        if (isTargetEnabled(target)) {
            // The target may have been thawed by a previous click. Do not replay the
            // WebView/payment source in that case: launch the requested destination again.
            if (prewarmOnly) {
                LinkAutomationDiagnostics.record(this, "payment_sdk_replay", rawUrl,
                        "target=" + target + " source="
                                + (bridgeSource == null ? "unknown" : bridgeSource)
                                + " already_enabled=true");
                replayPaymentSdkIntent(sourceIntent);
            } else {
                launchNativeTarget(target, rawUrl, sourceIntent, directIntent);
            }
            return;
        }
        Runnable thawAndLaunch = () -> mEngine.setEnabled(target, true, new BaseEngine.Callback() {
            @Override public void onSuccess() {
                LinkAutomationDiagnostics.record(BridgeActivity.this, "native_thaw_ok", rawUrl,
                        "target=" + target);
                if (prewarmOnly) {
                    LinkAutomationDiagnostics.record(BridgeActivity.this, "payment_sdk_replay", rawUrl,
                            "target=" + target + " source="
                                    + (bridgeSource == null ? "unknown" : bridgeSource));
                    replayPaymentSdkIntent(sourceIntent);
                } else {
                    launchNativeTarget(target, rawUrl, sourceIntent, directIntent);
                }
            }

            @Override public void onFailure() {
                LinkAutomationDiagnostics.record(BridgeActivity.this, "native_bridge_fail", rawUrl,
                        "THAW_FAILED target=" + target);
                replayPaymentSdkIntent(sourceIntent);
            }
        });
        if (mEngine.checkPermission() == PackageManager.PERMISSION_GRANTED) {
            thawAndLaunch.run();
        } else {
            mEngine.requestPermission(new BaseEngine.Callback() {
                @Override public void onSuccess() { thawAndLaunch.run(); }
                @Override public void onFailure() {
                    LinkAutomationDiagnostics.record(BridgeActivity.this, "native_bridge_fail", rawUrl,
                            "PERMISSION_DENIED target=" + target);
                    replayPaymentSdkIntent(sourceIntent);
                }
            });
        }
    }


    private void updateNativeBridgeIcons(Intent sourceIntent, String targetPackage) {
        ImageView sourceIcon = findViewById(R.id.bridge_source_icon);
        ImageView targetIcon = findViewById(R.id.bridge_target_icon);
        if (sourceIcon != null) {
            ComponentName source = sourceIntent == null ? null : sourceIntent.getComponent();
            sourceIcon.setImageDrawable(loadApplicationIcon(source == null ? null : source.getPackageName()));
        }
        if (targetIcon != null) {
            targetIcon.setImageDrawable(loadApplicationIcon(targetPackage));
        }
    }

    @SuppressWarnings("deprecation")
    private Drawable loadApplicationIcon(String packageName) {
        try {
            if (packageName != null && !packageName.isEmpty()) {
                int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        ? PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_UNINSTALLED_PACKAGES
                        : PackageManager.GET_DISABLED_COMPONENTS | PackageManager.GET_UNINSTALLED_PACKAGES;
                ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, flags);
                if (info != null) {
                    return info.loadIcon(getPackageManager());
                }
            }
        } catch (Throwable ignored) {
            // A hidden IceBox package may not expose ApplicationInfo until after it is restored.
        }
        return getResources().getDrawable(android.R.drawable.sym_def_app_icon);
    }
    private boolean isTargetEnabled(String target) {
        try {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS
                    : PackageManager.GET_UNINSTALLED_PACKAGES | PackageManager.GET_DISABLED_COMPONENTS;
            ApplicationInfo info = getPackageManager().getApplicationInfo(target, flags);
            return info != null && mEngine.isAppEnabled(info);
        } catch (Throwable ignored) {
            // Hidden IceBox packages are deliberately treated as frozen and are restored below.
            return false;
        }
    }

    private void launchNativeTarget(String target, String rawUrl, Intent sourceIntent,
                                    boolean directIntent) {
        try {
            Intent launch;
            if (directIntent) {
                // Android 16 rejects an Intent copied from a nested Intent extra when it has no
                // creator token (IntentRedirect Hardening). Build a new launch request instead
                // of calling new Intent(sourceIntent), and retain only primitive transport extras.
                launch = buildSanitizedIntent(sourceIntent, rawUrl);
                launch.setComponent(null);
                launch.setSelector(null);
                launch.setPackage(target);
                if (launch.getAction() == null) {
                    launch.setAction(Intent.ACTION_VIEW);
                }
                // Normalize intent:// wrappers to the actual alipays:// URI. The original SDK
                // component is deliberately not replayed after changing the target package.
                if (rawUrl != null) {
                    launch.setData(Uri.parse(rawUrl));
                }
            } else {
                Uri originalUri = Uri.parse(rawUrl);
                Uri handoffUri = FluidCloudRuleCatalog.rewriteForTarget(originalUri, target);
                launch = new Intent(Intent.ACTION_VIEW, handoffUri);
                launch.setPackage(target);
                launch.addCategory(Intent.CATEGORY_BROWSABLE);
                if (!originalUri.equals(handoffUri)) {
                    LinkAutomationDiagnostics.record(this, "native_rule_rewrite", rawUrl,
                            "target=" + target + " source=fluid_catalog");
                }
            }
            // Launch the target as a one-shot document task so the initiating app's task stays
            // underneath it. Back therefore returns to the source app instead of an existing
            // target launcher task. The target handoff is excluded from Recents.
            launch.setFlags(launch.getFlags() & ~(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            if (!directIntent) {
                ResolveInfo resolved = getPackageManager().resolveActivity(launch, PackageManager.MATCH_DEFAULT_ONLY);
                if (resolved == null) {
                    LinkAutomationDiagnostics.record(this, "native_handoff_unresolved", rawUrl,
                            "target=" + target + " uri=" + launch.getData());
                    replaySourceIntent(sourceIntent);
                    return;
                }
                ComponentName component = resolved.activityInfo == null ? null
                        : new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name);
                LinkAutomationDiagnostics.record(this, "native_resolve_ok", rawUrl,
                        "target=" + target + " component=" + component);
            }
            startActivity(launch);
            LinkAutomationDiagnostics.record(this, "native_open_ok", rawUrl,
                    "target=" + target + " direct=" + directIntent);
            finishBridgeTask();
        } catch (Throwable error) {
            String reason = error.getClass().getSimpleName();
            String message = error.getMessage();
            if (message != null && !message.isEmpty()) {
                // Keep diagnostics useful without exporting the payment URL or extras.
                reason += " " + message.replaceAll("https?://\\S+", "[url]");
            }
            LinkAutomationDiagnostics.record(this, "native_open_fail", rawUrl,
                    reason + " target=" + target);
            // If the app itself rejects this URI, preserve the original WebView experience instead
            // of stranding the user on an empty bridge screen.
            replaySourceIntent(sourceIntent);
        }
    }

    private void replaySourceIntent(Intent sourceIntent) {
        try {
            // The source is delivered by system_server as a nested Parcelable Intent. Do not
            // replay a direct copy on Android 16: the platform correctly treats an untrusted
            // copied Intent without a creator token as an unsafe redirect.
            Intent replay = buildSanitizedIntent(sourceIntent, null);
            replay.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(replay);
        } catch (Throwable ignored) {
            // Nothing else can safely be launched here.
        } finally {
            finishBridgeTask();
        }
    }

    /**
     * Replays a payment SDK Activity without converting it into a VIEW intent.  Carrier payment
     * flows commonly keep the signed order, nonce and callback binder in Parcelable/Serializable
     * extras; the old primitive-only sanitizer silently discarded those values and left the
     * wallet merely thawed.  This path is only used for an Activity identified by
     * PaymentSdkBridgeResolver, so retaining its explicit component is both necessary and safer
     * than trying to manufacture an Alipay URL.
     */
    private void replayPaymentSdkIntent(Intent sourceIntent) {
        if (sourceIntent == null) {
            finishBridgeTask();
            return;
        }
        try {
            Intent replay = new Intent();
            replay.setAction(sourceIntent.getAction());
            replay.setData(sourceIntent.getData());
            replay.setType(sourceIntent.getType());
            replay.setIdentifier(sourceIntent.getIdentifier());
            replay.setComponent(sourceIntent.getComponent());
            replay.setPackage(sourceIntent.getPackage());
            if (sourceIntent.getCategories() != null) {
                for (String category : sourceIntent.getCategories()) replay.addCategory(category);
            }
            Bundle extras = sourceIntent.getExtras();
            if (extras != null) {
                // putExtras retains Parcelable, Serializable, nested Bundle and binder-backed
                // values.  Do not log or inspect their contents.
                replay.putExtras(new Bundle(extras));
            }
            // A nested selector can redirect to an unrelated component on Android 16. The
            // payment resolver already supplied the original explicit SDK component.
            replay.setSelector(null);
            // Keep the SDK request in BridgeActivity's foreground task. Forcing NEW_TASK creates
            // a wallet-only task and can sever the merchant SDK's H5/result handoff even though
            // Alipay itself starts successfully. BridgeActivity is already foreground here.
            replay.setFlags(sourceIntent.getFlags() & ~(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            LinkAutomationDiagnostics.record(this, "payment_sdk_replay_start", null,
                    "component=" + (replay.getComponent() == null ? "none"
                            : replay.getComponent().flattenToShortString())
                            + " action=" + (replay.getAction() == null ? "none" : "present")
                            + " data=" + (replay.getData() == null ? "none"
                                    : String.valueOf(replay.getData().getScheme()))
                            + " sourceFlags=0x" + Integer.toHexString(sourceIntent.getFlags())
                            + " replayFlags=0x" + Integer.toHexString(replay.getFlags())
                            + " extras=" + (extras == null ? 0 : extras.keySet().size()));
            startActivity(replay);
            LinkAutomationDiagnostics.record(this, "payment_sdk_replay_dispatched", null,
                    "component=" + (replay.getComponent() == null ? "none"
                            : replay.getComponent().flattenToShortString()));
            finishBridgeTask();
        } catch (Throwable error) {
            LinkAutomationDiagnostics.record(this, "payment_sdk_replay_fail", null,
                    error.getClass().getSimpleName());
            // Keep the original web/payment Activity usable if a vendor rejects the complete
            // bundle.  The sanitized fallback still preserves its explicit component.
            replaySourceIntent(sourceIntent);
        }
    }

    /**
    /**
     * Creates an Intent that has no nested-Intent creator metadata. Primitive extras are kept for
     * common deep-link/payment fields; Parcelables, Serializables and nested Intents are excluded
     * so a third-party caller cannot turn this fallback into an Intent redirection primitive.
     */
    private Intent buildSanitizedIntent(Intent source, @Nullable String replacementData) {
        Intent sanitized = new Intent();
        if (source == null) {
            sanitized.setAction(Intent.ACTION_VIEW);
            if (replacementData != null) sanitized.setData(Uri.parse(replacementData));
            return sanitized;
        }
        sanitized.setAction(source.getAction());
        sanitized.setType(source.getType());
        sanitized.setData(replacementData == null ? source.getData() : Uri.parse(replacementData));
        sanitized.setIdentifier(source.getIdentifier());
        sanitized.setComponent(source.getComponent());
        sanitized.setPackage(source.getPackage());
        Set<String> categories = source.getCategories();
        if (categories != null) {
            for (String category : categories) sanitized.addCategory(category);
        }
        copySafeExtras(source.getExtras(), sanitized);
        return sanitized;
    }

    private void copySafeExtras(@Nullable Bundle extras, Intent destination) {
        if (extras == null || destination == null) return;
        try {
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (value instanceof String) destination.putExtra(key, (String) value);
                else if (value instanceof CharSequence) destination.putExtra(key, (CharSequence) value);
                else if (value instanceof Boolean) destination.putExtra(key, (Boolean) value);
                else if (value instanceof Byte) destination.putExtra(key, (Byte) value);
                else if (value instanceof Character) destination.putExtra(key, (Character) value);
                else if (value instanceof Double) destination.putExtra(key, (Double) value);
                else if (value instanceof Float) destination.putExtra(key, (Float) value);
                else if (value instanceof Integer) destination.putExtra(key, (Integer) value);
                else if (value instanceof Long) destination.putExtra(key, (Long) value);
                else if (value instanceof Short) destination.putExtra(key, (Short) value);
                else if (value instanceof boolean[]) destination.putExtra(key, (boolean[]) value);
                else if (value instanceof byte[]) destination.putExtra(key, (byte[]) value);
                else if (value instanceof char[]) destination.putExtra(key, (char[]) value);
                else if (value instanceof double[]) destination.putExtra(key, (double[]) value);
                else if (value instanceof float[]) destination.putExtra(key, (float[]) value);
                else if (value instanceof int[]) destination.putExtra(key, (int[]) value);
                else if (value instanceof long[]) destination.putExtra(key, (long[]) value);
                else if (value instanceof short[]) destination.putExtra(key, (short[]) value);
                else if (value instanceof String[]) destination.putExtra(key, (String[]) value);
                else if (value instanceof CharSequence[]) destination.putExtra(key, (CharSequence[]) value);
            }
        } catch (Throwable ignored) {
            // A malformed parcelable extra must not prevent the safe portion of the fallback.
        }
    }

    private void finishBridgeTask() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }
    private void autoBridge() {
        String target = getIntent().getStringExtra(EXTRA_TARGET_PACKAGE);
        Intent original = getIntent().getParcelableExtra(EXTRA_ORIGINAL_INTENT);
        mEngine = EngineManager.getEngine(this);
        if (target == null || original == null) {
            finish();
            return;
        }
        Runnable recover = () -> mEngine.setEnabled(target, true, new BaseEngine.Callback() {
            @Override public void onSuccess() {
                Intent launch = original;
                if (getIntent().getBooleanExtra(EXTRA_PLAY_STORE, false)) {
                    Intent launcher = getPackageManager().getLaunchIntentForPackage(target);
                    if (launcher != null) launch = launcher;
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(launch);
                finish();
            }
            @Override public void onFailure() { finish(); }
        });
        if (mEngine.checkPermission() == PackageManager.PERMISSION_GRANTED) {
            recover.run();
        } else {
            mEngine.requestPermission(new BaseEngine.Callback() {
                @Override public void onSuccess() { recover.run(); }
                @Override public void onFailure() { finish(); }
            });
        }
    }

    private int getSpanCount(int itemCount) {
        if (itemCount < 2) {
            return 1;
        } else if (itemCount == 2) {
            return 2;
        } else {
            return 3;
        }
    }

    @SuppressLint("WrongConstant")
    private void bridge() {
        Intent intent = getIntent();
        if (intent.getScheme() == null) {
            finish();
            return;
        }
        intent.setComponent(null);
        List<ResolveInfo> resolveInfoList;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resolveInfoList = getPackageManager().queryIntentActivities(intent, PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);
        } else {
            resolveInfoList = getPackageManager().queryIntentActivities(intent, PackageManager.GET_ACTIVITIES | PackageManager.GET_DISABLED_COMPONENTS);
        }
        List<ResolveInfo> resolveInfos = new ArrayList<>();
        List<ResolveInfo> enabledResolveInfos = new ArrayList<>();
        for (ResolveInfo resolveInfo : resolveInfoList) {
            if (resolveInfo.activityInfo == null) {
                continue;
            }
            if (resolveInfo.activityInfo.packageName.equalsIgnoreCase(getPackageName())) {
                continue;
            }
            if (mEngine.isAppEnabled(resolveInfo.activityInfo.applicationInfo)) {
                enabledResolveInfos.add(resolveInfo);
            } else {
                resolveInfos.add(resolveInfo);
            }
        }
        boolean showEnableApp = getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("show_enable_app", true);
        if (showEnableApp) resolveInfos.addAll(enabledResolveInfos);
        if (resolveInfos.size() == 0) {
            Toast.makeText(this, R.string.no_apps_for_bridge, Toast.LENGTH_SHORT).show();
            finish();
        } else if (resolveInfos.size() == 1) {
            start(intent, resolveInfos.get(0));
        } else {
            RecyclerView recyclerView = new RecyclerView(this);
            recyclerView.addItemDecoration(new SpacesItemDecoration(DisplayUtil.dp2px(this, 8)));
            recyclerView.getItemAnimator().setAddDuration(0);
            recyclerView.getItemAnimator().setChangeDuration(0);
            recyclerView.getItemAnimator().setMoveDuration(0);
            recyclerView.getItemAnimator().setRemoveDuration(0);
            ((SimpleItemAnimator) recyclerView.getItemAnimator()).setSupportsChangeAnimations(false);
            recyclerView.setLayoutManager(new GridLayoutManager(this, getSpanCount(resolveInfos.size())));
            GridChooseResolveInfoAdapter appAdapter = new GridChooseResolveInfoAdapter(this, resolveInfos);
            recyclerView.setAdapter(appAdapter);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.title_select_apps)
                    .setView(recyclerView)
                    .setCancelable(false)
                    .setPositiveButton(R.string.button_sure, (dialog, which) -> {
                        start(intent, resolveInfos.get(appAdapter.getSelectedPosition()));
                    })
                    .show();
        }
    }

    private void start(Intent intent, ResolveInfo resolveInfo) {
        mEngine.setEnabled(resolveInfo.activityInfo.packageName, true, new BaseEngine.Callback() {
            @Override
            public void onSuccess() {
                intent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name));
                startActivity(intent);
                finish();
            }

            @Override
            public void onFailure() {
                finish();
            }
        });
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_bridge;
    }
}



