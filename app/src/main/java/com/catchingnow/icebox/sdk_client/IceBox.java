package com.catchingnow.icebox.sdk_client;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.annotation.IntDef;
import androidx.annotation.RequiresPermission;
import androidx.annotation.WorkerThread;

public class IceBox {
    public static final String SDK_PERMISSION = "com.catchingnow.icebox.SDK";
    public static final String PACKAGE_NAME = "com.catchingnow.icebox";
    public static final int AVAILABLE_VERSION_CODE = 703;

    public enum WorkMode {
        MODE_PM_DISABLE_USER,
        MODE_PM_HIDE,
        MODE_NOT_AVAILABLE
    }

    public static final int FLAG_PM_DISABLE_USER = 1;
    public static final int FLAG_PM_HIDE = 2;

    @IntDef(flag = true, value = {FLAG_PM_HIDE, FLAG_PM_DISABLE_USER})
    public @interface AppState {
    }

    public static WorkMode queryWorkMode(Context context) {
        return SdkImplement.queryWorkMode(context);
    }

    public static IceBoxDiagnostic inspect(Context context) {
        return SdkImplement.inspect(context);
    }

    @AppState
    public static int getAppEnabledSetting(Context context, String packageName) throws PackageManager.NameNotFoundException {
        return SdkImplement.getAppEnabledSetting(context, packageName);
    }

    @AppState
    public static int getAppEnabledSetting(ApplicationInfo applicationInfo) {
        return SdkImplement.getAppEnabledSetting(applicationInfo);
    }

    @WorkerThread
    @RequiresPermission(SDK_PERMISSION)
    public static void setAppEnabledSettings(Context context, boolean enable, String... packageNames) {
        SdkImplement.setAppEnabledSettings(context, enable, packageNames);
    }

    public enum SilentInstallSupport {
        SUPPORTED,
        NOT_INSTALLED,
        UPDATE_REQUIRED,
        SYSTEM_NOT_SUPPORTED,
        NOT_DEVICE_OWNER,
        PERMISSION_REQUIRED
    }

    public static SilentInstallSupport querySupportSilentInstall(Context context) {
        return SdkImplement.querySupportSilentInstall(context);
    }

    @WorkerThread
    @RequiresPermission(SDK_PERMISSION)
    public static boolean installPackage(Context context, Uri apkUri) {
        return SdkImplement.installPackage(context, apkUri);
    }

    @WorkerThread
    @RequiresPermission(SDK_PERMISSION)
    public static boolean uninstallPackage(Context context, String packageName) {
        return SdkImplement.uninstallPackage(context, packageName);
    }
}
