package com.huanchengfly.icebridge.android16;

import org.junit.Test;

import static org.junit.Assert.*;

public class ActivityStarterTakeoverPolicyTest {
    private static final String SOURCE = "com.tencent.mm";

    @Test public void ordinaryAndResolvedCallerIdentityMustStayBoundToTheBinderCaller() {
        assertTrue(ActivityStarterTakeoverPolicy.callerMatches(SOURCE, SOURCE, 10234, -1, -1));
        assertTrue(ActivityStarterTakeoverPolicy.callerMatches(SOURCE, SOURCE, 10234, 10234, 10234));
        assertTrue(ActivityStarterTakeoverPolicy.callerMatches(SOURCE, SOURCE, 110234, 110234, -1));
    }

    @Test public void forgedPackageOrUidOverrideCannotBeTakenOver() {
        assertFalse(ActivityStarterTakeoverPolicy.callerMatches(SOURCE, "com.other.app", 10234, -1, -1));
        assertFalse(ActivityStarterTakeoverPolicy.callerMatches(SOURCE, SOURCE, 10234, 10456, -1));
        assertFalse(ActivityStarterTakeoverPolicy.callerMatches(SOURCE, SOURCE, 10234, -1, 10456));
        assertFalse(ActivityStarterTakeoverPolicy.callerMatches(SOURCE, SOURCE, 10234, -10000, -1));
    }

    @Test public void unknownSourceAndCallerDoNotClaimAnOriginalRequest() {
        assertFalse(ActivityStarterTakeoverPolicy.callerMatches(null, SOURCE, 10234, -1, -1));
        assertFalse(ActivityStarterTakeoverPolicy.callerMatches("", "", 10234, -1, -1));
        assertFalse(ActivityStarterTakeoverPolicy.callerMatches(SOURCE, null, 10234, -1, -1));
        assertFalse(ActivityStarterTakeoverPolicy.callerMatches(SOURCE, SOURCE, -1, -1, -1));
    }

    @Test public void nestedOrRecycledFrameworkRequestsCannotConsumeAnotherReservation() {
        Object starter = new Object();
        Object request = new Object();
        Object reservation = new Object();
        ActivityStarterTakeoverPolicy.Frame<Object> frame =
                new ActivityStarterTakeoverPolicy.Frame<>(starter, request, reservation);
        assertFalse(frame.suppress(new Object(), request));
        assertFalse(frame.suppress(starter, new Object()));
        assertFalse(frame.wasSuppressed());
        assertTrue(frame.owns(starter, request));
        assertSame(reservation, frame.reservation);
    }

    @Test public void exactRequestCanBeSuppressedOnlyOnce() {
        Object starter = new Object();
        Object request = new Object();
        ActivityStarterTakeoverPolicy.Frame<Object> frame =
                new ActivityStarterTakeoverPolicy.Frame<>(starter, request, new Object());
        assertTrue(frame.suppress(starter, request));
        assertTrue(frame.wasSuppressed());
        assertFalse(frame.suppress(starter, request));
        assertFalse(frame.owns(starter, request));
    }
}
