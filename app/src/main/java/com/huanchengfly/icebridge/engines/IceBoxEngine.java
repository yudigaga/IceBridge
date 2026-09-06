package com.huanchengfly.icebridge.engines;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.catchingnow.icebox.sdk_client.IceBox;
import com.catchingnow.icebox.sdk_client.IceBoxDiagnostic;
import com.huanchengfly.icebridge.R;
import com.huanchengfly.icebridge.utils.Util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class IceBoxEngine extends BaseEngine {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private volatile IceBoxDiagnostic diagnostic;
    private volatile boolean diagnosticFailed;

    public IceBoxEngine(Context context) {
        super(context);
    }

    @Override
    public boolean isAppEnabled(android.content.pm.ApplicationInfo applicationInfo) {
        return IceBox.getAppEnabledSetting(applicationInfo) == 0;
    }

    @Override
    public void setEnabled(String packageName, boolean enabled, Callback callback) {
        if (packageName == null || packageName.isEmpty()) {
            callback.onFailure();
            return;
        }
        Context context = getContext().getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                IceBox.setAppEnabledSettings(context, enabled, packageName);
                MAIN_HANDLER.post(callback::onSuccess);
            } catch (RuntimeException error) {
                MAIN_HANDLER.post(callback::onFailure);
            }
        });
    }

    public void refreshDiagnostic(Runnable onComplete) {
        Context engineContext = getContext();
        if (engineContext == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        Context context = engineContext.getApplicationContext();
        EXECUTOR.execute(() -> {
            inspectSafely(context);
            MAIN_HANDLER.post(() -> {
                notifyStatusChanged();
                if (onComplete != null) {
                    onComplete.run();
                }
            });
        });
    }

    public CharSequence getDiagnosticDescription() {
        IceBoxDiagnostic current = getDiagnostic();
        if (current == null) {
            return getContext().getString(R.string.status_icebox_query_error);
        }
        return current.getDetail() + "\nIceBox versionCode: " + current.getVersionCode();
    }

    @Override
    public int getEngineStatus() {
        IceBoxDiagnostic current = getDiagnostic();
        if (current == null || current.getState() != IceBoxDiagnostic.State.WORKING) {
            return STATUS_NOT_AVAILABLE;
        }
        return ContextCompat.checkSelfPermission(getContext(), IceBox.SDK_PERMISSION) == PackageManager.PERMISSION_GRANTED
                ? STATUS_WORKING
                : STATUS_NEED_PERMISSION;
    }

    @Override
    public CharSequence getStatusDescription() {
        IceBoxDiagnostic current = getDiagnostic();
        if (current == null) {
            return getContext().getString(R.string.status_icebox_query_error);
        }
        switch (current.getState()) {
            case NOT_INSTALLED:
                return getContext().getString(R.string.status_icebox_not_installed);
            case TOO_OLD:
                return getContext().getString(R.string.status_icebox_old);
            case MODE_NOT_AVAILABLE:
                return getContext().getString(R.string.status_icebox_not_available);
            case PROVIDER_UNAVAILABLE:
                return getContext().getString(R.string.status_icebox_provider_unavailable);
            case PROVIDER_LAUNCH_BLOCKED:
                return getContext().getString(R.string.status_icebox_provider_launch_blocked);
            case SECURITY_ERROR:
                return getContext().getString(R.string.status_icebox_security_error);
            case INVALID_RESPONSE:
                return getContext().getString(R.string.status_icebox_invalid_response);
            case QUERY_ERROR:
                return getContext().getString(R.string.status_icebox_query_error);
            case WORKING:
                if (ContextCompat.checkSelfPermission(getContext(), IceBox.SDK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                    return getContext().getString(R.string.status_need_permission);
                }
                return Util.isEnhanceModeActive(getContext())
                        ? getContext().getString(R.string.status_description_icebox_mode_enhance)
                        : getContext().getString(R.string.status_description_icebox_mode);
            default:
                return getContext().getString(R.string.status_icebox_query_error);
        }
    }

    @Override
    public int checkPermission() {
        return ContextCompat.checkSelfPermission(getContext(), IceBox.SDK_PERMISSION);
    }

    @Override
    public void requestPermission(Callback callback) {
        Util.requestPermission(getContext(), granted -> {
            callback.onSuccess();
            notifyStatusChanged();
        }, denied -> callback.onFailure());
    }

    private IceBoxDiagnostic getDiagnostic() {
        IceBoxDiagnostic current = diagnostic;
        if (current == null && !diagnosticFailed) {
            Context context = getContext();
            if (context != null) {
                inspectSafely(context.getApplicationContext());
                current = diagnostic;
            }
        }
        return current;
    }

    private void inspectSafely(Context context) {
        try {
            diagnostic = IceBox.inspect(context);
            diagnosticFailed = diagnostic == null;
        } catch (Throwable ignored) {
            // Ice Box's provider may fail on a ROM/version combination after permission is
            // granted. Keep the app usable and expose a normal "query failed" status instead.
            diagnostic = null;
            diagnosticFailed = true;
        }
    }
}
