package com.huanchengfly.icebridge.android16;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import com.huanchengfly.icebridge.automation.SmallWindowLayout;

/** Display coordinates shared by native launch bounds and the progress card. */
public final class SystemWindowLayout {
    private SystemWindowLayout() { }

    /** Stable bars and cutouts remain excluded even if the current source hides them. */
    public static Rect availableBounds(Context context) {
        WindowManager manager = context.getSystemService(WindowManager.class);
        if (manager == null) throw new IllegalStateException("window manager missing");
        if (Build.VERSION.SDK_INT >= 30) {
            // Maximum metrics are display-wide even if the settings or source Activity is in a window.
            WindowMetrics metrics = manager.getMaximumWindowMetrics();
            Rect display = new Rect(metrics.getBounds());
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Rect available = new Rect(display.left + insets.left, display.top + insets.top,
                    display.right - insets.right, display.bottom - insets.bottom);
            if (!available.isEmpty()) return available;
            if (!display.isEmpty()) return display;
            throw new IllegalStateException("display bounds missing");
        }
        DisplayMetrics metrics = new DisplayMetrics();
        manager.getDefaultDisplay().getRealMetrics(metrics);
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            throw new IllegalStateException("display bounds missing");
        }
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    public static Rect resolve(Context context, SmallWindowLayout layout) {
        if (layout == null) throw new IllegalArgumentException("small-window layout missing");
        Rect available = availableBounds(context);
        SmallWindowLayout.Bounds target = layout.resolve(available.left, available.top,
                available.width(), available.height());
        return new Rect(target.left, target.top, target.right, target.bottom);
    }
}
