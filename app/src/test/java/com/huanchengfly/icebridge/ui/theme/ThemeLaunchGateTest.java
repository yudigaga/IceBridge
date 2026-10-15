package com.huanchengfly.icebridge.ui.theme;

import org.junit.Test;
import static org.junit.Assert.*;

public class ThemeLaunchGateTest {
    @Test public void serviceAndAutomaticBridgeDoNotConsumeUserLaunch() {
        ThemeLaunchGate gate = new ThemeLaunchGate();
        assertFalse(gate.beginIfUserFacing(false));
        assertFalse(gate.beginIfUserFacing(false));
        assertTrue(gate.beginIfUserFacing(true));
    }

    @Test public void navigationRecreationAndReturnShareTheSameLaunch() {
        ThemeLaunchGate gate = new ThemeLaunchGate();
        assertTrue(gate.beginIfUserFacing(true));
        for (int i = 0; i < 10; i++) assertFalse(gate.beginIfUserFacing(true));
        assertFalse(gate.beginIfUserFacing(false));
    }

    @Test public void aNewApplicationLaunchCanChooseAgain() {
        assertTrue(new ThemeLaunchGate().beginIfUserFacing(true));
        assertTrue(new ThemeLaunchGate().beginIfUserFacing(true));
    }
}
