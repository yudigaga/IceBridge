package com.huanchengfly.icebridge.android16;

import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;

public final class ResolvedLaunch {
    public final String targetPackage;
    public final String targetActivity;

    private ResolvedLaunch(String targetPackage, String targetActivity) {
        this.targetPackage = targetPackage;
        this.targetActivity = targetActivity;
    }

    public static ResolvedLaunch from(Object resolveInfo, Object activityInfo) {
        ActivityInfo resolvedActivity = activityInfo instanceof ActivityInfo
                ? (ActivityInfo) activityInfo
                : resolveInfo instanceof ResolveInfo ? ((ResolveInfo) resolveInfo).activityInfo : null;
        if (resolvedActivity == null || resolvedActivity.packageName == null || resolvedActivity.name == null) {
            return null;
        }
        return new ResolvedLaunch(resolvedActivity.packageName, resolvedActivity.name);
    }
}
