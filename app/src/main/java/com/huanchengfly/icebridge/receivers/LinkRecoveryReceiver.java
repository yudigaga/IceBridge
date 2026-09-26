package com.huanchengfly.icebridge.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.annotation.SuppressLint;

import com.huanchengfly.icebridge.engines.BaseEngine;
import com.huanchengfly.icebridge.engines.EngineManager;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Performs a requested thaw outside system_server. The receiver is protected in the manifest so
 * only the system can ask it to perform a recovery operation.
 */
public final class LinkRecoveryReceiver extends BroadcastReceiver {
    private static final String TAG = "IceBridgeRecovery";
    public static final String ACTION_RECOVER_LINK = "io.github.yudigaga.icebridge.action.RECOVER_LINK";
    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_ORIGINAL_INTENT = "original_intent";
    public static final String EXTRA_AUTO_OPEN = "auto_open";
    public static final String EXTRA_PLAY_STORE = "play_store";
    public static final String EXTRA_SOURCE_PACKAGE = "source_package";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_ORIGINAL_URL = "original_url";
    private static final String APP_PACKAGE = "io.github.yudigaga.icebridge";
    private static final String RECEIVER_CLASS =
            "com.huanchengfly.icebridge.receivers.LinkRecoveryReceiver";
    private static final long ASYNC_TIMEOUT_MILLIS = 10_000L;

    @Override
    public void onReceive(Context context, Intent request) {
        if (context == null || request == null || !ACTION_RECOVER_LINK.equals(request.getAction())
                || Binder.getCallingUid() != Process.SYSTEM_UID
                || !isOwnComponent(request)) {
            return;
        }
        String targetPackage = request.getStringExtra(EXTRA_TARGET_PACKAGE);
        String sourcePackage = request.getStringExtra(EXTRA_SOURCE_PACKAGE);
        int userId = request.getIntExtra(EXTRA_USER_ID, -1);
        String originalUrl = request.getStringExtra(EXTRA_ORIGINAL_URL);
        Intent original = request.getParcelableExtra(EXTRA_ORIGINAL_INTENT);
        if (!isValidPackageName(targetPackage) || !isValidPackageName(sourcePackage)
                || !isValidUserId(userId) || original == null
                || getSendingUserIdCompat() != userId
                || !isBoundIntent(original, sourcePackage, targetPackage, originalUrl)) {
            Log.w(TAG, "recovery request rejected: invalid payload");
            return;
        }
        boolean autoOpen = request.getBooleanExtra(EXTRA_AUTO_OPEN, false);
        boolean playStoreFallback = request.getBooleanExtra(EXTRA_PLAY_STORE, false);
        PendingResult pendingResult = goAsync();
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                pendingResult.finish();
            }
        };
        new Handler(Looper.getMainLooper()).postDelayed(finish, ASYNC_TIMEOUT_MILLIS);
        Context appContext = context.getApplicationContext();
        try {
            EngineManager.getEngine(appContext).setEnabled(targetPackage, true, new BaseEngine.Callback() {
                @Override
                public void onSuccess() {
                    try {
                        // Android 16 rejects startActivity() from this background receiver (BAL).
                        // system_server schedules the follow-up launch after this activation succeeds.
                        Log.i(TAG, "recovery completed: target=" + targetPackage
                                + ", autoOpen=" + autoOpen + ", launchOwner=system_server");
                    } finally {
                        finish.run();
                    }
                }

                @Override
                public void onFailure() {
                    Log.w(TAG, "recovery failed: target=" + targetPackage);
                    finish.run();
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "recovery setup failed: " + error.getClass().getSimpleName());
            finish.run();
        }
    }

    private static boolean isOwnComponent(Intent request) {
        return request.getComponent() != null
                && APP_PACKAGE.equals(request.getComponent().getPackageName())
                && RECEIVER_CLASS.equals(request.getComponent().getClassName());
    }

    private static boolean isBoundIntent(Intent original, String sourcePackage,
                                         String targetPackage, String originalUrl) {
        String packageName = original.getPackage();
        if (packageName != null && !sourcePackage.equals(packageName)
                && !targetPackage.equals(packageName)) {
            return false;
        }
        if (original.getComponent() != null) {
            String componentPackage = original.getComponent().getPackageName();
            if (!sourcePackage.equals(componentPackage) && !targetPackage.equals(componentPackage)) {
                return false;
            }
        }
        String actualUrl = original.getDataString();
        return originalUrl != null && originalUrl.equals(actualUrl);
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private int getSendingUserIdCompat() {
        try {
            Method method = BroadcastReceiver.class.getDeclaredMethod("getSendingUserId");
            method.setAccessible(true);
            Object value = method.invoke(this);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable error) {
            return -1;
        }
    }

    private void launchRecoveredTarget(Context context, Intent original, String targetPackage,
                                       boolean playStoreFallback) {
        try {
            Intent launch;
            if (playStoreFallback) {
                launch = context.getPackageManager().getLaunchIntentForPackage(targetPackage);
                if (launch == null) {
                    Log.w(TAG, "recovery launch skipped: no launcher activity target=" + targetPackage);
                    return;
                }
            } else {
                launch = new Intent(original);
                launch.setComponent(null);
                launch.setPackage(targetPackage);
                launch.addCategory(Intent.CATEGORY_BROWSABLE);
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(launch);
        } catch (Throwable error) {
            Log.w(TAG, "recovery launch failed: target=" + targetPackage
                    + ", reason=" + error.getClass().getSimpleName());
        }
    }

    private static boolean isValidPackageName(String packageName) {
        return packageName != null && packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }

    private static boolean isValidUserId(int userId) {
        return userId >= 0 && userId <= 999;
    }
}
