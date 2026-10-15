package com.huanchengfly.icebridge.android16;

import android.app.ActivityOptions;
import android.os.Bundle;

/** Requests the platform's freeform mode, leaving sizing and placement to the ROM. */
public final class SystemFreeformLaunchOptions {
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private SystemFreeformLaunchOptions() { }

    public static Bundle create() throws ReflectiveOperationException {
        ActivityOptions options = ActivityOptions.makeBasic();
        ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class)
                .invoke(options, WINDOWING_MODE_FREEFORM);
        return options.toBundle();
    }
}
