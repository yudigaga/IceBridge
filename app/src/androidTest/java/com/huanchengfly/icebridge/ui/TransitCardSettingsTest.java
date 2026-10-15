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
import com.huanchengfly.icebridge.activities.EnhanceModeActivity;
import com.huanchengfly.icebridge.databases.AppConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.litepal.LitePal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.Assert.*;

/** Exercises the opt-in UI, stored configuration and the runtime-confirmation boundary together. */
@RunWith(AndroidJUnit4.class)
public class TransitCardSettingsTest {
    private static final String CARD_KEY = SystemServerConfigContract.KEY_TRANSIT_CARD_ENABLED;
    private static final String CARD_LABEL = "显示中转卡片";
    private static final String SMALL_WINDOW_KEY = SystemServerConfigContract.KEY_OPEN_LINKS_IN_SMALL_WINDOW;
    private static final String SMALL_WINDOW_LABEL = "小窗打开";
    private static final String SMALL_WINDOW_PACKAGES = SystemServerConfigContract.KEY_SMALL_WINDOW_PACKAGES;
    private static final String[] ROUTING_KEYS = {
            "enhance_mode_enabled", "xhs_resolve_recovery", "auto_open_links", "native_link_capture",
            "clipboard_auto_open", "payment_sdk_bridge", "only_frozen_link_targets",
            "link_browser_fallback", SystemServerConfigContract.KEY_AUTONOMOUS_RUNTIME,
            CARD_KEY, SMALL_WINDOW_KEY, SMALL_WINDOW_PACKAGES
    };

    @Test public void cardOptInSurvivesRecreationWithoutChangingRoutingAndRevokesStaleConfirmation() {
        exerciseOptIn(CARD_KEY, CARD_LABEL);
    }

    @Test public void smallWindowOptInSurvivesRecreationWithoutChangingCardOrRouting() {
        exerciseOptIn(SMALL_WINDOW_KEY, SMALL_WINDOW_LABEL);
    }

    @Test public void appSelectionSavesOnlyOnConfirmationAndKeepsInvisiblePackagesAfterRecreation() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        SharedPreferences status = context.getSharedPreferences(
                SystemServerConfigContract.RUNTIME_STATUS_PREFS, Context.MODE_PRIVATE);
        Object originalValue = preferences.getAll().get(SMALL_WINDOW_PACKAGES);
        Map<String, ?> originalStatus = new HashMap<>(status.getAll());
        String invisiblePackage = "io.github.icebridge.test.missing.selection";
        String selectedApp = "com.android.settings";
        Set<String> initial = Collections.singleton(invisiblePackage);
        Set<String> saved = new HashSet<>(Arrays.asList(invisiblePackage, selectedApp));
        Activity activity = null;
        try {
            assertTrue(preferences.edit().putStringSet(SMALL_WINDOW_PACKAGES, initial).commit());
            activity = openSettings(instrumentation);
            Map<String, Object> routingBefore = routingPreferences(preferences, SMALL_WINDOW_PACKAGES);
            seedConfirmedStatus(status);

            clickText(instrumentation, "小窗应用");
            searchApp(instrumentation, selectedApp);
            clickAppSelection(instrumentation, selectedApp);
            assertEquals("Draft selection must not affect routing before confirmation", initial,
                    preferences.getStringSet(SMALL_WINDOW_PACKAGES, Collections.emptySet()));
            clickText(instrumentation, "取消");
            assertEquals("Cancelling must discard the new checkmark", initial,
                    preferences.getStringSet(SMALL_WINDOW_PACKAGES, Collections.emptySet()));
            assertTrue("Cancelling must keep the previous confirmation valid",
                    status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, false));

            clickText(instrumentation, "小窗应用");
            searchApp(instrumentation, selectedApp);
            clickAppSelection(instrumentation, selectedApp);
            clickText(instrumentation, "确认");
            assertEquals("Saving must keep previously selected packages even when not installed", saved,
                    preferences.getStringSet(SMALL_WINDOW_PACKAGES, Collections.emptySet()));
            assertConfirmationRevoked(status);
            assertEquals("Editing the app list must not toggle other features", routingBefore,
                    routingPreferences(preferences, SMALL_WINDOW_PACKAGES));

