package com.huanchengfly.icebridge.ui.theme;

/** A cold-start launch is consumed by the first user UI, never by background bridge work. */
public final class ThemeLaunchGate {
    private boolean userSessionStarted;

    public synchronized boolean beginIfUserFacing(boolean userFacing) {
        if (!userFacing || userSessionStarted) return false;
        userSessionStarted = true;
        return true;
    }
}
