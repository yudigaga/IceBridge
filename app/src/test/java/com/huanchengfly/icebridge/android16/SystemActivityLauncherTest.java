package com.huanchengfly.icebridge.android16;

import android.content.Intent;
import android.os.Bundle;

import org.junit.Test;

import java.lang.reflect.Method;

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

    @Test public void rejectedAtmsStartIsOneAttempt() throws Exception {
        FakeAtms service = new FakeAtms();
        service.result = 102;
        try {
            SystemActivityLauncher.invokeStart(service, startMethod(), null, null, 10, null);
            fail("START_ABORTED must not trigger a second attempt without options");
        } catch (SystemActivityLauncher.StartRejectedException expected) {
            assertEquals(102, expected.result);
        }
        assertEquals(1, service.calls);
        assertEquals(10, service.userId);
        assertNull(service.options);
    }

    @Test public void possiblyAcceptedStartExceptionIsUnwrappedWithoutRetry() throws Exception {
        FakeAtms service = new FakeAtms();
        service.failure = new IllegalStateException("binder-connection-lost");
        try {
            SystemActivityLauncher.invokeStart(service, startMethod(), null, null, 0, null);
            fail("expected original start failure");
        } catch (IllegalStateException expected) {
            assertSame(service.failure, expected);
        }
        assertEquals(1, service.calls);
    }

    private static Method startMethod() throws Exception {
        return FakeAtms.class.getMethod("startActivityAsUser", Object.class, String.class,
                String.class, Intent.class, String.class, Object.class, String.class,
                int.class, int.class, Object.class, Bundle.class, int.class);
    }

    public static final class FakeAtms {
        int result;
        int calls;
        int userId;
        Bundle options;
        RuntimeException failure;
        public int startActivityAsUser(Object caller, String callingPackage, String feature,
                Intent intent, String resolvedType, Object resultTo, String resultWho,
                int requestCode, int flags, Object profiler, Bundle options, int userId) {
            calls++;
            this.userId = userId;
            this.options = options;
            if (failure != null) throw failure;
            return result;
        }
    }
}
