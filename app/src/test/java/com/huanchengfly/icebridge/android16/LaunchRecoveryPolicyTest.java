package com.huanchengfly.icebridge.android16;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LaunchRecoveryPolicyTest {
    @Test
    public void normalDisabledUnsuspendedPackageIsRecoverable() {
        assertTrue(LaunchRecoveryPolicy.isRecoverableState(2, false));
    }

    @Test
    public void enabledAndUnsupportedStatesAreNotRecoverable() {
        assertFalse(LaunchRecoveryPolicy.isRecoverableState(0, false));
        assertFalse(LaunchRecoveryPolicy.isRecoverableState(1, false));
        assertFalse(LaunchRecoveryPolicy.isRecoverableState(-1, false));
    }

    @Test
    public void suspendedPackageIsNotRecoverable() {
        assertFalse(LaunchRecoveryPolicy.isRecoverableState(2, true));
    }

    @Test
    public void hiddenPackageIsRecoverableEvenWhenEnabledSettingIsDefault() {
        assertTrue(LaunchRecoveryPolicy.isRecoverableState(0, true, true, false));
        assertFalse(LaunchRecoveryPolicy.isRecoverableState(0, false, true, false));
        assertFalse(LaunchRecoveryPolicy.isRecoverableState(0, true, true, true));
    }

    @Test
    public void disableUserStatesAreRecoverable() {
        assertTrue(LaunchRecoveryPolicy.isRecoverableState(3, false, false, false));
        assertTrue(LaunchRecoveryPolicy.isRecoverableState(4, false, false, false));
    }

}
