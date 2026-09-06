package com.huanchengfly.icebridge.android16;

import android.os.Build;

public final class CapabilityProbe {
    private CapabilityProbe() {
    }

    public static String fingerprint() {
        return "sdk=" + Build.VERSION.SDK_INT + ";release=" + Build.VERSION.RELEASE;
    }

    public static boolean isAndroid16() {
        return Build.VERSION.SDK_INT == 36;
    }
}
