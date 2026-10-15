package com.huanchengfly.icebridge.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.huanchengfly.icebridge.SystemServerConfigContract;
import com.huanchengfly.icebridge.activities.SmallWindowSettingsActivity;
import com.huanchengfly.icebridge.automation.SmallWindowLayout;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** Verifies draft geometry, the visible preview and the durable system-confirmation boundary. */
@RunWith(AndroidJUnit4.class)
public class SmallWindowLayoutSettingsTest {
    private static final String KEY = SystemServerConfigContract.KEY_SMALL_WINDOW_LAYOUT;

    @Test public void previewTracksAllAdjustmentsAndRecreationWithoutApplyingUntilSaved() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        SharedPreferences status = context.getSharedPreferences(
                SystemServerConfigContract.RUNTIME_STATUS_PREFS, Context.MODE_PRIVATE);
        Object original = preferences.getAll().get(KEY);
        Map<String, ?> originalStatus = new HashMap<>(status.getAll());
        Activity activity = null;
        try {
            assertTrue(preferences.edit().remove(KEY).commit());
            activity = open(instrumentation);
            seedConfirmation(status);
            assertPreview(instrumentation, SmallWindowLayout.DEFAULT);
            assertFalse("Opening a preview must not write a new preference", preferences.contains(KEY));

            setProgress(instrumentation, "小窗宽度", 85);
            setProgress(instrumentation, "小窗高度", 45);
            setProgress(instrumentation, "水平位置", 15);
            setProgress(instrumentation, "垂直位置", 80);
            SmallWindowLayout requested = new SmallWindowLayout(85, 45, 15, 80);
            assertPreviewAlreadyVisible(instrumentation);
            assertPreview(instrumentation, requested);
            assertFalse("Preview adjustments must remain a draft", preferences.contains(KEY));
            assertTrue("An unsaved draft must keep the acknowledged runtime settings valid",
                    status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false));

            activity = recreate(instrumentation, activity);
            assertPreview(instrumentation, requested);
            assertFalse("Recreation must retain the draft without saving it", preferences.contains(KEY));
            clickText(instrumentation, "保存设置");
            assertEquals(requested.encode(), preferences.getString(KEY, null));
            assertConfirmationRevoked(status);

