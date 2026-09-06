package com.catchingnow.icebox.sdk_client;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;

import androidx.annotation.RequiresPermission;
import androidx.annotation.WorkerThread;

final class SdkImplement {
    private static final String INSTALLER_SERVICE = "com.catchingnow.icebox.appSdk.InstallerService";
    private static final Uri PERMISSION_URI = Uri.parse("content://com.catchingnow.icebox.SDK");
    private static final Uri STATE_URI = Uri.parse("content://com.catchingnow.icebox.STATE");

    private SdkImplement() {
    }

    static IceBoxDiagnostic inspect(Context context) {
        return IceBoxDiagnostic.inspect(context);
    }

    static Bundle authorizeExtras(Context context) {
        Bundle extras = new Bundle();
        extras.putParcelable("authorize", AuthorizeUtil.getAuthorizedPI(context));
        return extras;
    }

    static Uri getStateUri() {
        return STATE_URI;
    }

    static IceBox.WorkMode queryWorkMode(Context context) {
        IceBoxDiagnostic diagnostic = inspect(context);
        return diagnostic.getState() == IceBoxDiagnostic.State.WORKING
                || diagnostic.getState() == IceBoxDiagnostic.State.MODE_NOT_AVAILABLE
                ? diagnostic.getWorkMode()
                : IceBox.WorkMode.MODE_NOT_AVAILABLE;
    }

    @IceBox.AppState
    static int getAppEnabledSetting(Context context, String packageName) throws PackageManager.NameNotFoundException {
        return AppStateUtil.getAppEnabledSettings(context, packageName);
    }

    @IceBox.AppState
    static int getAppEnabledSetting(android.content.pm.ApplicationInfo applicationInfo) {
        return AppStateUtil.getAppEnabledSettings(applicationInfo);
    }

    @WorkerThread
    @RequiresPermission(IceBox.SDK_PERMISSION)
    static void setAppEnabledSettings(Context context, boolean enable, String... packageNames) {
        int userHandle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                ? Process.myUserHandle().hashCode()
                : 0;
        Bundle extras = new Bundle();
        extras.putParcelable("authorize", AuthorizeUtil.getAuthorizedPI(context));
        extras.putStringArray("package_names", packageNames);
        extras.putInt("user_handle", userHandle);
        extras.putBoolean("enable", enable);
        context.getContentResolver().call(PERMISSION_URI, "set_enable", null, extras);
    }

    static IceBox.SilentInstallSupport querySupportSilentInstall(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return IceBox.SilentInstallSupport.SYSTEM_NOT_SUPPORTED;
        }
        Bundle extras = new Bundle();
        extras.putParcelable("authorize", AuthorizeUtil.getAuthorizedPI(context));
        try {
            Bundle result = context.getContentResolver().call(STATE_URI, "query_silent_install_support", null, extras);
            if (result == null || result.isEmpty()) {
                return IceBox.SilentInstallSupport.UPDATE_REQUIRED;
            }
            return IceBox.SilentInstallSupport.valueOf(result.getString("state"));
        } catch (Exception ignored) {
            return IceBox.SilentInstallSupport.NOT_INSTALLED;
        }
    }

    @WorkerThread
    @RequiresPermission(IceBox.SDK_PERMISSION)
    static boolean installPackage(Context context, Uri apkUri) {
        return startInstallerService(context, Intent.ACTION_INSTALL_PACKAGE, apkUri, null);
    }

    @WorkerThread
    @RequiresPermission(IceBox.SDK_PERMISSION)
    static boolean uninstallPackage(Context context, String packageName) {
        return startInstallerService(context, Intent.ACTION_UNINSTALL_PACKAGE, null, packageName);
    }

    private static boolean startInstallerService(Context context, String action, Uri apkUri, String packageName) {
        try {
            Intent intent = new Intent(action)
                    .setPackage(IceBox.PACKAGE_NAME)
                    .setClassName(IceBox.PACKAGE_NAME, INSTALLER_SERVICE)
                    .putExtra("authorize", AuthorizeUtil.getAuthorizedPI(context));
            if (apkUri != null) {
                intent.setData(apkUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if (packageName != null) {
                intent.putExtra("uninstall_package_name", packageName);
            }
            context.startService(intent);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