            activity = recreateSettings(instrumentation, activity);
            assertEquals(saved, preferences.getStringSet(SMALL_WINDOW_PACKAGES, Collections.emptySet()));
            clickText(instrumentation, "小窗应用");
            searchApp(instrumentation, selectedApp);
            AccessibilityNodeInfo selected = findVisibleNode(instrumentation,
                    node -> node.isCheckable() && hasPackageText(node, selectedApp), false);
            try { assertTrue("The saved selection must be checked after Activity recreation", selected.isChecked()); }
            finally { selected.recycle(); }
            searchApp(instrumentation, invisiblePackage);
            AccessibilityNodeInfo invisible = findVisibleNode(instrumentation,
                    node -> node.isCheckable() && hasPackageText(node, invisiblePackage), false);
            try { assertTrue("A missing or hidden package must remain selected and editable", invisible.isChecked()); }
            finally { invisible.recycle(); }
            clickText(instrumentation, "取消");
        } finally {
            finishActivity(instrumentation, activity);
            SharedPreferences.Editor edit = preferences.edit().remove(SMALL_WINDOW_PACKAGES);
            if (originalValue != null) putPreference(edit, SMALL_WINDOW_PACKAGES, originalValue);
            edit.commit();
            instrumentation.waitForIdleSync();
            restoreStatus(status, originalStatus);
        }
    }

    private static void exerciseOptIn(String key, String label) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("preferences", Context.MODE_PRIVATE);
        SharedPreferences status = context.getSharedPreferences(
                SystemServerConfigContract.RUNTIME_STATUS_PREFS, Context.MODE_PRIVATE);
        Object originalValue = preferences.getAll().get(key);
        Map<String, ?> originalStatus = new HashMap<>(status.getAll());
        List<String> originalDatabaseValues = new ArrayList<>();
        for (AppConfig row : LitePal.where("key = ?", key).find(AppConfig.class)) {
            originalDatabaseValues.add(row.getValue());
        }
        Activity activity = null;
        try {
            preferences.edit().remove(key).commit();
            LitePal.deleteAll(AppConfig.class, "key = ?", key);
            activity = openSettings(instrumentation);

            assertChecked(instrumentation, label, false);
            assertFalse("An absent setting must leave the option disabled", preferences.getBoolean(key, false));
            Map<String, Object> routingBefore = routingPreferences(preferences, key);
            Map<String, String> databaseBefore = routingDatabase(key);

            seedConfirmedStatus(status);
            clickSwitch(instrumentation, label);
            assertChecked(instrumentation, label, true);
            assertTrue("Opting in must save the preference", preferences.getBoolean(key, false));
            assertEquals("1", databaseValue(key));
            assertConfirmationRevoked(status);
            assertEquals("Opting in must not change other features", routingBefore, routingPreferences(preferences, key));
            assertEquals(databaseBefore, routingDatabase(key));

            activity = recreateSettings(instrumentation, activity);
            assertChecked(instrumentation, label, true);
            assertTrue(preferences.getBoolean(key, false));

            seedConfirmedStatus(status);
            clickSwitch(instrumentation, label);
            assertChecked(instrumentation, label, false);
            assertFalse(preferences.getBoolean(key, true));
            assertEquals("0", databaseValue(key));
            assertConfirmationRevoked(status);
            assertEquals("Opting out must not change other features", routingBefore, routingPreferences(preferences, key));
            assertEquals(databaseBefore, routingDatabase(key));
        } finally {
            finishActivity(instrumentation, activity);
            SharedPreferences.Editor edit = preferences.edit().remove(key);
            if (originalValue != null) putPreference(edit, key, originalValue);
            edit.commit();
            LitePal.deleteAll(AppConfig.class, "key = ?", key);
            for (String value : originalDatabaseValues) new AppConfig(key, value).save();
            // Preference listeners invalidate acknowledgements asynchronously on the main thread.
            instrumentation.waitForIdleSync();
            restoreStatus(status, originalStatus);
        }
    }

    private static Activity openSettings(Instrumentation instrumentation) {
        Intent intent = new Intent(instrumentation.getTargetContext(), EnhanceModeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        return activity;
    }

    private static Activity recreateSettings(Instrumentation instrumentation, Activity previous) {
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                EnhanceModeActivity.class.getName(), null, false);
        try {
            instrumentation.runOnMainSync(previous::recreate);
            Activity recreated = monitor.waitForActivityWithTimeout(10_000L);
            assertNotNull("The settings Activity must be recreated", recreated);
            instrumentation.waitForIdleSync();
            return recreated;
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private static void finishActivity(Instrumentation instrumentation, Activity activity) {
        if (activity == null) return;
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
    }

    private static void restoreStatus(SharedPreferences status, Map<String, ?> values) {
        SharedPreferences.Editor restore = status.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) putPreference(restore, entry.getKey(), entry.getValue());
        restore.commit();
    }

    private static void searchApp(Instrumentation instrumentation, String packageName) {
        AccessibilityNodeInfo input = findVisibleNode(instrumentation, AccessibilityNodeInfo::isEditable, false);
        try {
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, packageName);
            assertTrue("The app picker must accept a package-name search",
                    input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
        } finally { input.recycle(); }
        instrumentation.waitForIdleSync();
        AccessibilityNodeInfo updated = findVisibleNode(instrumentation,
                node -> node.isEditable() && packageName.contentEquals(node.getText() == null ? "" : node.getText()), false);
        updated.recycle();
    }

    private static void clickAppSelection(Instrumentation instrumentation, String packageName) {
        AccessibilityNodeInfo app = findVisibleNode(instrumentation,
                node -> node.isCheckable() && hasPackageText(node, packageName), false);
        try {
            assertFalse("A cancelled draft must not preselect the searched app", app.isChecked());
            assertTrue("The app row must be selectable", app.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        } finally { app.recycle(); }
        instrumentation.waitForIdleSync();
        AccessibilityNodeInfo checked = findVisibleNode(instrumentation,
                node -> node.isCheckable() && node.isChecked() && hasPackageText(node, packageName), false);
        checked.recycle();
    }

    private static boolean hasPackageText(AccessibilityNodeInfo node, String packageName) {
        CharSequence text = node.getText();
        if (text != null) {
            for (String line : text.toString().split("\\n")) if (packageName.equals(line.trim())) return true;
        }
        // The checkable Compose row owns separate TextView children for the app name and package.
        // Exact matching within this subtree also excludes packages with the same prefix.
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try { if (hasPackageText(child, packageName)) return true; }
                finally { child.recycle(); }
            }
        }
        return false;
    }

    private static void clickText(Instrumentation instrumentation, String label) {
        AccessibilityNodeInfo text = findVisibleNode(instrumentation,
                node -> label.contentEquals(node.getText() == null ? "" : node.getText()), true);
        AccessibilityNodeInfo current = text;
        boolean clicked = false;
        while (current != null) {
            AccessibilityNodeInfo parent = null;
            try {
                current.refresh();
                if (current.isClickable()) {
                    clicked = current.isEnabled() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    break;
                }
                parent = current.getParent();
            } finally { current.recycle(); }
            current = parent;
        }
        assertTrue("The settings control must accept a click: " + label, clicked);
        instrumentation.waitForIdleSync();
    }

    private static AccessibilityNodeInfo findVisibleNode(Instrumentation instrumentation,
                                                        Predicate<AccessibilityNodeInfo> predicate,
                                                        boolean scrollForward) {
        long deadline = SystemClock.uptimeMillis() + 10_000L;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    AccessibilityNodeInfo match = findNode(root, node -> node.isVisibleToUser() && predicate.test(node));
                    if (match != null) return match;
                    if (scrollForward) {
                        AccessibilityNodeInfo scroll = findNode(root, AccessibilityNodeInfo::isScrollable);
                        if (scroll != null) {
                            try { scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); }
                            finally { scroll.recycle(); }
                        }
                    }
                } finally { root.recycle(); }
            }
            instrumentation.waitForIdleSync();
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Expected app-selection control did not become visible");
    }

    private static void seedConfirmedStatus(SharedPreferences status) {
        assertTrue(status.edit().putBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, true)
                .putBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, true)
                .putString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, "test-previous-confirmation").commit());
    }

    private static void assertConfirmationRevoked(SharedPreferences status) {
        assertFalse("A changed option must await fresh system confirmation",
                status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_READY, true));
        assertFalse("The old durable acknowledgement must not be reused",
                status.getBoolean(SystemServerConfigContract.KEY_RUNTIME_PERSISTED_ACK, true));
        assertEquals("", status.getString(SystemServerConfigContract.KEY_CONFIG_FINGERPRINT, null));
    }

    private static Map<String, Object> routingPreferences(SharedPreferences preferences, String changedKey) {
        Map<String, ?> all = preferences.getAll();
        Map<String, Object> result = new HashMap<>();
        for (String key : ROUTING_KEYS) if (!key.equals(changedKey)) result.put(key, all.get(key));
        return result;
    }

    private static Map<String, String> routingDatabase(String changedKey) {
        Map<String, String> result = new HashMap<>();
        for (String key : ROUTING_KEYS) {
            if (key.equals(changedKey)) continue;
            AppConfig row = LitePal.where("key = ?", key).findFirst(AppConfig.class);
            result.put(key, row == null ? null : row.getValue());
        }
        return result;
    }

    private static String databaseValue(String key) {
        AppConfig row = LitePal.where("key = ?", key).findFirst(AppConfig.class);
        assertNotNull("The setting must also reach AppConfig", row);
        return row.getValue();
    }

    @SuppressWarnings("unchecked")
    private static void putPreference(SharedPreferences.Editor edit, String key, Object value) {
        if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
        else if (value instanceof String) edit.putString(key, (String) value);
        else if (value instanceof Integer) edit.putInt(key, (Integer) value);
        else if (value instanceof Long) edit.putLong(key, (Long) value);
        else if (value instanceof Float) edit.putFloat(key, (Float) value);
        else if (value instanceof Set) edit.putStringSet(key, (Set<String>) value);
        else throw new AssertionError("Unexpected preference type for " + key);
    }

    private static void assertChecked(Instrumentation instrumentation, String label, boolean expected) {
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        boolean actual;
        do {
            AccessibilityNodeInfo control = findVisibleSwitch(instrumentation, label);
            try { actual = control.isChecked(); }
            finally { control.recycle(); }
            if (actual == expected) return;
            instrumentation.waitForIdleSync();
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals("The switch must show the persisted opt-in state", expected, actual);
    }

    private static void clickSwitch(Instrumentation instrumentation, String label) {
        AccessibilityNodeInfo control = findVisibleSwitch(instrumentation, label);
        try {
            assertTrue("The opt-in switch must accept a real user click",
                    control.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        } finally { control.recycle(); }
        instrumentation.waitForIdleSync();
    }

    private static AccessibilityNodeInfo findVisibleSwitch(Instrumentation instrumentation, String label) {
        long deadline = SystemClock.uptimeMillis() + 10_000L;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                try {
                    AccessibilityNodeInfo control = findSwitch(root, label);
                    if (control != null) return control;
                    AccessibilityNodeInfo scroll = findNode(root, AccessibilityNodeInfo::isScrollable);
                    if (scroll != null) {
                        try { scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); }
                        finally { scroll.recycle(); }
                    }
                } finally { root.recycle(); }
            }
            instrumentation.waitForIdleSync();
            SystemClock.sleep(100L);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("The setting switch did not become visible: " + label);
    }

    /** Select the smallest semantics subtree containing both this label and its checkable control. */
    private static AccessibilityNodeInfo findSwitch(AccessibilityNodeInfo node, String expectedLabel) {
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    AccessibilityNodeInfo result = findSwitch(child, expectedLabel);
                    if (result != null) return result;
                } finally { child.recycle(); }
            }
        }
        AccessibilityNodeInfo label = findNode(node,
                item -> item.isVisibleToUser() && expectedLabel.contentEquals(item.getText() == null ? "" : item.getText()));
        if (label == null) return null;
        label.recycle();
        return findNode(node, item -> item.isVisibleToUser() && item.isCheckable() && item.isClickable());
    }

    private static AccessibilityNodeInfo findNode(AccessibilityNodeInfo node,
                                                   Predicate<AccessibilityNodeInfo> predicate) {
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
}