            activity = recreate(instrumentation, activity);
            assertPreview(instrumentation, requested);
            assertEquals(requested.encode(), preferences.getString(KEY, null));
            seedConfirmation(status);
            clickText(instrumentation, "恢复默认");
            assertPreview(instrumentation, SmallWindowLayout.DEFAULT);
            assertEquals("Reset also remains a draft until Save", requested.encode(), preferences.getString(KEY, null));
            assertTrue(status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false));
            clickText(instrumentation, "保存设置");
            assertEquals(SmallWindowLayout.DEFAULT.encode(), preferences.getString(KEY, null));
            assertConfirmationRevoked(status);
        } finally {
            finish(instrumentation, activity);
            restorePreference(preferences, original);
            instrumentation.waitForIdleSync();
            restorePreferences(status, originalStatus);
        }
    }

    @Test public void leavingAnUnsavedPreviewKeepsThePreviouslySavedWindow() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        SharedPreferences status = context.getSharedPreferences(
                SystemServerConfigContract.RUNTIME_STATUS_PREFS, Context.MODE_PRIVATE);
        Object original = preferences.getAll().get(KEY);
        Map<String, ?> originalStatus = new HashMap<>(status.getAll());
        SmallWindowLayout saved = new SmallWindowLayout(55, 40, 0, 100);
        Activity activity = null;
        try {
            assertTrue(preferences.edit().putString(KEY, saved.encode()).commit());
            activity = open(instrumentation);
            seedConfirmation(status);
            setProgress(instrumentation, "小窗宽度", 95);
            assertPreview(instrumentation, new SmallWindowLayout(95, 40, 0, 100));
            finish(instrumentation, activity);
            activity = null;
            assertEquals(saved.encode(), preferences.getString(KEY, null));
            assertTrue(status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false));
            activity = open(instrumentation);
            assertPreview(instrumentation, saved);
        } finally {
            finish(instrumentation, activity);
            restorePreference(preferences, original);
            instrumentation.waitForIdleSync();
            restorePreferences(status, originalStatus);
        }
    }

    @Test public void malformedSavedLayoutCanBeRepairedWithoutMakingAnotherAdjustment() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        SharedPreferences status = context.getSharedPreferences(
                SystemServerConfigContract.RUNTIME_STATUS_PREFS, Context.MODE_PRIVATE);
        Object original = preferences.getAll().get(KEY);
        Map<String, ?> originalStatus = new HashMap<>(status.getAll());
        String malformed = "70,60,not-a-position,50";
        Activity activity = null;
        try {
            assertTrue(preferences.edit().putString(KEY, malformed).commit());
            activity = open(instrumentation);
            assertPreview(instrumentation, SmallWindowLayout.DEFAULT);
            assertEquals("Opening the editor must not silently overwrite the malformed preference",
                    malformed, preferences.getString(KEY, null));
            seedConfirmation(status);
            clickText(instrumentation, "保存设置");
            assertEquals("Save must repair the malformed preference even when the preview is unchanged",
                    SmallWindowLayout.DEFAULT.encode(), preferences.getString(KEY, null));
            assertConfirmationRevoked(status);
            activity = recreate(instrumentation, activity);
            assertPreview(instrumentation, SmallWindowLayout.DEFAULT);
        } finally {
            finish(instrumentation, activity);
            restorePreference(preferences, original);
            instrumentation.waitForIdleSync();
            restorePreferences(status, originalStatus);
        }
    }

    private static Activity open(Instrumentation instrumentation) {
        Activity activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),
                SmallWindowSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrumentation.waitForIdleSync();
        return activity;
    }

    private static Activity recreate(Instrumentation instrumentation, Activity previous) {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                SmallWindowSettingsActivity.class.getName(), null, false);
        try {
            instrumentation.runOnMainSync(previous::recreate);
            Activity activity = monitor.waitForActivityWithTimeout(10_000L);
            assertNotNull("The layout editor must recreate", activity);
            instrumentation.waitForIdleSync();
            return activity;
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private static void finish(Instrumentation instrumentation, Activity activity) {
        if (activity == null) return;
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
    }

    private static void setProgress(Instrumentation instrumentation, String label, int value) {
        AccessibilityNodeInfo slider = findVisible(instrumentation,
                node -> label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())
                        && node.getRangeInfo() != null, true);
        try {
            Bundle arguments = new Bundle();
            arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value);
            assertTrue("The slider must accept an adjustment: " + label,
                    slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), arguments));
        } finally { slider.recycle(); }
        instrumentation.waitForIdleSync();
    }

    private static void assertPreview(Instrumentation instrumentation, SmallWindowLayout layout) {
        String expected = "宽度" + layout.widthPercent + "%，高度" + layout.heightPercent
                + "%，水平" + layout.horizontalPercent + "%，垂直" + layout.verticalPercent + "%";
        AccessibilityNodeInfo preview = findVisible(instrumentation,
                node -> "小窗布局预览".contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())
                        && expected.contentEquals(node.getStateDescription() == null ? "" : node.getStateDescription()), false);
        try { assertEquals(expected, String.valueOf(preview.getStateDescription())); }
        finally { preview.recycle(); }
    }

    /** Deliberately does not scroll: the preview must stay visible beside the last adjustment. */
    private static void assertPreviewAlreadyVisible(Instrumentation instrumentation) {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        assertNotNull("The layout editor must remain active", root);
        try {
            AccessibilityNodeInfo preview = find(root, node -> node.isVisibleToUser()
                    && "小窗布局预览".contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription()));
            assertNotNull("The preview must remain visible while adjusting the bottom slider", preview);
            preview.recycle();
        } finally { root.recycle(); }
    }

    private static void clickText(Instrumentation instrumentation, String label) {
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        boolean clicked = false;
        do {
            AccessibilityNodeInfo node = findVisible(instrumentation,
                    item -> label.contentEquals(item.getText() == null ? "" : item.getText()), true);
            while (node != null) {
                AccessibilityNodeInfo parent = null;
                try {
                    // Compose can update enabled state without replacing the semantics node ID.
                    // Read each ancestor again before deciding whether it can accept a click.
                    if (node.refresh() && node.isClickable()) {
                        clicked = node.isEnabled() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        break;
                    }
                    parent = node.getParent();
                } finally { node.recycle(); }
                node = parent;
            }
            // A successful action must never be repeated; only rejected actions may be retried.
            if (clicked) break;
            instrumentation.waitForIdleSync();
            SystemClock.sleep(50L);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("The layout control must accept a click: " + label, clicked);
        instrumentation.waitForIdleSync();
    }

    private static AccessibilityNodeInfo findVisible(Instrumentation instrumentation,
                                                      Predicate<AccessibilityNodeInfo> predicate, boolean forward) {
        long deadline = SystemClock.uptimeMillis() + 10_000L;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    AccessibilityNodeInfo result = find(root, node -> node.isVisibleToUser() && predicate.test(node));
                    if (result != null) return result;
                    AccessibilityNodeInfo scroll = find(root, AccessibilityNodeInfo::isScrollable);
                    if (scroll != null) {
                        try { scroll.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); }
                        finally { scroll.recycle(); }
                    }
                } finally { root.recycle(); }
            }
            instrumentation.waitForIdleSync();
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The layout control or expected preview did not become visible");
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> predicate) {
        if (predicate.test(node)) return AccessibilityNodeInfo.obtain(node);
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                try {
                    AccessibilityNodeInfo result = find(child, predicate);
                    if (result != null) return result;
                } finally { child.recycle(); }
            }
        }
        return null;
    }

    private static void seedConfirmation(SharedPreferences status) {
        assertTrue(status.edit().putBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, true)
                .putBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, true)
                .putString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, "previous-layout-confirmation").commit());
    }

    private static void assertConfirmationRevoked(SharedPreferences status) {
        assertFalse(status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, true));
        assertFalse(status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, true));
        assertEquals("", status.getString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, null));
    }

    private static void restorePreference(SharedPreferences preferences, Object original) {
        SharedPreferences.Editor editor = preferences.edit().remove(KEY);
        if (original != null) put(editor, KEY, original);
        assertTrue(editor.commit());
    }

    private static void restorePreferences(SharedPreferences preferences, Map<String, ?> original) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) put(editor, entry.getKey(), entry.getValue());
        assertTrue(editor.commit());
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
