package com.huanchengfly.icebridge.android16;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.test.InstrumentationRegistry;

import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class SystemFreeformLaunchTest {
    private static final String SELF = "io.github.yudigaga.icebridge";
    private static final String SOURCE = "com.tencent.mm";
    private static final String TARGET = "com.example.target";

    @Test public void platformOptionsRoundTripAsFreeformWithRomSelectedBounds() throws Exception {
        Bundle bundle = SystemFreeformLaunchOptions.create();
        assertNotNull(bundle);
        ActivityOptions restored = (ActivityOptions) ActivityOptions.class
                .getMethod("fromBundle", Bundle.class).invoke(null, new Bundle(bundle));
        assertNotNull(restored);
        assertEquals(5, ActivityOptions.class.getMethod("getLaunchWindowingMode").invoke(restored));
        assertNull("The ROM must choose the freeform size and position", restored.getLaunchBounds());
    }

    @Test public void typedOptionsReachTheAtmsOptionsArgumentAndKeepUserAndCaller() throws Exception {
        FakeAtms service = new FakeAtms();
        Intent intent = web();
        Bundle options = SystemFreeformLaunchOptions.create();
        assertEquals(0, SystemActivityLauncher.invokeStart(service, startMethod(), intent,
                "text/html", 10, options));
        assertSame(intent, service.intent);
        assertSame(options, service.options);
        assertEquals(10, service.userId);
        assertNull(service.caller);
        assertNull(service.resultTo);
        assertEquals("android", service.callingPackage);
        assertEquals("text/html", service.resolvedType);
        assertEquals(-1, service.requestCode);
        assertEquals(0, service.flags);
        SystemActivityLauncher.invokeStart(service, startMethod(), intent, null, 0, null);
        assertNull("Original and fallback calls keep null options", service.options);
        assertEquals(0, service.userId);
        assertEquals(2, service.calls);
    }

    @Test public void toggleIsReadForEachWebLaunchAndDisabledModeDoesNotBuildOptions() throws Exception {
        AtomicBoolean enabled = new AtomicBoolean(false);
        Fixture fixture = new Fixture(enabled::get);
        fixture.launcher.launch(request(web(), AutonomousBridgeCoordinator.Kind.WEB_RULE, false), TARGET, "none");
        assertNull(fixture.options);
        assertEquals(0, fixture.optionBuilds);
        enabled.set(true);
        fixture.launcher.launch(request(web(), AutonomousBridgeCoordinator.Kind.WEB_RULE, false), TARGET, "none");
        assertSame(fixture.freeform, fixture.options);
        assertEquals(1, fixture.optionBuilds);
        enabled.set(false);
        fixture.launcher.launch(request(web(), AutonomousBridgeCoordinator.Kind.WEB_RULE, false), TARGET, "none");
        assertNull(fixture.options);
        assertEquals(1, fixture.optionBuilds);
        assertEquals(3, fixture.starts);
        assertEquals(3, fixture.decorations);
        assertEquals(10, fixture.userId);
        assertEquals(TARGET, fixture.intent.getPackage());
        assertTrue(fixture.events.contains("system-small-window-requested"));
        for (String event : fixture.events) {
            assertFalse("No private URL contents in diagnostics", event.contains("private"));
            assertFalse("No URLs in diagnostics", event.contains("https://"));
        }
    }

    @Test public void explicitStartsAndOriginalReplayDoNotUseSmallWindowOptions() throws Exception {
        Fixture fixture = new Fixture(() -> true);
        Intent nativeIntent = new Intent("example.OPEN", Uri.parse("example://item"))
                .setComponent(new ComponentName(TARGET, TARGET + ".MainActivity"));
        fixture.launcher.launch(request(nativeIntent, AutonomousBridgeCoordinator.Kind.EXPLICIT, false),
                TARGET, "none");
        assertNull(fixture.options);
        assertEquals(0, fixture.optionBuilds);
        Intent original = web().setComponent(new ComponentName(SOURCE, SOURCE + ".WebViewActivity"));
        fixture.launcher.fallback(request(original, AutonomousBridgeCoordinator.Kind.WEB_RULE, true));
        assertNull(fixture.options);
        assertEquals(original.getComponent(), fixture.intent.getComponent());
        assertEquals(original.getData(), fixture.intent.getData());
        assertEquals(0, fixture.optionBuilds);
        assertEquals(2, fixture.starts);
    }

    @Test public void selectedResolvedTargetGetsSmallWindowRegardlessOfOriginalRequestTarget() throws Exception {
        String selectedTarget = "com.example.selected";
        List<String> checkedTargets = new ArrayList<>();
        Fixture fixture = new Fixture(target -> {
            checkedTargets.add(target);
            return selectedTarget.equals(target);
        });
        AutonomousBridgeCoordinator.Request<Intent> original = request(web(),
                AutonomousBridgeCoordinator.Kind.WEB_RULE, false);
        assertEquals(TARGET, original.explicitTarget);
        fixture.launcher.launch(original, selectedTarget, "none");
        assertSame(fixture.freeform, fixture.options);
        assertEquals(selectedTarget, fixture.intent.getPackage());
        assertEquals(1, checkedTargets.size());
        assertEquals(selectedTarget, checkedTargets.get(0));
    }

    @Test public void selectingTheOriginalRequestTargetDoesNotApplyToAnotherResolvedTarget() throws Exception {
        Fixture fixture = new Fixture(target -> TARGET.equals(target));
        String resolvedTarget = "com.example.unselected";
        fixture.launcher.launch(request(web(), AutonomousBridgeCoordinator.Kind.WEB_RULE, false),
                resolvedTarget, "none");
        assertNull(fixture.options);
        assertEquals(0, fixture.optionBuilds);
        assertEquals(resolvedTarget, fixture.intent.getPackage());
        assertEquals(1, fixture.starts);
    }

    @Test public void selectingOnlySourceAppDoesNotPutItsDestinationInSmallWindow() throws Exception {
        Fixture fixture = new Fixture(target -> SOURCE.equals(target));
        fixture.launcher.launch(request(web().setPackage(SOURCE),
                AutonomousBridgeCoordinator.Kind.WEB_RULE, false), TARGET, "none");
        assertNull(fixture.options);
        assertEquals(0, fixture.optionBuilds);
        assertEquals(TARGET, fixture.intent.getPackage());
        assertEquals(1, fixture.starts);
    }

    @Test public void unavailableOptionsFallBackBeforeOneNormalStart() throws Exception {
        Fixture fixture = new Fixture(() -> true);
        fixture.optionsFailure = new NoSuchMethodException("setLaunchWindowingMode");
        fixture.launcher.launch(request(web(), AutonomousBridgeCoordinator.Kind.WEB_RULE, false), TARGET, "none");
        assertEquals(1, fixture.starts);
        assertEquals(1, fixture.optionBuilds);
        assertNull(fixture.options);
        assertTrue(fixture.events.contains("system-small-window-unavailable:NoSuchMethodException"));
        assertEquals(web().getData(), fixture.intent.getData());
    }

    @Test public void failedSettingReadKeepsSingleNormalLaunch() throws Exception {
        Fixture fixture = new Fixture(() -> { throw new IllegalStateException("settings-not-ready"); });
        fixture.launcher.launch(request(web(), AutonomousBridgeCoordinator.Kind.WEB_RULE, false), TARGET, "none");
        assertEquals(1, fixture.starts);
        assertEquals(0, fixture.optionBuilds);
        assertNull(fixture.options);
        assertTrue(fixture.events.contains("system-small-window-unavailable:IllegalStateException"));
    }

    @Test public void failedStartWithOptionsIsNeverRetriedWithoutOptions() throws Exception {
        Fixture fixture = new Fixture(() -> true);
        fixture.startFailure = new IllegalStateException("start-may-already-be-accepted");
        try {
            fixture.launcher.launch(request(web(), AutonomousBridgeCoordinator.Kind.WEB_RULE, false),
                    TARGET, "none");
            fail("Expected start failure to propagate without a duplicate launch");
        } catch (IllegalStateException expected) {
            assertSame(fixture.startFailure, expected);
        }
        assertEquals(1, fixture.starts);
        assertEquals(1, fixture.decorations);
        assertSame(fixture.freeform, fixture.options);
    }

    @Test public void loggingFailureCannotTurnAnAcceptedLaunchIntoAFailure() throws Exception {
        Fixture fixture = new Fixture(() -> true);
        fixture.failLogging = true;
        fixture.launcher.launch(request(web(), AutonomousBridgeCoordinator.Kind.WEB_RULE, false), TARGET, "none");
        assertEquals(1, fixture.starts);
        assertSame(fixture.freeform, fixture.options);
        fixture.launcher.fallback(request(web(), AutonomousBridgeCoordinator.Kind.WEB_RULE, true));
        assertEquals(2, fixture.starts);
        assertNull(fixture.options);
    }

    private static Intent web() {
        return new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/item?private=value"));
    }

    private static AutonomousBridgeCoordinator.Request<Intent> request(Intent intent,
            AutonomousBridgeCoordinator.Kind kind, boolean ownsOriginal) {
        return new AutonomousBridgeCoordinator.Request<>(kind, 10, SOURCE,
                kind == AutonomousBridgeCoordinator.Kind.WEB_RULE ? "example.com" : null,
                TARGET, "request-fingerprint", intent, ownsOriginal, true);
    }

    private static final class Fixture {
        final Bundle freeform = new Bundle();
        final List<String> events = new ArrayList<>();
        final SystemAutonomousLauncher launcher;
        Bundle options;
        Intent intent;
        int userId;
        int starts;
        int decorations;
        int optionBuilds;
        Exception optionsFailure;
        RuntimeException startFailure;
        boolean failLogging;

        Fixture(BooleanSupplier enabled) {
            this(target -> enabled.getAsBoolean());
        }

        Fixture(Predicate<String> selectedTarget) {
            Context context = InstrumentationRegistry.getTargetContext();
            freeform.putInt("test-mode", 5);
            launcher = new SystemAutonomousLauncher(context, SELF,
                    (launch, user, source) -> {
                        assertEquals(SOURCE, source);
                        decorations++;
                        launch.putExtra("test-replay-registered", true);
                    }, (event, user, target) -> {
                        events.add(event);
                        if (failLogging) throw new IllegalStateException("logger-unavailable");
                    }, selectedTarget, (suppliedContext, launch, user, launchOptions) -> {
                        assertSame(context, suppliedContext);
                        assertTrue(launch.getBooleanExtra("test-replay-registered", false));
                        starts++;
                        intent = launch;
                        options = launchOptions;
                        userId = user;
                        if (startFailure != null) throw startFailure;
                    }, () -> {
                        optionBuilds++;
                        if (optionsFailure != null) throw optionsFailure;
                        return freeform;
                    });
        }
    }

    private static Method startMethod() throws Exception {
        return FakeAtms.class.getMethod("startActivityAsUser", Object.class, String.class,
                String.class, Intent.class, String.class, Object.class, String.class,
                int.class, int.class, Object.class, Bundle.class, int.class);
    }

    public static final class FakeAtms {
        int calls;
        Object caller;
        Object resultTo;
        String callingPackage;
        String resolvedType;
        Intent intent;
        Bundle options;
        int requestCode;
        int flags;
        int userId;
        public int startActivityAsUser(Object caller, String callingPackage, String feature,
                Intent intent, String resolvedType, Object resultTo, String resultWho,
                int requestCode, int flags, Object profiler, Bundle options, int userId) {
            calls++;
            this.caller = caller;
            this.callingPackage = callingPackage;
            this.intent = intent;
            this.resolvedType = resolvedType;
            this.resultTo = resultTo;
            this.requestCode = requestCode;
            this.flags = flags;
            this.options = options;
            this.userId = userId;
            return 0;
        }
    }
}
