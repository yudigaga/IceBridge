package com.huanchengfly.icebridge.android16;

import android.app.ActivityOptions;
import android.content.Context;
import android.os.Bundle;

import com.huanchengfly.icebridge.automation.SmallWindowLayout;

/** Requests the platform's freeform mode, leaving sizing and placement to the ROM. */
public final class SystemFreeformLaunchOptions {
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private SystemFreeformLaunchOptions() { }

    public static Bundle create() throws ReflectiveOperationException {
        return basic().toBundle();
    }

    public static Bundle create(Context context, SmallWindowLayout layout) throws ReflectiveOperationException {
        ActivityOptions options = basic();
        options.setLaunchBounds(SystemWindowLayout.resolve(context, layout));
        return options.toBundle();
    }

    private static ActivityOptions basic() throws ReflectiveOperationException {
        ActivityOptions options = ActivityOptions.makeBasic();
        ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class)
                .invoke(options, WINDOWING_MODE_FREEFORM);
        return options;
    }
}
