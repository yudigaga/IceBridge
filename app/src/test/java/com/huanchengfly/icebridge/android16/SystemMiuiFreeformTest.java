package com.huanchengfly.icebridge.android16;

import org.junit.Test;

import static org.junit.Assert.*;

public class SystemMiuiFreeformTest {
    @Test public void nativeDefaultScaleIsExplicitlyDisabledAndVerified() {
        Injector injector = new Injector();
        assertEquals(0.7f, injector.scale, 0f);
        assertTrue(SystemMiuiFreeform.configureUnscaledLaunch(new Options(injector)));
        assertEquals(1f, injector.scale, 0f);
        assertEquals(1, injector.writes);
    }

    @Test public void stockAndroidOrMissingInjectorIsAnOptionalCapability() {
        assertFalse(SystemMiuiFreeform.configureUnscaledLaunch(null));
        assertFalse(SystemMiuiFreeform.configureUnscaledLaunch(new Object()));
        assertFalse(SystemMiuiFreeform.configureUnscaledLaunch(new Options(null)));
        assertFalse(SystemMiuiFreeform.configureUnscaledLaunch(new Options(new Object())));
    }

    @Test public void ignoredScaleSetterDoesNotClaimSuccess() {
        assertFalse(SystemMiuiFreeform.configureUnscaledLaunch(new Options(new IgnoredInjector())));
    }

    @Test public void vendorSetterAndGetterFailuresAreContained() {
        assertFalse(SystemMiuiFreeform.configureUnscaledLaunch(new Options(new ThrowingSetter())));
        assertFalse(SystemMiuiFreeform.configureUnscaledLaunch(new Options(new ThrowingGetter())));
    }

    @Test public void privateInheritedScaleFieldIsAReadbackFallback() {
        assertTrue(SystemMiuiFreeform.configureUnscaledLaunch(new Options(new FieldOnlyInjector())));
    }

    @Test public void missingReadbackDoesNotClaimSuccess() {
        assertFalse(SystemMiuiFreeform.configureUnscaledLaunch(new Options(new WriteOnlyInjector())));
    }

    @Test public void wrongSetterSignatureCannotBeCalledAccidentally() {
        WrongSignature injector = new WrongSignature();
        assertFalse(SystemMiuiFreeform.configureUnscaledLaunch(new Options(injector)));
        assertEquals(0, injector.writes);
    }

    @Test public void nonNumericReadbackDoesNotClaimSuccess() {
        assertFalse(SystemMiuiFreeform.configureUnscaledLaunch(new Options(new StringGetter())));
    }

    @Test public void localTaskReadsOnlyTheRequestedNativeStack() {
        Manager manager = new Manager(new Stack(0.7f));
        assertEquals(0.7f, SystemMiuiFreeform.taskScale(new Task(new Atms(manager)), 13), 0f);
        assertEquals(13, manager.requestedTask);
        assertEquals(1, manager.reads);
    }

    @Test public void inheritedPrivateFrameworkFieldsAndMethodsAreSupported() {
        Manager manager = new Manager(new DerivedStack(0.25f));
        assertEquals(0.25f,
                SystemMiuiFreeform.taskScale(new DerivedTask(new Atms(manager)), 21), 0f);
    }

    @Test public void workerCanReadLocalAtmsButNeverCallsARemoteProxy() {
        Manager manager = new Manager(new Stack(0.7f));
        assertEquals(0.7f, SystemMiuiFreeform.atmsScale(new Atms(manager), 13), 0f);
        assertEquals(13, manager.requestedTask);
        RemoteProxy proxy = new RemoteProxy();
        assertNull(SystemMiuiFreeform.atmsScale(proxy, 13));
        assertEquals(0, proxy.calls);
        assertNull(SystemMiuiFreeform.atmsScale(new Atms(manager), -1));
        assertEquals(1, manager.reads);
    }

