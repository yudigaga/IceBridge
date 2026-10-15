package com.huanchengfly.icebridge.android16;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
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

import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Uses an application token to verify the same native card without asking for overlay permission. */
@RunWith(AndroidJUnit4.class)
public class SystemTransitRenderingTest {
    @Test public void nativeCardShowsBothStagesAndWaitsForActualTargetDraw() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        TransitPreviewActivity activity = open(instrumentation, false);
        try {
            assertCardText(instrumentation, activity, "正在解冻");
            instrumentation.runOnMainSync(() -> {
                activity.show(1, SystemTransitPresenter.Stage.OPENING, 0xff7e57c2, "light");
                activity.submitted(1);
            });
            instrumentation.waitForIdleSync();
            assertCardText(instrumentation, activity, "正在打开");
            instrumentation.runOnMainSync(() -> activity.targetDrawn(1));
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> assertNull(activity.card()));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void oldDrawCannotDismissReplacementAndFailureRemovesIt() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        TransitPreviewActivity activity = open(instrumentation, false);
        try {
            assertCardText(instrumentation, activity, "正在解冻");
            instrumentation.runOnMainSync(() -> {
                activity.show(2, SystemTransitPresenter.Stage.OPENING, 0xff00796b, "light");
                activity.targetDrawn(1);
            });
            instrumentation.waitForIdleSync();
            assertCardText(instrumentation, activity, "正在打开");
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
            assertCardText(instrumentation, activity, "正在解冻");
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
            assertCardText(instrumentation, activity, "正在解冻");
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
        Intent intent = new Intent(instrumentation.getTargetContext(), TransitPreviewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("preview_dark", dark);
        TransitPreviewActivity activity = (TransitPreviewActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        return activity;
    }

    private void assertCardText(Instrumentation instrumentation, TransitPreviewActivity activity, String text) {
        instrumentation.runOnMainSync(() -> {
            assertNotNull("The native preview card must be attached", activity.card());
            assertTrue("Missing stage " + text, containsText(activity.card(), text));
        });
    }

    private boolean containsText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsText(group.getChildAt(index), text)) return true;
            }
        }
        return false;
    }
}
