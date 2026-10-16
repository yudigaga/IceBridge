package com.huanchengfly.icebridge.android16;

import android.content.Intent;
import android.graphics.Rect;
import android.app.ActivityManager;
import android.app.Instrumentation;

import androidx.test.InstrumentationRegistry;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Framework-shaped local fixtures exercise snapshots with real mutable Rect and Intent objects. */
@RunWith(AndroidJUnit4.class)
public class SystemTaskBoundsObserverTest {
    private static final String TARGET = "com.example.destination";

    @Test public void runningTaskQueryReadsActualAndroidTaskInfoMetadata() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        TransitPreviewActivity activity = (TransitPreviewActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), TransitPreviewActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.waitForIdleSync();
            ActivityManager manager = activity.getSystemService(ActivityManager.class);
            TransitTaskBounds actual = null;
            for (ActivityManager.RunningTaskInfo info : manager.getRunningTasks(20)) {
                if (info.id == activity.getTaskId()) {
                    actual = SystemTaskBoundsObserver.runningTask(info);
                    break;
                }
            }
            assertNotNull("The worker query must read real TaskInfo configuration and visibility fields", actual);
            assertEquals(activity.getTaskId(), actual.taskId);
            assertEquals(activity.getPackageName(), actual.packageName);
            assertEquals(0, actual.userId);
            assertEquals(0, actual.displayId);
            assertTrue(actual.right > actual.left);
            assertTrue(actual.bottom > actual.top);
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void copiesTheActualTaskBoundsAndKeepsIdentityAcrossMovesAndResizes() {
        SystemTaskBoundsObserver observer = new SystemTaskBoundsObserver();
        Task task = new Task(7, 0, new Record(TARGET, 0, tagged(41)));
        TransitTaskBounds first = observer.read(task);
        assertNotNull(first);
        assertTrue(first.isFreeform());
        assertEquals(41, first.transitId);
        assertEquals(7, first.taskId);
        assertEquals(TARGET, first.packageName);
        assertEquals(40, first.left);
        task.bounds.set(240, 300, 700, 900);
        TransitTaskBounds second = observer.read(task);
        assertNotNull(second);
        assertEquals(first.taskInstance, second.taskInstance);
        assertEquals(40, first.left);
        assertEquals(240, second.left);
        assertEquals(300, second.top);
        assertEquals(700, second.right);
        assertEquals(900, second.bottom);
        Task reusedId = new Task(7, 0, new Record(TARGET, 0, tagged(42)));
        assertNotEquals(first.taskInstance, observer.read(reusedId).taskInstance);
    }

    @Test public void warmTaskSnapshotsUseTheNewDeliveredTokenRatherThanItsOldIntent() {
        SystemTargetWindowObserver launches = new SystemTargetWindowObserver();
        SystemTaskBoundsObserver observer = new SystemTaskBoundsObserver(launches);
        Record record = new Record(TARGET, 0, tagged(41));
        Task task = new Task(7, 0, record);
        record.task = task;
        assertEquals(41, observer.readActivity(record).transitId);
        launches.onNewIntent(record, tagged(42));
        assertEquals(42, observer.read(task).transitId);
        assertEquals(41, record.intent.getLongExtra(SystemTargetWindowObserver.EXTRA_TRANSIT_ID, 0));
        launches.onNewIntent(record, new Intent(Intent.ACTION_VIEW));
        assertEquals(0, observer.read(task).transitId);
    }

    @Test public void nativeMiuiScaleConvertsLogicalTaskBoundsToTheVisiblePhoneFrame() {
        SystemTaskBoundsObserver observer = new SystemTaskBoundsObserver();
        Task task = new Task(7, 0, new Record(TARGET, 0, tagged(41)));
        task.bounds.set(347, 315, 1369, 1644);
        NativeStack stack = attachNativeStack(task, 0.7f);

        TransitTaskBounds actual = observer.read(task);
        assertNotNull(actual);
        assertTrue(actual.isFreeform());
        assertBounds(actual, 347, 315, 1062, 1245);
        assertEquals("The framework's mutable logical bounds must remain untouched",
                new Rect(347, 315, 1369, 1644), task.bounds);
        assertEquals(7, stack.lastTaskId);
    }

