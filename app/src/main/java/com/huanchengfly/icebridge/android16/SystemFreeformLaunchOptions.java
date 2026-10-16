package com.huanchengfly.icebridge.android16;

import android.app.ActivityOptions;
import android.content.Context;
import android.os.Bundle;

import com.huanchengfly.icebridge.automation.SmallWindowLayout;

/** Uses screen-visible bounds, including ROMs with a separate freeform surface scale. */
public final class SystemFreeformLaunchOptions {
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private SystemFreeformLaunchOptions() { }

    public static Bundle create() throws ReflectiveOperationException {
        return basic().toBundle();
    }

    public static Bundle create(Context context, SmallWindowLayout layout) throws ReflectiveOperationException {
        ActivityOptions options = basic();
        options.setLaunchBounds(SystemWindowLayout.resolve(context, layout));
        // MIUI otherwise applies its default surface scale (0.7 on the reported device)
        // to these already visual dimensions. Set it per launch, without changing ROM defaults.
        SystemMiuiFreeform.configureUnscaledLaunch(options);
        return options.toBundle();
    }

    private static ActivityOptions basic() throws ReflectiveOperationException {
        ActivityOptions options = ActivityOptions.makeBasic();
        ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class)
                .invoke(options, WINDOWING_MODE_FREEFORM);
        return options;
    }
}
