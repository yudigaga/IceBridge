package com.huanchengfly.icebridge.ui.theme;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import com.huanchengfly.icebridge.activities.ThemeSettingsActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.function.Predicate;
import static org.junit.Assert.*;

/** Exercises real rendering and Activity lifecycle, beyond the pure color/policy tests. */
@RunWith(AndroidJUnit4.class)
public class ThemeRenderingTest {
    @Test public void lightDarkSwitchKeepsContentAndColorFamily() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ThemePreferencesStore store = ThemePreferencesStore.get(instrumentation.getTargetContext());
        instrumentation.runOnMainSync(() -> {
            store.setRandomEnabled(false);
            store.setFixedSeedColor(0xff7e57c2);
            store.setAppearance(ThemePolicy.APPEARANCE_LIGHT);
        });
        Activity activity = openSettings(instrumentation);
        assertContentVisible(instrumentation);
        int seed = store.getSnapshot().getSeedColor();
        instrumentation.runOnMainSync(() -> store.setAppearance(ThemePolicy.APPEARANCE_DARK));
        instrumentation.waitForIdleSync();
        assertContentVisible(instrumentation);
        assertEquals(seed, store.getSnapshot().getSeedColor());
        instrumentation.runOnMainSync(() -> store.setAppearance(ThemePolicy.APPEARANCE_LIGHT));
        instrumentation.waitForIdleSync();
        assertContentVisible(instrumentation);
        assertEquals(seed, store.getSnapshot().getSeedColor());
        instrumentation.runOnMainSync(activity::finish);
    }

    @Test public void activityRecreationKeepsContentWithoutRotatingLaunchColor() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ThemePreferencesStore store = ThemePreferencesStore.get(instrumentation.getTargetContext());
        instrumentation.runOnMainSync(() -> {
            store.setAppearance(ThemePolicy.APPEARANCE_LIGHT);
            store.setRandomInterval(ThemePolicy.INTERVAL_LAUNCH);
            store.setRandomEnabled(true);
        });
        Activity activity = openSettings(instrumentation);
        assertContentVisible(instrumentation);
        int seed = store.getSnapshot().getSeedColor();
        instrumentation.runOnMainSync(activity::recreate);
        instrumentation.waitForIdleSync();
        assertContentVisible(instrumentation);
        assertEquals(seed, store.getSnapshot().getSeedColor());
    }

    @Test public void validCustomColorCanBeEnteredAndApplied() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ThemePreferencesStore store = prepareFixedColor(instrumentation, 0xff388e3c);
        Activity activity = openSettings(instrumentation);
        try {
            setCustomColorText(instrumentation, "#C54977");
            instrumentation.runOnMainSync(activity::recreate);
            instrumentation.waitForIdleSync();
            AccessibilityNodeInfo restoredInput = findVisibleNode(instrumentation,
                    node -> node.isEditable() && "#C54977".contentEquals(node.getText() == null ? "" : node.getText()));
            restoredInput.recycle();
            assertEquals("Editing must not apply the custom color until the button is clicked",
                    0xff388e3c, store.getSnapshot().getFixedSeedColor());
            AccessibilityNodeInfo button = findVisibleNode(instrumentation,
                    node -> "应用自定义颜色".contentEquals(node.getText() == null ? "" : node.getText()));
            try {
                assertTrue("The apply button must be enabled for a valid custom color", button.isEnabled());
                assertTrue("A real button click must apply the color", performClickOnNodeOrParent(button));
            } finally { button.recycle(); }
            instrumentation.waitForIdleSync();
            assertEquals(0xffc54977, store.getSnapshot().getFixedSeedColor());
            assertFalse(store.getSnapshot().isRandomEnabled());
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void invalidCustomColorShowsErrorWithoutChangingSavedColor() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        ThemePreferencesStore store = prepareFixedColor(instrumentation, 0xff388e3c);
        Activity activity = openSettings(instrumentation);
        try {
            setCustomColorText(instrumentation, "#GG0000");
            AccessibilityNodeInfo error = findVisibleNode(instrumentation,
                    node -> node.getText() != null && node.getText().toString().contains("请输入 #RRGGBB 格式"));
            error.recycle();
            AccessibilityNodeInfo button = findVisibleNode(instrumentation,
                    node -> "应用自定义颜色".contentEquals(node.getText() == null ? "" : node.getText()));
            try {
                AccessibilityNodeInfo clickTarget = findClickTarget(button);
                assertNotNull("The apply label must belong to a button", clickTarget);
                try {
                    assertFalse("Invalid input must disable the apply button: " + clickTarget, clickTarget.isEnabled());
                    clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } finally { clickTarget.recycle(); }
            } finally { button.recycle(); }
            instrumentation.waitForIdleSync();
            assertEquals("An invalid value must leave the saved color unchanged",
                    0xff388e3c, store.getSnapshot().getFixedSeedColor());
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    private ThemePreferencesStore prepareFixedColor(Instrumentation instrumentation, int color) {
        ThemePreferencesStore store = ThemePreferencesStore.get(instrumentation.getTargetContext());
        instrumentation.runOnMainSync(() -> {
            store.setRandomEnabled(false);
            store.setFixedSeedColor(color);
            store.setAppearance(ThemePolicy.APPEARANCE_LIGHT);
        });
        return store;
    }

    /** Accessibility SET_TEXT supplies the exact value without device keyboard transformations. */
    private void setCustomColorText(Instrumentation instrumentation, String value) {
        AccessibilityNodeInfo field = findVisibleNode(instrumentation, AccessibilityNodeInfo::isEditable);
        try {
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            assertTrue("The custom color field must accept user text",
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
        } finally { field.recycle(); }
        instrumentation.waitForIdleSync();
        // Compose may publish the new text on the next frame, after waitForIdleSync returns.
        // Do not scroll while waiting: inserting the validation message also changes the viewport.
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        String actual = null;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    AccessibilityNodeInfo updated = findNode(root, AccessibilityNodeInfo::isEditable);
                    if (updated != null) {
                        try { actual = updated.getText() == null ? "" : updated.getText().toString(); }
                        finally { updated.recycle(); }
                        if (value.equals(actual)) return;
                    }
                } finally { root.recycle(); }
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals("The custom color field must contain the exact entered value", value, actual);
    }

    private AccessibilityNodeInfo findVisibleNode(Instrumentation instrumentation,
                                                   Predicate<AccessibilityNodeInfo> predicate) {
        long deadline = SystemClock.uptimeMillis() + 10_000L;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    AccessibilityNodeInfo match = findNode(root, node -> node.isVisibleToUser() && predicate.test(node));
                    if (match != null) return match;
                    AccessibilityNodeInfo scroll = findNode(root, AccessibilityNodeInfo::isScrollable);
                    if (scroll != null) {
                        try { scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); }
                        finally { scroll.recycle(); }
                    }
                } finally { root.recycle(); }
            }
            instrumentation.waitForIdleSync();
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Expected theme control did not become visible");
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> predicate) {
        if (predicate.test(node)) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    AccessibilityNodeInfo result = findNode(child, predicate);
                    if (result != null) return result;
                } finally { child.recycle(); }
            }
        }
        return null;
    }

    private boolean performClickOnNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo clickTarget = findClickTarget(node);
        if (clickTarget == null) return false;
        try {
            return clickTarget.isEnabled() && clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } finally { clickTarget.recycle(); }
    }

    private AccessibilityNodeInfo findClickTarget(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        while (current != null) {
            AccessibilityNodeInfo parent = null;
            try {
                // SET_TEXT changes enabled state without replacing the semantics node ID.
                // Refresh ancestors so UiAutomation does not reuse the previous cached state.
                current.refresh();
                if (current.isClickable()) return AccessibilityNodeInfo.obtain(current);
                parent = current.getParent();
            } finally { current.recycle(); }
            current = parent;
        }
        return null;
    }

    private Activity openSettings(Instrumentation instrumentation) {
        Intent intent = new Intent(instrumentation.getTargetContext(), ThemeSettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        return activity;
    }

    private void assertContentVisible(Instrumentation instrumentation) {
        long deadline = SystemClock.uptimeMillis() + 10_000L;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try { if (containsText(root, "主题预览")) return; }
                finally { root.recycle(); }
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Theme page must keep visible content after a mode switch or Activity recreation");
    }

    private boolean containsText(AccessibilityNodeInfo node, String expected) {
        if (expected.contentEquals(node.getText() == null ? "" : node.getText())) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try { if (containsText(child, expected)) return true; }
                finally { child.recycle(); }
            }
        }
        return false;
    }
}
