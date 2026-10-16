package com.huanchengfly.icebridge.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.huanchengfly.icebridge.SystemServerConfigContract;
import com.huanchengfly.icebridge.activities.SmallWindowFrameEditorActivity;
import com.huanchengfly.icebridge.activities.SmallWindowFrameEditorView;
import com.huanchengfly.icebridge.android16.SystemWindowLayout;
import com.huanchengfly.icebridge.automation.SmallWindowLayout;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Actual display geometry and gesture/save lifecycle without an exported endpoint. */
@RunWith(AndroidJUnit4.class)
public class SmallWindowLayoutSettingsTest {
    private static final String KEY = SystemServerConfigContract.KEY_SMALL_WINDOW_LAYOUT;

    @Test public void transparentEditorUsesActualDisplayAndCentersTheCompactCard() {
        Fixture fixture = new Fixture();
        try {
            SmallWindowLayout saved = new SmallWindowLayout(55, 40, 0, 100);
            assertTrue(fixture.preferences.edit().putString(KEY, saved.encode()).commit());
            fixture.open();
            fixture.instrumentation.runOnMainSync(() -> {
                SmallWindowFrameEditorView frame = fixture.activity.getFrameEditor();
                Rect available = SystemWindowLayout.availableBounds(fixture.activity);
                assertEquals(available, frame.getAvailableBoundsOnScreen());
                assertTrue("Edit surface must span the real display, not a thumbnail", frame.getWidth() >= available.width());
                assertTrue(frame.getHeight() >= available.height());
                assertEquals(saved, frame.getDraft());
                assertFrameMatchesDraft(frame);
                assertCardCentered(frame);
                assertEquals("Editor must expose the real screen underneath", 0,
                        ((ColorDrawable) fixture.activity.getWindow().getDecorView().getBackground()).getAlpha());
            });
        } finally { fixture.close(); }
    }