    @Test public void nativeMiuiScaleOnlyChangesKeepTheSameTaskIdentityAndDoNotRescaleOldSnapshots() {
        SystemTaskBoundsObserver observer = new SystemTaskBoundsObserver();
        Task task = new Task(7, 0, new Record(TARGET, 0, tagged(41)));
        task.bounds.set(347, 315, 1369, 1644);
        NativeStack stack = attachNativeStack(task, 0.7f);
        TransitTaskBounds scaled = observer.read(task);
        assertNotNull(scaled);
        assertBounds(scaled, 347, 315, 1062, 1245);

        stack.scale = 1.0f;
        TransitTaskBounds unscaled = observer.read(task);
        assertNotNull(unscaled);
        assertEquals(scaled.taskInstance, unscaled.taskInstance);
        assertEquals(scaled.transitId, unscaled.transitId);
        assertBounds(unscaled, 347, 315, 1369, 1644);
        assertBounds(scaled, 347, 315, 1062, 1245);

        stack.scale = 0.5f;
        TransitTaskBounds smaller = observer.read(task);
        assertNotNull(smaller);
        assertEquals(scaled.taskInstance, smaller.taskInstance);
        assertBounds(smaller, 347, 315, 858, 980);
        assertEquals(new Rect(347, 315, 1369, 1644), task.bounds);
    }

