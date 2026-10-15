package com.huanchengfly.icebridge.android16;

import org.junit.Test;

import static org.junit.Assert.*;

public class SystemActivityLauncherTest {
    @Test public void acceptsStartSuccessTaskToFrontAndDeliveredToTop() throws Exception {
        for (int value : new int[]{0, 1, 2, 3, 99}) {
            assertEquals(value, SystemActivityLauncher.requireSuccessfulResult(value));
        }
    }

    @Test public void rejectsRealCallerFailureAndPositiveAbortInsteadOfClaimingRouted() {
        for (int value : new int[]{-94, -1, -2, 100, 101, 102, Integer.MAX_VALUE}) {
            try {
                SystemActivityLauncher.requireSuccessfulResult(value);
                fail("expected rejected ATMS result " + value);
            } catch (SystemActivityLauncher.StartRejectedException expected) {
                assertEquals(value, expected.result);
                assertTrue(expected.getMessage().contains(String.valueOf(value)));
            }
        }
    }

    @Test public void missingOrWrongResultTypeFailsClosed() throws Exception {
        for (Object value : new Object[]{null, "0", Boolean.TRUE, Long.valueOf(0)}) {
            try {
                SystemActivityLauncher.requireSuccessfulResult(value);
                fail("expected unavailable result");
            } catch (IllegalStateException expected) {
                assertEquals("activity-task-result-unavailable", expected.getMessage());
            }
        }
    }

    @Test public void unknownServiceSignatureIsNotInvoked() {
        assertNull(SystemActivityLauncher.findStartMethod(Object.class));
    }
}
