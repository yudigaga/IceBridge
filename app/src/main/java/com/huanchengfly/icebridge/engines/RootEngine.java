package com.huanchengfly.icebridge.engines;

import android.content.Context;
import android.content.pm.PackageManager;

import com.huanchengfly.icebridge.R;
import com.huanchengfly.icebridge.SystemServerConfigContract;
import com.huanchengfly.icebridge.utils.Util;

public final class RootEngine extends BaseEngine {
    public static final int STATUS_NO_ROOT = 0;
    public static final int STATUS_NOT_GIVEN_ROOT = 1;
    public static final int STATUS_WORKING = 2;

    public RootEngine(Context context) {
        super(context);
    }

    @Override
    public boolean isAppEnabled(android.content.pm.ApplicationInfo applicationInfo) {
        return applicationInfo != null && applicationInfo.enabled;
    }

    @Override
    public void setEnabled(String packageName, boolean enabled, Callback callback) {
        if (!isValidPackageName(packageName)) {
            callback.onFailure();
            return;
        }
        RootCommandRunner.setPackageEnabled(packageName, enabled, SystemServerConfigContract.currentUserId(), result -> {
            if (result.isSuccessful()) {
                SystemServerConfigContract.notifyChanged(getContext());
                callback.onSuccess();
            } else {
                callback.onFailure();
            }
        });
    }

    private boolean isValidPackageName(String packageName) {
        return packageName != null && packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }

    @Override
    public int getEngineStatus() {
        RootCommandRunner.Status rootStatus = RootCommandRunner.getStatus();
        if (rootStatus == RootCommandRunner.Status.WORKING) {
            return BaseEngine.STATUS_WORKING;
        }
        if (rootStatus == RootCommandRunner.Status.NEED_PERMISSION) {
            return BaseEngine.STATUS_NEED_PERMISSION;
        }
        return BaseEngine.STATUS_NOT_AVAILABLE;
    }

    @Override
    public CharSequence getStatusDescription() {
        switch (getEngineStatus()) {
            case STATUS_NOT_AVAILABLE:
                return getContext().getString(R.string.status_description_no_root);
            case STATUS_NEED_PERMISSION:
                return getContext().getString(R.string.status_description_not_given_root);
            case STATUS_WORKING:
                if (Util.isEnhanceModeActive(getContext())) {
                    return getContext().getString(R.string.status_description_root_working_enhance);
                }
                return getContext().getString(R.string.status_description_root_working);
            default:
                return null;
        }
    }

    @Override
    public int checkPermission() {
        return RootCommandRunner.getStatus() == RootCommandRunner.Status.WORKING
                ? PackageManager.PERMISSION_GRANTED
                : PackageManager.PERMISSION_DENIED;
    }

    public void refreshStatus(Runnable onComplete) {
        RootCommandRunner.refreshStatus(result -> {
            notifyStatusChanged();
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    @Override
    public void requestPermission(Callback callback) {
        RootCommandRunner.refreshStatus(result -> {
            if (result.isSuccessful() && result.isRoot()) {
                callback.onSuccess();
                notifyStatusChanged();
            } else {
                callback.onFailure();
            }
        });
    }
}
