package com.huanchengfly.icebridge.android16;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.os.Build;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.huanchengfly.icebridge.automation.SmallWindowLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Uses an application token to verify the same native card without asking for overlay permission. */
@RunWith(AndroidJUnit4.class)
public class SystemTransitRenderingTest {
    @Test public void cardTracksActualTaskMovesAndResizesIndependentlyOfDisplayChanges() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        TransitPreviewActivity activity = openSmallWindow(instrumentation, false, SmallWindowLayout.DEFAULT);
        try {
            assertCompactCard(instrumentation, activity, "正在解冻");
            awaitFocusedAndLaidOut(instrumentation, activity);
            AtomicReference<Rect> actual = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                Rect available = SystemWindowLayout.availableBounds(activity);
                Rect first = new Rect(available.left + available.width() / 12,
                        available.top + available.height() / 10,
                        available.left + available.width() * 7 / 12,
                        available.top + available.height() * 6 / 10);
                actual.set(first);
                activity.show(1, SystemTransitPresenter.Stage.OPENING, 0xff7e57c2, "light");
                activity.targetBounds(1, 7, 11, first);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> assertActualPlacement(activity.card(), actual.get()));
            instrumentation.runOnMainSync(() -> {
                Rect available = SystemWindowLayout.availableBounds(activity);
                Rect resized = new Rect(available.right - available.width() / 3,
                        available.bottom - available.height() / 3, available.right, available.bottom);
                actual.set(resized);
                activity.targetBounds(1, 7, 11, resized);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertActualPlacement(activity.card(), actual.get());
                activity.targetDrawn(1);
                activity.targetBounds(1, 7, 11, new Rect(0, 0, 200, 300));
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> assertNull("A late move must never recreate a dismissed card",
                    activity.card()));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    private static void assertActualPlacement(View card, Rect area) {
        assertNotNull(card);
        int[] location = new int[2];
        card.getLocationOnScreen(location);
        assertEquals(area.centerX(), location[0] + card.getWidth() / 2, 2);
        assertEquals(area.centerY(), location[1] + card.getHeight() / 2, 2);
        assertTrue(card.getWidth() <= area.width());
        assertTrue(card.getHeight() <= area.height());
    }

    @Test public void cardUsesNativeVisibleCoordinatesWithoutApplyingMiuiScaleASecondTime() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        TransitPreviewActivity activity = openSmallWindow(instrumentation, false, SmallWindowLayout.DEFAULT);
        try {
            assertCompactCard(instrumentation, activity, "正在解冻");
            awaitFocusedAndLaidOut(instrumentation, activity);
            // Preserve the phone regression's asymmetric placement on any test display.
            // The observer supplies visible coordinates; the renderer must not scale them again.
            Rect available = SystemWindowLayout.availableBounds(activity);
            int left = available.left + available.width() / 8;
            int top = available.top + available.height() / 9;
            Rect logical = new Rect(left, top, left + available.width() * 3 / 4,
                    top + available.height() * 3 / 5);
            Rect visible = new Rect(left, top, left + Math.round(logical.width() * 0.7f),
                    top + Math.round(logical.height() * 0.7f));
            instrumentation.runOnMainSync(() -> {
                activity.show(1, SystemTransitPresenter.Stage.OPENING, 0xff7e57c2, "light");
                activity.targetBounds(1, 7, 11, visible);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertActualPlacement(activity.card(), visible);
                activity.targetDrawn(1);
                activity.targetBounds(1, 7, 11, logical);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> assertNull(
                    "A late native scale update must not recreate the dismissed card", activity.card()));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void smallWindowCardIsSmallerAndCenteredInOffCenterTargetBounds() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        SmallWindowLayout layout = new SmallWindowLayout(60, 50, 100, 0);
        TransitPreviewActivity activity = openSmallWindow(instrumentation, false, layout);
        try {
            assertCompactCard(instrumentation, activity, "正在解冻");
            awaitFocusedAndLaidOut(instrumentation, activity);
            instrumentation.runOnMainSync(() -> {
                assertCardPlacement(activity, layout);
                float density = activity.getResources().getDisplayMetrics().density;
                assertEquals(156, activity.card().getWidth() / density, 1);
                assertEquals(72, activity.card().getHeight() / density, 1);
                TransitFlowView flow = findFlow(activity.card());
                assertEquals("Small-window icons should be scaled together with the moving line",
                        27, flow.sourceIconBounds().width() / density, 1);
                assertFlowGeometryInsideView(flow);
                activity.show(1, SystemTransitPresenter.Stage.OPENING, 0xff7e57c2, "dark", null);
                activity.submitted(1);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertCardPlacement(activity, layout);
                assertEquals(156, activity.card().getWidth()
                        / activity.getResources().getDisplayMetrics().density, 1);
                assertEquals(TransitSessionPolicy.Palette.fromSeed(0xff7e57c2, true).card,
                        ((GradientDrawable) activity.card().getBackground()).getColor().getDefaultColor());
                activity.targetDrawn(1);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> assertNull(activity.card()));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void minimumSmallWindowKeepsScaledCardAndIconsInsideTarget() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        SmallWindowLayout layout = new SmallWindowLayout(30, 25, 0, 100);
        TransitPreviewActivity activity = openSmallWindow(instrumentation, true, layout);
        AtomicReference<TransitFlowView> rendered = new AtomicReference<>();
        try {
            assertCompactCard(instrumentation, activity, "正在解冻");
            awaitFocusedAndLaidOut(instrumentation, activity);
            instrumentation.runOnMainSync(() -> {
                assertCardPlacement(activity, layout);
                Rect target = SystemWindowLayout.resolve(activity, layout);
                View card = activity.card();
                assertTrue(card.getWidth() <= target.width());
                assertTrue(card.getHeight() <= target.height());
                TransitFlowView flow = findFlow(card);
                assertFlowGeometryInsideView(flow);
                rendered.set(flow);
                assertFalse(containsVisibleText(card));
                assertTrue(activity.hasWindowFocus());
                assertSame(activity.backgroundView(), activity.getCurrentFocus());
            });
            awaitFlowFrames(instrumentation, rendered.get());
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void compactCardContainsOnlyTheAppFlowAndNoVisibleText() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        TransitPreviewActivity activity = open(instrumentation, false);
        try {
            assertCompactCard(instrumentation, activity, "正在解冻");
            awaitFocusedAndLaidOut(instrumentation, activity);
            instrumentation.runOnMainSync(() -> {
                ViewGroup card = (ViewGroup) activity.card();
                float density = activity.getResources().getDisplayMetrics().density;
                assertEquals("The card should be 208 dp wide", 208, card.getWidth() / density, 1);
                assertEquals("The card should be 96 dp high", 96, card.getHeight() / density, 1);
                assertEquals("Only the icon and motion drawing surface should be present", 1, card.getChildCount());
                assertTrue(card.getChildAt(0) instanceof TransitFlowView);
                assertEquals("Compact icons and flow should be 80 dp high", 80,
                        card.getChildAt(0).getHeight() / density, 1);
                assertFalse("Titles, app names, status text and branding must not be visible",
                        containsVisibleText(card));
            });
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void flowShowsBothAppsAnimatesAndStopsAfterTargetDraw() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        TransitPreviewActivity activity = open(instrumentation, false);
        AtomicReference<TransitFlowView> renderedFlow = new AtomicReference<>();
        try {
            assertCompactCard(instrumentation, activity, "正在解冻");
            String expectedSourceLabel = activity.getPackageManager().getApplicationLabel(
                    activity.getPackageManager().getApplicationInfo("com.android.settings", 0)).toString();
            instrumentation.runOnMainSync(() -> {
                TransitFlowView flow = findFlow(activity.card());
                assertNotNull("The native card must contain the app-to-app flow", flow);
                renderedFlow.set(flow);
                assertFlowIdentity(flow);
                assertEquals("The source label must come from installed package metadata",
                        expectedSourceLabel, flow.sourceLabel());
            });
            TransitFlowView flow = renderedFlow.get();
            awaitFlowFrames(instrumentation, flow);
            instrumentation.runOnMainSync(() -> {
                assertTrue("Visible progress must animate", flow.animationRunning());
                activity.show(1, SystemTransitPresenter.Stage.OPENING, 0xff7e57c2, "light");
                activity.submitted(1);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                View card = activity.card();
                assertSame("Changing stage must reuse the existing app-to-app flow",
                        flow, findFlow(card));
                assertFalse("Changing stage must not insert visible status text", containsVisibleText(card));
                assertTrue("The phase still needs an accessible announcement",
                        card.getContentDescription().toString().contains("正在打开"));
                activity.targetDrawn(1);
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertNull("The card must close after the actual target draw", activity.card());
                assertFalse("Removing the card must stop its frame loop", flow.animationRunning());
            });
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void unknownPackagesStillShowTwoUsableFallbackIcons() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        TransitPreviewActivity activity = open(instrumentation, false,
                "com.icebridge.preview.absent.source", "com.icebridge.preview.absent.target");
        try {
            assertCompactCard(instrumentation, activity, "正在解冻");
            instrumentation.runOnMainSync(() -> {
                TransitFlowView flow = findFlow(activity.card());
                assertNotNull("Missing package metadata must not remove the flow", flow);
                assertFlowIdentity(flow);
            });
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void nativeCardAnnouncesBothStagesAndWaitsForActualTargetDraw() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        TransitPreviewActivity activity = open(instrumentation, false);
        try {
            assertCompactCard(instrumentation, activity, "正在解冻");
            instrumentation.runOnMainSync(() -> {
                activity.show(1, SystemTransitPresenter.Stage.OPENING, 0xff7e57c2, "light");
                activity.submitted(1);
            });
            instrumentation.waitForIdleSync();
            assertCompactCard(instrumentation, activity, "正在打开");
            instrumentation.runOnMainSync(() -> activity.targetDrawn(1));
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> assertNull(activity.card()));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void oldDrawCannotDismissReplacementAndFailureRemovesIt() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        TransitPreviewActivity activity = open(instrumentation, false);
        try {
            assertCompactCard(instrumentation, activity, "正在解冻");
            instrumentation.runOnMainSync(() -> {
                activity.show(2, SystemTransitPresenter.Stage.OPENING, 0xff00796b, "light");
                activity.targetDrawn(1);
            });
            instrumentation.waitForIdleSync();
            assertCompactCard(instrumentation, activity, "正在打开");
            instrumentation.runOnMainSync(() -> activity.fail(2));
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> assertNull(activity.card()));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void darkAndLightUseDifferentReadableSurfacesFromSameSeed() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        TransitPreviewActivity activity = open(instrumentation, false);
        AtomicInteger light = new AtomicInteger();
        AtomicInteger dark = new AtomicInteger();
        try {
            assertCompactCard(instrumentation, activity, "正在解冻");
            instrumentation.runOnMainSync(() -> {
                light.set(((GradientDrawable) activity.card().getBackground()).getColor().getDefaultColor());
                activity.show(1, SystemTransitPresenter.Stage.OPENING, 0xff7e57c2, "dark");
            });
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> dark.set(((GradientDrawable) activity.card()
                    .getBackground()).getColor().getDefaultColor()));
            assertNotEquals(light.get(), dark.get());
            assertEquals(TransitSessionPolicy.Palette.fromSeed(0xff7e57c2, false).card, light.get());
            assertEquals(TransitSessionPolicy.Palette.fromSeed(0xff7e57c2, true).card, dark.get());
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void cardDoesNotTakeFocusAndTouchReachesTheBackground() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        TransitPreviewActivity activity = open(instrumentation, false);
        Rect bounds = new Rect();
        CountDownLatch downReceived = new CountDownLatch(1);
        CountDownLatch clicked = new CountDownLatch(1);
        try {
            assertCompactCard(instrumentation, activity, "正在解冻");
            awaitFocusedAndLaidOut(instrumentation, activity);
            syncInputTransactions(instrumentation.getUiAutomation());
            instrumentation.runOnMainSync(() -> {
                assertTrue("The underlying Activity window must own input focus", activity.hasWindowFocus());
                assertSame(activity.backgroundView(), activity.getCurrentFocus());
                assertTrue(activity.backgroundView().isLaidOut());
                assertTrue(activity.card().isLaidOut());
                activity.observeBackgroundInput(downReceived::countDown, clicked::countDown);
                int[] location = new int[2];
                activity.card().getLocationOnScreen(location);
                bounds.set(location[0], location[1], location[0] + activity.card().getWidth(),
                        location[1] + activity.card().getHeight());
                assertFalse(bounds.isEmpty());
            });
            long time = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
                    bounds.centerX(), bounds.centerY(), 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            boolean released = false;
            try {
                assertTrue(instrumentation.getUiAutomation().injectInputEvent(down, true));
                assertTrue("The real DOWN must pass through the card to the background",
                        downReceived.await(5, TimeUnit.SECONDS));
                // Wait for handling of the pressed frame, not an arbitrary sleep or a future eventTime.
                CountDownLatch pressedFrame = new CountDownLatch(1);
                instrumentation.runOnMainSync(() -> activity.backgroundView().postOnAnimation(pressedFrame::countDown));
                assertTrue("The background must receive an animation frame after DOWN",
                        pressedFrame.await(5, TimeUnit.SECONDS));
                MotionEvent up = MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                        bounds.centerX(), bounds.centerY(), 0);
                up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try {
                    assertTrue(instrumentation.getUiAutomation().injectInputEvent(up, true));
                    released = true;
                } finally { up.recycle(); }
                assertTrue("The real background click callback must run", clicked.await(5, TimeUnit.SECONDS));
            } finally {
                down.recycle();
                if (!released) {
                    MotionEvent cancel = MotionEvent.obtain(time, SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_CANCEL, bounds.centerX(), bounds.centerY(), 0);
                    cancel.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    try { instrumentation.getUiAutomation().injectInputEvent(cancel, true); }
                    finally { cancel.recycle(); }
                }
            }
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertEquals(1, activity.backgroundClicks());
                assertSame(activity.backgroundView(), activity.getCurrentFocus());
            });
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    private void awaitFocusedAndLaidOut(Instrumentation instrumentation, TransitPreviewActivity activity)
            throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(1);
        ViewTreeObserver[] observers = new ViewTreeObserver[2];
        Runnable check = () -> {
            View card = activity.card();
            if (activity.hasWindowFocus() && activity.backgroundView().isAttachedToWindow()
                    && activity.backgroundView().isLaidOut() && card != null
                    && card.isAttachedToWindow() && card.isLaidOut()) ready.countDown();
        };
        ViewTreeObserver.OnGlobalLayoutListener layout = check::run;
        ViewTreeObserver.OnWindowFocusChangeListener focus = hasFocus -> check.run();
        instrumentation.runOnMainSync(() -> {
            observers[0] = activity.getWindow().getDecorView().getViewTreeObserver();
            observers[1] = activity.card().getViewTreeObserver();
            observers[0].addOnWindowFocusChangeListener(focus);
            observers[0].addOnGlobalLayoutListener(layout);
            observers[1].addOnGlobalLayoutListener(layout);
            check.run();
        });
        try {
            assertTrue("Both windows must be laid out and the Activity must hold window focus",
                    ready.await(5, TimeUnit.SECONDS));
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (observers[0] != null && observers[0].isAlive()) {
                    observers[0].removeOnWindowFocusChangeListener(focus);
                    observers[0].removeOnGlobalLayoutListener(layout);
                }
                if (observers[1] != null && observers[1].isAlive()) {
                    observers[1].removeOnGlobalLayoutListener(layout);
                }
            });
        }
    }

    private void syncInputTransactions(UiAutomation automation) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            // @TestApi is present in the platform, but absent from SDK34's public android.jar.
            // Keep this reflection confined to instrumentation; failure must remain a test failure.
            UiAutomation.class.getMethod("syncInputTransactions").invoke(automation);
        }
    }

    private TransitPreviewActivity open(Instrumentation instrumentation, boolean dark) {
        return open(instrumentation, dark, null, null);
    }

    private TransitPreviewActivity openSmallWindow(Instrumentation instrumentation, boolean dark,
                                                    SmallWindowLayout layout) {
        Intent intent = new Intent(instrumentation.getTargetContext(), TransitPreviewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("preview_dark", dark)
                .putExtra("preview_small_window", true)
                .putExtra("preview_window_width", layout.widthPercent)
                .putExtra("preview_window_height", layout.heightPercent)
                .putExtra("preview_window_horizontal", layout.horizontalPercent)
                .putExtra("preview_window_vertical", layout.verticalPercent);
        TransitPreviewActivity activity = (TransitPreviewActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        return activity;
    }

    private void assertCardPlacement(TransitPreviewActivity activity, SmallWindowLayout layout) {
        Rect target = SystemWindowLayout.resolve(activity, layout);
        View card = activity.card();
        int[] location = new int[2];
        card.getLocationOnScreen(location);
        Rect actual = new Rect(location[0], location[1], location[0] + card.getWidth(),
                location[1] + card.getHeight());
        assertTrue("The progress card must fit within the intended target: " + actual + " in " + target,
                target.contains(actual));
        assertEquals("Horizontal placement must use the target center, rather than screen center",
                target.centerX(), actual.centerX(), 1);
        assertEquals("Preview panels must use the same screen frame as the actual system card",
                target.centerY(), actual.centerY(), 1);
    }

    private void assertFlowGeometryInsideView(TransitFlowView flow) {
        for (RectF bounds : new RectF[]{flow.sourceIconBounds(), flow.targetIconBounds()}) {
            assertTrue(bounds.width() > 0);
            assertTrue(bounds.height() > 0);
            assertTrue(bounds.left >= 0);
            assertTrue(bounds.top >= 0);
            assertTrue(bounds.right <= flow.getWidth());
            assertTrue(bounds.bottom <= flow.getHeight());
        }
    }

    private TransitPreviewActivity open(Instrumentation instrumentation, boolean dark,
                                        String sourcePackage, String targetPackage) {
        Intent intent = new Intent(instrumentation.getTargetContext(), TransitPreviewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("preview_dark", dark);
        if (sourcePackage != null) intent.putExtra("preview_source", sourcePackage);
        if (targetPackage != null) intent.putExtra("preview_target", targetPackage);
        TransitPreviewActivity activity = (TransitPreviewActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        return activity;
    }

    private void assertFlowIdentity(TransitFlowView flow) {
        assertTrue("The source must have an app icon or a usable fallback", flow.hasSourceIcon());
        assertTrue("The target must have an app icon or a usable fallback", flow.hasTargetIcon());
        assertNotNull(flow.sourceLabel());
        assertNotNull(flow.targetLabel());
        assertFalse("The source label must remain accessible", flow.sourceLabel().trim().isEmpty());
        assertFalse("The target label must remain accessible", flow.targetLabel().trim().isEmpty());
        CharSequence description = flow.getContentDescription();
        assertNotNull("The visual flow must expose its direction to accessibility", description);
        String spoken = description.toString();
        int sourceIndex = spoken.indexOf(flow.sourceLabel());
        int targetIndex = spoken.indexOf(flow.targetLabel(), sourceIndex + flow.sourceLabel().length());
        assertTrue("Accessibility must identify the source app", sourceIndex >= 0);
        assertTrue("Accessibility must identify the target after the source", targetIndex > sourceIndex);
    }

    private TransitFlowView findFlow(View view) {
        if (view instanceof TransitFlowView) return (TransitFlowView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TransitFlowView flow = findFlow(group.getChildAt(index));
                if (flow != null) return flow;
            }
        }
        return null;
    }

    private void awaitFlowFrames(Instrumentation instrumentation, TransitFlowView flow)
            throws InterruptedException {
        CountDownLatch advanced = new CountDownLatch(1);
        AtomicInteger startingFrame = new AtomicInteger();
        AtomicInteger latestFrame = new AtomicInteger();
        Runnable check = new Runnable() {
            @Override public void run() {
                latestFrame.set(flow.renderedFrames());
                if (latestFrame.get() >= startingFrame.get() + 2) advanced.countDown();
                else if (flow.isAttachedToWindow()) flow.postOnAnimation(this);
            }
        };
        instrumentation.runOnMainSync(() -> {
            startingFrame.set(flow.renderedFrames());
            flow.postOnAnimation(check);
        });
        try {
            assertTrue("The flow must draw real animation frames; started at " + startingFrame.get(),
                    advanced.await(5, TimeUnit.SECONDS));
            assertTrue(latestFrame.get() >= startingFrame.get() + 2);
        } finally {
            instrumentation.runOnMainSync(() -> flow.removeCallbacks(check));
        }
    }

    private void assertCompactCard(Instrumentation instrumentation, TransitPreviewActivity activity, String stage) {
        instrumentation.runOnMainSync(() -> {
            View card = activity.card();
            assertNotNull("The native preview card must be attached", card);
            assertFalse("The compact card must have no visible text", containsVisibleText(card));
            CharSequence description = card.getContentDescription();
            assertNotNull("The icon-only card must remain accessible", description);
            assertTrue("Missing accessible stage " + stage, description.toString().contains(stage));
            TransitFlowView flow = findFlow(card);
            assertNotNull("Only the app-to-app visual flow should be shown", flow);
            assertTrue("The source identity must remain accessible",
                    description.toString().contains(flow.sourceLabel()));
            assertTrue("The destination identity must remain accessible",
                    description.toString().contains(flow.targetLabel()));
        });
    }

    private boolean containsVisibleText(View view) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof TextView && ((TextView) view).getText().length() != 0) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsVisibleText(group.getChildAt(index))) return true;
            }
        }
        return false;
    }
}
