package com.huanchengfly.icebridge.android16;

import android.content.Intent;
import android.os.SystemClock;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Real Intent extras with the ActivityRecord field and zero-argument method shapes. */
@RunWith(AndroidJUnit4.class)
public class SystemTargetWindowObserverTest {
    private static final String TARGET = "com.example.destination";

    @Test public void coldDrawReportsItsTransitIdPackageAndUser() {
        SystemTargetWindowObserver observer = new SystemTargetWindowObserver();
        Record record = new Record(TARGET, 0, tagged(41));
        record.mReportedDrawn = true;
        long before = SystemClock.elapsedRealtime();
        SystemTargetWindowObserver.Event event = observer.read(record, false);
        assertTarget(event, 41, 0, TARGET);
        assertTrue(event.observedAt >= before);
        assertTrue(event.observedAt <= SystemClock.elapsedRealtime());
    }

    @Test public void taggedButNotDrawnCannotFinishTheResumePath() {
        SystemTargetWindowObserver observer = new SystemTargetWindowObserver();
        Record record = new Record(TARGET, 0, tagged(41));
        assertNull(observer.read(record, true));
    }

    @Test public void warmNewIntentOverridesTheRecordsInitialLaunchId() {
        SystemTargetWindowObserver observer = new SystemTargetWindowObserver();
        Record record = visible(TARGET, 0, tagged(41));
        observer.onNewIntent(record, tagged(42));
        assertEquals(41, record.intent.getLongExtra(SystemTargetWindowObserver.EXTRA_TRANSIT_ID, 0));
        assertTarget(observer.read(record, true), 42, 0, TARGET);
        observer.onNewIntent(record, tagged(43));
        assertTarget(observer.read(record, true), 43, 0, TARGET);
    }

    @Test public void deliveredTransitIdIsScopedToItsActivityRecord() {
        SystemTargetWindowObserver observer = new SystemTargetWindowObserver();
        Record first = visible(TARGET, 0, tagged(41));
        Record unrelated = visible(TARGET, 0, new Intent(Intent.ACTION_VIEW));
        observer.onNewIntent(first, tagged(42));
        assertTarget(observer.read(first, true), 42, 0, TARGET);
        assertEquals(0, observer.read(unrelated, true).id);
    }

    @Test public void untaggedRouterDeliveryDoesNotKeepThePreviousLaunchToken() {
        SystemTargetWindowObserver observer = new SystemTargetWindowObserver();
        Record record = visible(TARGET, 0, tagged(41));
        observer.onNewIntent(record, tagged(42));
        assertTarget(observer.read(record, true), 42, 0, TARGET);
        observer.onNewIntent(record, new Intent(Intent.ACTION_VIEW));
        SystemTargetWindowObserver.Event candidate = observer.read(record, true);
        assertNotNull(candidate);
        assertEquals(0, candidate.id);
        assertEquals(TARGET, candidate.packageName);
        assertEquals(41, record.intent.getLongExtra(SystemTargetWindowObserver.EXTRA_TRANSIT_ID, 0));
    }

    @Test public void workProfileUserIsPreservedInColdAndWarmEvents() {
        SystemTargetWindowObserver observer = new SystemTargetWindowObserver();
        Record record = visible(TARGET, 10, tagged(41));
        assertTarget(observer.read(record, true), 41, 10, TARGET);
        observer.onNewIntent(record, tagged(42));
        assertTarget(observer.read(record, true), 42, 10, TARGET);
    }

    @Test public void missingOrInvalidTagDoesNotReportAnUnrelatedTargetAsReady() {
        SystemTargetWindowObserver observer = new SystemTargetWindowObserver();
        for (Intent intent : new Intent[]{new Intent(Intent.ACTION_VIEW), tagged(0), tagged(-1)}) {
            SystemTargetWindowObserver.Event candidate = observer.read(visible("com.example.unrelated", 0, intent), false);
            assertNotNull(candidate);
            assertEquals(0, candidate.id);
            assertFalse(candidate.systemPrompt);
        }
        assertNull(observer.read(null, false));
    }