    @Test public void invalidNativeScalesKeepFinitePositiveLogicalBoundsAsTheFallback() {
        SystemTaskBoundsObserver observer = new SystemTaskBoundsObserver();
        Task task = new Task(7, 0, new Record(TARGET, 0, tagged(41)));
        task.bounds.set(347, 315, 1369, 1644);
        NativeStack stack = attachNativeStack(task, 1.0f);
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, 0.0f, -0.7f, -1.0f}) {
            stack.scale = invalid;
            TransitTaskBounds actual = observer.read(task);
            assertNotNull("Malformed cosmetic metadata must not discard the task: " + invalid, actual);
            assertTrue(actual.isFreeform());
            assertBounds(actual, 347, 315, 1369, 1644);
        }
    }

    @Test public void missingOrThrowingNativeStackFallsBackWithoutDiscardingTheTask() {
        SystemTaskBoundsObserver observer = new SystemTaskBoundsObserver();
        Task task = new Task(7, 0, new Record(TARGET, 0, tagged(41)));
        task.bounds.set(347, 315, 1369, 1644);
        NativeStack stack = attachNativeStack(task, 0.7f);
        NativeManager manager = task.mAtmService.mMiuiFreeFormManagerService;
        manager.stack = null;
        assertBounds(observer.read(task), 347, 315, 1369, 1644);
        manager.stack = stack;
        manager.throwOnRead = true;
        assertBounds(observer.read(task), 347, 315, 1369, 1644);
        manager.throwOnRead = false;
        task.mAtmService.mMiuiFreeFormManagerService = null;
        assertBounds(observer.read(task), 347, 315, 1369, 1644);
        task.mAtmService = null;
        assertBounds(observer.read(task), 347, 315, 1369, 1644);
    }

    @Test public void staleNativeFreeformScaleCannotShrinkAFullscreenTaskSnapshot() {
        SystemTaskBoundsObserver observer = new SystemTaskBoundsObserver();
        Task task = new Task(7, 0, new Record(TARGET, 0, tagged(41)));
        task.bounds.set(347, 315, 1369, 1644);
        attachNativeStack(task, 0.7f);
        task.mode = 1;
        TransitTaskBounds actual = observer.read(task);
        assertNotNull(actual);
        assertFalse(actual.isFreeform());
        assertBounds(actual, 347, 315, 1369, 1644);
    }

    @Test public void observingMoreThan128LiveTasksDoesNotChangeAnExistingTaskIdentity() {
        SystemTaskBoundsObserver observer = new SystemTaskBoundsObserver();
        Task[] live = new Task[129];
        for (int index = 0; index < live.length; index++) {
            live[index] = new Task(index + 1, 0, new Record(TARGET, 0, tagged(index + 1)));
        }
        long firstIdentity = observer.read(live[0]).taskInstance;
        for (int index = 1; index < live.length; index++) assertNotNull(observer.read(live[index]));
        assertEquals(firstIdentity, observer.read(live[0]).taskInstance);
        // Keep every Task live through the assertion so this cannot accidentally pass through GC.
        for (Task task : live) assertNotNull(task);
    }

    @Test public void onlyLeafTasksWithConsistentUserAndActivityMetadataProduceSnapshots() {
        SystemTaskBoundsObserver observer = new SystemTaskBoundsObserver();
        assertNull(observer.read(null));
        Task task = new Task(7, 0, new Record(TARGET, 0, tagged(41)));
        task.leaf = false;
        assertNull(observer.read(task));
        task.leaf = true;
        task.record = null;
        assertNull(observer.read(task));
        task.record = new Record(TARGET, 10, tagged(41));
        assertNull(observer.read(task));
        task.mUserId = 10;
        assertEquals(10, observer.read(task).userId);
    }

    @Test public void nonFreeformOtherDisplayAndEmptyBoundsRemainIneligibleAnchors() {
        SystemTaskBoundsObserver observer = new SystemTaskBoundsObserver();
        Task task = new Task(7, 0, new Record(TARGET, 0, tagged(41)));
        task.mode = 1;
        assertFalse(observer.read(task).isFreeform());
        task.mode = 5;
        task.display = 2;
        assertFalse(observer.read(task).isFreeform());
        task.display = 0;
        task.bounds.setEmpty();
        assertFalse(observer.read(task).isFreeform());
    }

    private static Intent tagged(long id) {
        return new Intent(Intent.ACTION_VIEW).putExtra(SystemTargetWindowObserver.EXTRA_TRANSIT_ID, id);
    }

    private static void assertBounds(TransitTaskBounds actual, int left, int top, int right, int bottom) {
        assertNotNull(actual);
        assertEquals(left, actual.left);
        assertEquals(top, actual.top);
        assertEquals(right, actual.right);
        assertEquals(bottom, actual.bottom);
    }

    private static NativeStack attachNativeStack(Task task, float scale) {
        NativeStack stack = new NativeStack(task, scale);
        task.mAtmService = new NativeAtmService(new NativeManager(stack));
        return stack;
    }

    private static final class NativeAtmService {
        NativeManager mMiuiFreeFormManagerService;
        NativeAtmService(NativeManager manager) { mMiuiFreeFormManagerService = manager; }
    }

    private static final class NativeManager {
        NativeStack stack;
        boolean throwOnRead;
        NativeManager(NativeStack stack) { this.stack = stack; }
        NativeStack getMiuiFreeFormActivityStack(int taskId) {
            if (throwOnRead) throw new IllegalStateException("fixture-native-stack-unavailable");
            if (stack != null) stack.lastTaskId = taskId;
            return stack;
        }
    }

    private static final class NativeStack {
        final Task mTask;
        float scale;
        int lastTaskId = -1;
        NativeStack(Task task, float scale) { mTask = task; this.scale = scale; }
        float getFreeFormScale() { return scale; }
    }

    // The native ATM service field can be inherited on vendor Task implementations.
    private static class TaskContainer {
        NativeAtmService mAtmService;
    }

    private static final class Record {
        final String packageName;
        final int mUserId;
        final Intent intent;
        Task task;
        Record(String pkg, int user, Intent intent) {
            this.packageName = pkg; this.mUserId = user; this.intent = intent;
        }
        Task getTask() { return task; }
    }

    private static final class Task extends TaskContainer {
        final int mTaskId;
        int mUserId;
        Record record;
        Rect bounds = new Rect(40, 80, 440, 680);
        int mode = 5, display;
        boolean leaf = true;
        Task(int id, int user, Record record) { mTaskId = id; mUserId = user; this.record = record; }
        boolean isLeafTask() { return leaf; }
        Record getTopNonFinishingActivity() { return record; }
        int getWindowingMode() { return mode; }
        int getDisplayId() { return display; }
        Rect getBounds() { return bounds; }
    }
}
