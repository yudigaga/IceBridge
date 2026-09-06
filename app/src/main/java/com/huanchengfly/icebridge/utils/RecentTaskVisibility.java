package com.huanchengfly.icebridge.utils;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/** Applies the recent-task visibility preference to the task currently hosting IceBridge. */
public final class RecentTaskVisibility {
    private RecentTaskVisibility() {
    }

    public static void apply(Activity activity, boolean excludeFromRecents) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        try {
            ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return;
            }
            int currentTaskId = activity.getTaskId();
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info != null && info.id == currentTaskId) {
                    task.setExcludeFromRecents(excludeFromRecents);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // This is cosmetic only. Link restoration must never depend on task-manager access.
        }
    }
}
