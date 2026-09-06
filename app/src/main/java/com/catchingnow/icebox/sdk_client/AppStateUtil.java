package com.catchingnow.icebox.sdk_client;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;

final class AppStateUtil {
    private static final int PM_FLAGS_GET_APP_INFO = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            ? PackageManager.MATCH_UNINSTALLED_PACKAGES
            : PackageManager.GET_UNINSTALLED_PACKAGES;
    private static final int PRIVATE_FLAG_HIDDEN = 1;
    private static final int FLAG_HIDDEN = 1 << 27;

    @Nullable
    private static final Field PRIVATE_FLAGS_FIELD = findPrivateFlagsField();

    private AppStateUtil() {
    }

    @Nullable
    private static Field findPrivateFlagsField() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return null;
            }
            Field field = ApplicationInfo.class.getDeclaredField("privateFlags");
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isAppHidden(ApplicationInfo applicationInfo) {
        if (PRIVATE_FLAGS_FIELD != null) {
            try {
                int privateFlags = (int) PRIVATE_FLAGS_FIELD.get(applicationInfo);
                return (privateFlags & PRIVATE_FLAG_HIDDEN) != 0;
            } catch (Throwable ignored) {
            }
        }
        return (applicationInfo.flags & FLAG_HIDDEN) != 0;
    }

    @IceBox.AppState
    static int getAppEnabledSettings(Context context, String packageName) throws PackageManager.NameNotFoundException {
        return getAppEnabledSettings(context.getPackageManager().getApplicationInfo(packageName, PM_FLAGS_GET_APP_INFO));
    }

    @IceBox.AppState
    static int getAppEnabledSettings(ApplicationInfo applicationInfo) {
        int flags = 0;
        if (isAppHidden(applicationInfo)) {
            flags |= IceBox.FLAG_PM_HIDE;
        }
        if (!applicationInfo.enabled) {
            flags |= IceBox.FLAG_PM_DISABLE_USER;
        }
        return flags;
    }
}
