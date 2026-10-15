package com.huanchengfly.icebridge.android16;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;

import com.huanchengfly.icebridge.BuildConfig;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class SystemBrowserFallbackTest {
    @Test public void uniqueRealBrowserWinsOverDeepLinkAppsAndOwnPackage() {
        ResolveInfo deepLink = activity("com.example.target", false);
        ResolveInfo self = activity(BuildConfig.APPLICATION_ID, true);
        ResolveInfo browser = activity("com.example.browser", true);
        assertEquals(component(browser), SystemBrowserFallback.selectBrowser(
                Arrays.asList(deepLink, self, browser), null, null));
    }

    @Test public void multipleBrowsersWithoutConfiguredDefaultAreNotGuessed() {
        assertNull(SystemBrowserFallback.selectBrowser(Arrays.asList(
                activity("com.browser.one", true), activity("com.browser.two", true)), null, null));
        assertNull(SystemBrowserFallback.selectBrowser(Arrays.asList(
                activity("com.browser.one", true), activity("com.browser.two", true)), "com.nonbrowser.app", null));
    }

    @Test public void configuredDefaultMustBeAnEligibleBrowserForThisUrl() {
        ResolveInfo first = activity("com.browser.one", true);
        ResolveInfo second = activity("com.browser.two", true);
        assertEquals(component(second), SystemBrowserFallback.selectBrowser(Arrays.asList(first, second),
                "com.browser.two", null));
        ResolveInfo deepLink = activity("com.default.target", false);
        assertNull(SystemBrowserFallback.selectBrowser(Arrays.asList(first, second, deepLink),
                "com.default.target", null));
    }

    @Test public void failedBrowserTargetAndDisabledOrPrivateActivitiesAreExcluded() {
        ResolveInfo failed = activity("com.browser.failed", true);
        ResolveInfo disabled = activity("com.browser.disabled", true);
        disabled.activityInfo.applicationInfo.enabled = false;
        ResolveInfo privateActivity = activity("com.browser.private", true);
        privateActivity.activityInfo.exported = false;
        assertNull(SystemBrowserFallback.selectBrowser(Arrays.asList(failed, disabled, privateActivity),
                "com.browser.failed", "com.browser.failed"));
    }

    @Test public void duplicateResolutionRowsDoNotInventMultipleBrowsers() {
        ResolveInfo browser = activity("com.browser.one", true);
        assertEquals(component(browser), SystemBrowserFallback.selectBrowser(Arrays.asList(browser, browser), null, null));
        assertNull(SystemBrowserFallback.selectBrowser(Collections.emptyList(), null, null));
    }

    @Test public void multipleActivitiesInDefaultBrowserRemainAmbiguous() {
        ResolveInfo first = activity("com.browser.one", true);
        ResolveInfo second = activity("com.browser.one", true);
        second.activityInfo.name = "com.browser.one.OtherActivity";
        assertNull(SystemBrowserFallback.selectBrowser(Arrays.asList(first, second), "com.browser.one", null));
    }

    @Test public void browserIntentIsExplicitAndContainsOnlyThePublicWebRequest() {
        ComponentName browser = new ComponentName("com.browser.one", "com.browser.one.MainActivity");
        Intent intent = SystemBrowserFallback.buildBrowserIntent("https://example.com/item?q=1", browser);
        assertEquals(browser, intent.getComponent());
        assertNull(intent.getPackage());
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertEquals("https://example.com/item?q=1", intent.getDataString());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.getFlags());
        assertNull(intent.getExtras());
        assertNull(intent.getClipData());
        assertNull(intent.getSelector());
    }

    @Test public void ownTargetAndNonWebOrMalformedUrlsCannotBeBuilt() {
        ComponentName browser = new ComponentName("com.browser.one", "com.browser.one.MainActivity");
        expectFailure(() -> SystemBrowserFallback.buildBrowserIntent("https://example.com", new ComponentName(
                BuildConfig.APPLICATION_ID, "com.example.AnyActivity")));
        String[] rejected = {"content://private/item", "file:///private/item", "intent://item#Intent;end",
                "javascript:alert(1)", "https:", "https:///", "https://example.com/a b", "https://example.com/\n"};
        for (String url : rejected) expectFailure(() -> SystemBrowserFallback.buildBrowserIntent(url, browser));
    }

    private static ResolveInfo activity(String packageName, boolean browser) {
        ResolveInfo result = new ResolveInfo();
        try { ResolveInfo.class.getField("handleAllWebDataURI").setBoolean(result, browser); }
        catch (ReflectiveOperationException unavailable) { throw new AssertionError(unavailable); }
        result.isDefault = true;
        result.activityInfo = new ActivityInfo();
        result.activityInfo.packageName = packageName;
        result.activityInfo.name = packageName + ".MainActivity";
        result.activityInfo.enabled = true;
        result.activityInfo.exported = true;
        result.activityInfo.applicationInfo = new ApplicationInfo();
        result.activityInfo.applicationInfo.packageName = packageName;
        result.activityInfo.applicationInfo.enabled = true;
        return result;
    }

    private static ComponentName component(ResolveInfo info) {
        return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
    }

    private static void expectFailure(Runnable runnable) {
        try { runnable.run(); fail("unexpected browser request accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
