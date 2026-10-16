package com.huanchengfly.icebridge.android16;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;

/** App-only preview of the actual compact native card; it never creates a system overlay. */
public final class SmallWindowCardPreview extends FrameLayout {
    private final TransitFlowView flow;

    public SmallWindowCardPreview(Context context, int seed, boolean dark, String targetPackage) {
        super(context);
        TransitSessionPolicy.Palette palette = TransitSessionPolicy.Palette.fromSeed(seed, dark);
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.card);
        background.setCornerRadius(dp(15));
        background.setStroke(Math.max(1, Math.round(dp(1))), palette.outline);
        setBackground(background);
        setElevation(dp(8));
        setContentDescription("小窗中转卡片预览");
        flow = new TransitFlowView(context);
        PackageManager packages = context.getPackageManager();
        Drawable source = context.getApplicationInfo().loadIcon(packages);
        Drawable target = packages.getDefaultActivityIcon();
        String targetName = "目标应用";
        try {
            if (targetPackage != null) {
                target = packages.getApplicationIcon(targetPackage);
                targetName = String.valueOf(packages.getApplicationLabel(
                        packages.getApplicationInfo(targetPackage, PackageManager.MATCH_DISABLED_COMPONENTS)));
            }
        } catch (PackageManager.NameNotFoundException ignored) { }
        flow.bind(source, "IceBridge", target, targetName,
                TransitSessionPolicy.Palette.fromSeed(seed, dark));
        addView(flow, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    public void setFrameScale(float scale) {
        setPadding(Math.round(dp(8) * scale), Math.round(dp(6) * scale),
                Math.round(dp(8) * scale), Math.round(dp(6) * scale));
        flow.setCompactScale(.75f * scale);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