    @Test public void visiblePermissionWindowsAreInterruptionsWithoutATargetId() {
        SystemTargetWindowObserver observer = new SystemTargetWindowObserver();
        for (String pkg : new String[]{"com.android.permissioncontroller",
                "com.google.android.permissioncontroller", "com.lbe.security.miui",
                "com.android.packageinstaller"}) {
            SystemTargetWindowObserver.Event event = observer.read(visible(pkg, 0, new Intent()), true);
            assertNotNull(pkg, event);
            assertTrue(pkg, event.systemPrompt);
            assertEquals(0, event.id);
            assertEquals(0, event.userId);
            assertEquals(pkg, event.packageName);
            TransitSessionPolicy sessions = new TransitSessionPolicy();
            assertTrue(sessions.stage(41, 0, TARGET, TransitSessionPolicy.OPENING,
                    0xff7e57c2, "light", 0));
            assertFalse(sessions.targetDrawn(event.id, event.userId, event.packageName));
            assertNotNull(sessions.active());
        }
    }

    @Test public void stoppedOrInvisibleRecordsCannotCompleteAWarmLaunch() {
        SystemTargetWindowObserver observer = new SystemTargetWindowObserver();
        Record record = visible(TARGET, 0, tagged(41));
        record.state = ActivityState.STOPPED;
        assertNull(observer.read(record, true));
        record.state = ActivityState.RESUMED;
        record.mVisibleRequested = false;
        assertNull(observer.read(record, true));
        record.mVisibleRequested = true;
        assertTarget(observer.read(record, true), 41, 0, TARGET);
    }

    @Test public void hiddenStoppedOrUndrawnPermissionWindowDoesNotInterrupt() {
        SystemTargetWindowObserver observer = new SystemTargetWindowObserver();
        Record record = visible("com.android.permissioncontroller", 0, new Intent());
        record.state = ActivityState.STOPPED;
        assertNull(observer.read(record, false));
        record.state = ActivityState.RESUMED;
        record.mVisibleRequested = false;
        assertNull(observer.read(record, false));
        record.mVisibleRequested = true;
        record.mReportedDrawn = false;
        assertNull(observer.read(record, true));
        // A verified onWindowsDrawn callback is itself proof of a draw, before the flag commits.
        SystemTargetWindowObserver.Event event = observer.read(record, false);
        assertNotNull(event);
        assertTrue(event.systemPrompt);
        assertEquals(0, event.id);
    }

    @Test public void officialVisibilityUpdateCommitsDrawnFlagAfterItsInnerDrawCallback() {
        SystemTargetWindowObserver observer = new SystemTargetWindowObserver();
        Record record = new Record(TARGET, 0, tagged(41));
        // AOSP calls onWindowsDrawn() before assigning mReportedDrawn in the outer update.
        // read(false) belongs only to that verified callback and must trust its draw event.
        assertTarget(observer.read(record, false), 41, 0, TARGET);
        record.mReportedDrawn = true;
        assertTarget(observer.read(record, false), 41, 0, TARGET);
    }

    private static Intent tagged(long id) {
        return new Intent(Intent.ACTION_VIEW)
                .putExtra(SystemTargetWindowObserver.EXTRA_TRANSIT_ID, id);
    }

    private static Record visible(String pkg, int user, Intent intent) {
        Record record = new Record(pkg, user, intent);
        record.mReportedDrawn = true;
        record.mVisibleRequested = true;
        record.state = ActivityState.RESUMED;
        return record;
    }

    private static void assertTarget(SystemTargetWindowObserver.Event event, long id,
                                     int user, String pkg) {
        assertNotNull(event);
        assertEquals(id, event.id);
        assertEquals(user, event.userId);
        assertEquals(pkg, event.packageName);
        assertFalse(event.systemPrompt);
    }

    private enum ActivityState { INITIALIZING, RESUMED, STOPPED }

    /** Names match com.android.server.wm.ActivityRecord in AOSP Android 16. */
    private static final class Record {
        final String packageName;
        final int mUserId;
        final Intent intent;
        boolean mReportedDrawn;
        boolean mVisibleRequested;
        ActivityState state = ActivityState.INITIALIZING;

        Record(String packageName, int userId, Intent intent) {
            this.packageName = packageName;
            this.mUserId = userId;
            this.intent = intent;
        }

        boolean isReportedDrawn() { return mReportedDrawn; }
        boolean isVisibleRequested() { return mVisibleRequested; }
        ActivityState getState() { return state; }
    }
}
