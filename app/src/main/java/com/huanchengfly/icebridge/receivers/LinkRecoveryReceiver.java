package com.huanchengfly.icebridge.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import com.huanchengfly.icebridge.engines.BaseEngine;
import com.huanchengfly.icebridge.engines.EngineManager;

/**
 * Performs a requested thaw outside system_server. The receiver is protected in the manifest so
 * only the system can ask it to perform a recovery operation.
 */
public final class LinkRecoveryReceiver extends BroadcastReceiver {
    private static final String TAG = "IceBridgeRecovery";
    public static final String ACTION_RECOVER_LINK = "com.huanchengfly.icebridge.action.RECOVER_LINK";
    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_ORIGINAL_INTENT = "original_intent";
    public static final String EXTRA_AUTO_OPEN = "auto_open";
    public static final String EXTRA_PLAY_STORE = "play_store";

    @Override
    public void onReceive(Context context, Intent request) {
        if (request == null || !ACTION_RECOVER_LINK.equals(request.getAction())) {
            return;
        }
        String targetPackage = request.getStringExtra(EXTRA_TARGET_PACKAGE);
        Intent original = request.getParcelableExtra(EXTRA_ORIGINAL_INTENT);
        if (!isValidPackageName(targetPackage) || original == null) {
            Log.w(TAG, "recovery request rejected: invalid payload");
            return;
        }
        boolean autoOpen = request.getBooleanExtra(EXTRA_AUTO_OPEN, false);
        boolean playStoreFallback = request.getBooleanExtra(EXTRA_PLAY_STORE, false);
        PendingResult pendingResult = goAsync();
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
                        pendingResult.finish();
                    }
                }

                @Override
                public void onFailure() {
                    Log.w(TAG, "recovery failed: target=" + targetPackage);
                    pendingResult.finish();
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "recovery setup failed: " + error.getClass().getSimpleName());
            pendingResult.finish();
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
}