    @Test public void unknownTaskAndMissingVendorServiceYieldNoScale() {
        Manager manager = new Manager(null);
        assertNull(SystemMiuiFreeform.taskScale(null, 13));
        assertNull(SystemMiuiFreeform.taskScale(new Object(), 13));
        assertNull(SystemMiuiFreeform.taskScale(new Task(null), 13));
        assertNull(SystemMiuiFreeform.taskScale(new Task(new Atms(null)), 13));
        assertNull(SystemMiuiFreeform.taskScale(new Task(new Atms(manager)), 13));
        assertNull(SystemMiuiFreeform.taskScale(new Task(new Atms(manager)), -1));
        assertEquals(1, manager.reads);
    }

    @Test public void invalidNativeScalesNeverBecomeWindowGeometry() {
        for (float scale : new float[]{Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, -1f, 0f, 0.001f, 3f}) {
            assertNull("scale=" + scale, SystemMiuiFreeform.stackScale(new Stack(scale)));
        }
        assertEquals(1f, SystemMiuiFreeform.stackScale(new Stack(1f)), 0f);
    }

    @Test public void vendorStackReadFailuresAreContained() {
        assertNull(SystemMiuiFreeform.stackScale(new Object()));
        assertNull(SystemMiuiFreeform.stackScale(new BrokenStack()));
        assertNull(SystemMiuiFreeform.taskScale(new Task(new Atms(new BrokenManager())), 13));
    }

    private static final class Options {
        private final Object injector;
        Options(Object injector) { this.injector = injector; }
        private Object getActivityOptionsInjector() { return injector; }
    }

    private static class Injector {
        float scale = 0.7f;
        int writes;
        private void setFreeformScale(float value) { scale = value; writes++; }
        private float getFreeformScale() { return scale; }
    }

    private static final class IgnoredInjector {
        private void setFreeformScale(float ignored) { }
        private float getFreeformScale() { return 0.7f; }
    }

    private static final class ThrowingSetter {
        private void setFreeformScale(float ignored) { throw new IllegalStateException(); }
        private float getFreeformScale() { return 1f; }
    }

    private static final class ThrowingGetter {
        private void setFreeformScale(float ignored) { }
        private float getFreeformScale() { throw new LinkageError(); }
    }

    private static class FieldInjectorBase {
        private float mFreeformScale = 0.7f;
        private void setFreeformScale(float value) { mFreeformScale = value; }
    }

    private static final class FieldOnlyInjector extends FieldInjectorBase { }

    private static final class WriteOnlyInjector {
        private void setFreeformScale(float ignored) { }
    }

    private static final class WrongSignature {
        int writes;
        private void setFreeformScale(double ignored) { writes++; }
        private float getFreeformScale() { return 1f; }
    }

    private static final class StringGetter {
        private void setFreeformScale(float ignored) { }
        private String getFreeformScale() { return "1.0"; }
    }

    private static class Task {
        private final Object mAtmService;
        Task(Object atms) { mAtmService = atms; }
    }

    private static final class DerivedTask extends Task {
        DerivedTask(Object atms) { super(atms); }
    }

    private static final class Atms {
        private final Object mMiuiFreeFormManagerService;
        Atms(Object manager) { mMiuiFreeFormManagerService = manager; }
    }

    private static final class RemoteProxy {
        int calls;
        private Object getMiuiFreeFormActivityStack(int ignored) { calls++; throw new AssertionError(); }
    }

    private static final class Manager {
        private final Object stack;
        int requestedTask = -1;
        int reads;
        Manager(Object stack) { this.stack = stack; }
        private Object getMiuiFreeFormActivityStack(int taskId) {
            requestedTask = taskId;
            reads++;
            return stack;
        }
    }

    private static final class BrokenManager {
        private Object getMiuiFreeFormActivityStack(int ignored) { throw new IllegalStateException(); }
    }

    private static class Stack {
        private final float scale;
        Stack(float scale) { this.scale = scale; }
        private float getFreeFormScale() { return scale; }
    }

    private static final class DerivedStack extends Stack {
        DerivedStack(float scale) { super(scale); }
    }

    private static final class BrokenStack {
        private float getFreeFormScale() { throw new LinkageError(); }
    }
}