    @Test public void draggingAndResizingTheFrameMovesItsCardWithoutSaving() {
        Fixture fixture = new Fixture();
        try {
            fixture.clearAndOpen();
            seedConfirmation(fixture.status);
            fixture.instrumentation.runOnMainSync(() -> {
                SmallWindowFrameEditorView frame = fixture.activity.getFrameEditor();
                Rect before = frame.getFrameBoundsOnScreen(), area = frame.getAvailableBoundsOnScreen();
                drag(frame, before.centerX(), before.centerY(), before.centerX() - area.width() * .12f,
                        before.centerY() - area.height() * .10f);
                assertTrue(frame.getDraft().horizontalPercent < SmallWindowLayout.DEFAULT.horizontalPercent);
                assertTrue(frame.getDraft().verticalPercent < SmallWindowLayout.DEFAULT.verticalPercent);
                assertFrameMatchesDraft(frame);
                assertCardCentered(frame);
                Rect moved = frame.getFrameBoundsOnScreen();
                drag(frame, moved.right, moved.bottom, moved.right - area.width() * .10f,
                        moved.bottom - area.height() * .10f);
                assertTrue(frame.getDraft().widthPercent < SmallWindowLayout.DEFAULT.widthPercent);
                assertTrue(frame.getDraft().heightPercent < SmallWindowLayout.DEFAULT.heightPercent);
                assertFrameMatchesDraft(frame);
                assertCardCentered(frame);
            });
            assertFalse("Gestures remain a draft until Save", fixture.preferences.contains(KEY));
            assertTrue("Draft changes must not revoke runtime confirmation",
                    fixture.status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false));
        } finally { fixture.close(); }
    }

    @Test public void extremeGesturesStayInsideDisplayAndRespectSizeLimits() {
        Fixture fixture = new Fixture();
        try {
            fixture.clearAndOpen();
            fixture.instrumentation.runOnMainSync(() -> {
                SmallWindowFrameEditorView frame = fixture.activity.getFrameEditor();
                Rect bounds = frame.getFrameBoundsOnScreen();
                drag(frame, bounds.right, bounds.bottom, bounds.left - 10_000, bounds.top - 10_000);
                assertEquals(30, frame.getDraft().widthPercent);
                assertEquals(25, frame.getDraft().heightPercent);
                assertFrameMatchesDraft(frame);
                assertCardCentered(frame);
                bounds = frame.getFrameBoundsOnScreen();
                drag(frame, bounds.centerX(), bounds.centerY(), -10_000, -10_000);
                assertEquals(0, frame.getDraft().horizontalPercent);
                assertEquals(0, frame.getDraft().verticalPercent);
                bounds = frame.getFrameBoundsOnScreen();
                drag(frame, bounds.right, bounds.bottom, 10_000, 10_000);
                assertEquals(95, frame.getDraft().widthPercent);
                assertEquals(95, frame.getDraft().heightPercent);
                assertTrue(frame.getAvailableBoundsOnScreen().contains(frame.getFrameBoundsOnScreen()));
                assertCardCentered(frame);
            });
        } finally { fixture.close(); }
    }

    @Test public void savePersistsExactPreviewRevokesConfirmationAndHidesEditor() {
        Fixture fixture = new Fixture();
        try {
            fixture.clearAndOpen();
            seedConfirmation(fixture.status);
            SmallWindowLayout[] requested = new SmallWindowLayout[1];
            fixture.instrumentation.runOnMainSync(() -> {
                SmallWindowFrameEditorView frame = fixture.activity.getFrameEditor();
                Rect bounds = frame.getFrameBoundsOnScreen();
                drag(frame, bounds.centerX(), bounds.centerY(), bounds.centerX() + bounds.width() * .10f,
                        bounds.centerY() - bounds.height() * .12f);
                requested[0] = frame.getDraft();
                assertFrameMatchesDraft(frame);
                click(fixture.activity, "保存");
            });
            fixture.instrumentation.waitForIdleSync();
            assertEquals(requested[0].encode(), fixture.preferences.getString(KEY, null));
            assertFalse(fixture.status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, true));
            assertFalse(fixture.status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, true));
            assertEquals("", fixture.status.getString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, null));
            assertTrue("Save removes the editor so its frame disappears", fixture.activity.isFinishing());
            fixture.open();
            fixture.instrumentation.runOnMainSync(() -> {
                assertEquals(requested[0], fixture.activity.getFrameEditor().getDraft());
                assertFrameMatchesDraft(fixture.activity.getFrameEditor());
            });
        } finally { fixture.close(); }
    }

    @Test public void cancelAndBackKeepPreviousLayoutAndConfirmation() {
        Fixture fixture = new Fixture();
        try {
            SmallWindowLayout saved = new SmallWindowLayout(55, 40, 0, 100);
            assertTrue(fixture.preferences.edit().putString(KEY, saved.encode()).commit());
            fixture.open();
            seedConfirmation(fixture.status);
            fixture.instrumentation.runOnMainSync(() -> {
                fixture.activity.getFrameEditor().resetDraft();
                click(fixture.activity, "取消");
            });
            fixture.instrumentation.waitForIdleSync();
            assertTrue(fixture.activity.isFinishing());
            assertEquals(saved.encode(), fixture.preferences.getString(KEY, null));
            fixture.open();
            fixture.instrumentation.runOnMainSync(() -> {
                assertEquals(saved, fixture.activity.getFrameEditor().getDraft());
                fixture.activity.getFrameEditor().resetDraft();
                fixture.activity.onBackPressed();
            });
            fixture.instrumentation.waitForIdleSync();
            assertTrue(fixture.activity.isFinishing());
            assertEquals(saved.encode(), fixture.preferences.getString(KEY, null));
            assertTrue(fixture.status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false));
        } finally { fixture.close(); }
    }

    @Test public void recreationRetainsUnsavedDraftAndResolvesCurrentDisplay() {
        Fixture fixture = new Fixture();
        try {
            fixture.clearAndOpen();
            SmallWindowLayout[] draft = new SmallWindowLayout[1];
            fixture.instrumentation.runOnMainSync(() -> {
                SmallWindowFrameEditorView frame = fixture.activity.getFrameEditor();
                Rect bounds = frame.getFrameBoundsOnScreen();
                drag(frame, bounds.right, bounds.bottom, bounds.right - bounds.width() * .15f,
                        bounds.bottom - bounds.height() * .15f);
                draft[0] = frame.getDraft();
            });
            Instrumentation.ActivityMonitor monitor = fixture.instrumentation.addMonitor(
                    SmallWindowFrameEditorActivity.class.getName(), null, false);
            try {
                fixture.instrumentation.runOnMainSync(fixture.activity::recreate);
                Activity recreated = monitor.waitForActivityWithTimeout(10_000);
                assertNotNull("Editor must recreate", recreated);
                fixture.activity = (SmallWindowFrameEditorActivity) recreated;
                fixture.awaitLayout();
            } finally { fixture.instrumentation.removeMonitor(monitor); }
            fixture.instrumentation.runOnMainSync(() -> {
                assertEquals(draft[0], fixture.activity.getFrameEditor().getDraft());
                assertFrameMatchesDraft(fixture.activity.getFrameEditor());
                assertCardCentered(fixture.activity.getFrameEditor());
            });
            assertFalse(fixture.preferences.contains(KEY));
        } finally { fixture.close(); }
    }

    @Test public void malformedLayoutIsRepairedOnlyOnExplicitSave() {
        Fixture fixture = new Fixture();
        try {
            String malformed = "70,60,not-a-position,50";
            assertTrue(fixture.preferences.edit().putString(KEY, malformed).commit());
            fixture.open();
            fixture.instrumentation.runOnMainSync(() ->
                    assertEquals(SmallWindowLayout.DEFAULT, fixture.activity.getFrameEditor().getDraft()));
            assertEquals(malformed, fixture.preferences.getString(KEY, null));
            fixture.instrumentation.runOnMainSync(() -> click(fixture.activity, "保存"));
            fixture.instrumentation.waitForIdleSync();
            assertEquals(SmallWindowLayout.DEFAULT.encode(), fixture.preferences.getString(KEY, null));
            assertTrue(fixture.activity.isFinishing());
        } finally { fixture.close(); }
    }

    private static void assertFrameMatchesDraft(SmallWindowFrameEditorView frame) {
        Rect area = frame.getAvailableBoundsOnScreen();
        SmallWindowLayout.Bounds expected = frame.getDraft().resolve(area.left, area.top, area.width(), area.height());
        assertEquals(new Rect(expected.left, expected.top, expected.right, expected.bottom), frame.getFrameBoundsOnScreen());
    }

    private static void assertCardCentered(SmallWindowFrameEditorView frame) {
        frame.layout(frame.getLeft(), frame.getTop(), frame.getRight(), frame.getBottom());
        Rect bounds = frame.getFrameBoundsOnScreen(), card = frame.getCardBoundsOnScreen();
        assertEquals(bounds.exactCenterX(), card.exactCenterX(), 1);
        assertEquals(bounds.exactCenterY(), card.exactCenterY(), 1);
        assertTrue(bounds.contains(card));
    }

    private static void drag(SmallWindowFrameEditorView frame, float fromX, float fromY, float toX, float toY) {
        int[] origin = new int[2];
        frame.getLocationOnScreen(origin);
        long time = SystemClock.uptimeMillis();
        dispatch(frame, time, time, MotionEvent.ACTION_DOWN, fromX - origin[0], fromY - origin[1]);
        dispatch(frame, time, time + 100, MotionEvent.ACTION_MOVE, toX - origin[0], toY - origin[1]);
        assertCardCentered(frame);
        dispatch(frame, time, time + 200, MotionEvent.ACTION_UP, toX - origin[0], toY - origin[1]);
        frame.layout(frame.getLeft(), frame.getTop(), frame.getRight(), frame.getBottom());
    }

    private static void dispatch(View view, long down, long now, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, now, action, x, y, 0);
        try { assertTrue("Frame gesture must be accepted", view.dispatchTouchEvent(event)); }
        finally { event.recycle(); }
    }

    private static void click(Activity activity, String label) {
        View button = findButton(activity.getWindow().getDecorView(), label);
        assertNotNull("Missing editor action: " + label, button);
        assertTrue(button.performClick());
    }

    private static View findButton(View view, String text) {
        if (view instanceof Button && text.contentEquals(((Button) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View result = findButton(group.getChildAt(index), text);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void seedConfirmation(SharedPreferences status) {
        assertTrue(status.edit().putBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, true)
                .putBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, true)
                .putString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, "previous-layout-confirmation").commit());
    }

    private static final class Fixture implements AutoCloseable {
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final SharedPreferences preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        final SharedPreferences status = context.getSharedPreferences(SystemServerConfigContract.RUNTIME_STATUS_PREFS,
                Context.MODE_PRIVATE);
        final Object original = preferences.getAll().get(KEY);
        final Map<String, ?> originalStatus = new HashMap<>(status.getAll());
        SmallWindowFrameEditorActivity activity;

        void clearAndOpen() { assertTrue(preferences.edit().remove(KEY).commit()); open(); }

        void open() {
            activity = (SmallWindowFrameEditorActivity) instrumentation.startActivitySync(new Intent(context,
                    SmallWindowFrameEditorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
            awaitLayout();
        }

        void awaitLayout() {
            long deadline = SystemClock.uptimeMillis() + 10_000;
            boolean[] laidOut = {false};
            do {
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> laidOut[0] = activity.getFrameEditor().getWidth() > 0
                        && !activity.getFrameEditor().getFrameBoundsOnScreen().isEmpty());
                if (laidOut[0]) return;
                SystemClock.sleep(50);
            } while (SystemClock.uptimeMillis() < deadline);
            fail("Real-screen frame did not finish layout");
        }

        @Override public void close() {
            if (activity != null) {
                instrumentation.runOnMainSync(activity::finishAndRemoveTask);
                instrumentation.waitForIdleSync();
            }
            SharedPreferences.Editor restore = preferences.edit().remove(KEY);
            if (original != null) put(restore, KEY, original);
            assertTrue(restore.commit());
            instrumentation.waitForIdleSync();
            SharedPreferences.Editor restoreStatus = status.edit().clear();
            for (Map.Entry<String, ?> entry : originalStatus.entrySet()) put(restoreStatus, entry.getKey(), entry.getValue());
            assertTrue(restoreStatus.commit());
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
        else throw new AssertionError("Unsupported original preference: " + key);
    }
}
