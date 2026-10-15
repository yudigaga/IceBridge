package com.huanchengfly.icebridge.android16;

import com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator;

import org.junit.Test;

import static com.huanchengfly.icebridge.automation.AutonomousBridgeCoordinator.State;
import static org.junit.Assert.*;

public class SystemLaunchPreflightTest {
    private static final String SELF = "io.github.yudigaga.icebridge";
    private static final String TARGET = "com.example.target";
    private static final State FROZEN = State.known(true, 3, false, true, false);
    private static final State READY = State.known(true, 1, true, false, false);

    @Test public void thawIsVerifiedBeforeOriginalFrameworkLaunchCanProceed() {
        Packages packages = new Packages();
        SystemLaunchPreflight.Result result = new SystemLaunchPreflight(SELF, packages)
                .thawTarget(TARGET, 10, 10, true);
        assertEquals(SystemLaunchPreflight.Outcome.THAWED, result.outcome);
        assertEquals(TARGET, result.targetPackage);
        assertEquals(1, packages.writes);
        assertEquals(2, packages.reads);
        assertEquals(10, packages.user);
        // The preflight has no launcher or replayer. Its only side effect is verified PM state.
        assertTrue(packages.state.isLaunchable());
    }

    @Test public void localCallsGlobalLockUnknownUserAndCrossUserNeverReadOrMutatePackages() {
        Packages packages = new Packages();
        SystemLaunchPreflight preflight = new SystemLaunchPreflight(SELF, packages);
        assertEquals(SystemLaunchPreflight.Outcome.SKIPPED, preflight.thawTarget(TARGET, 0, 0, false).outcome);
        assertEquals(SystemLaunchPreflight.Outcome.SKIPPED, preflight.thawTarget(TARGET, -1, 0, true).outcome);
        assertEquals(SystemLaunchPreflight.Outcome.SKIPPED, preflight.thawTarget(TARGET, 10, 0, true).outcome);
        assertEquals(SystemLaunchPreflight.Outcome.SKIPPED, preflight.thawTarget(TARGET, 0, -1, true).outcome);
        assertEquals(0, packages.reads);
        assertEquals(0, packages.writes);
    }

    @Test public void protectsFrozenOwnerAndDoesNotGuessMissingTarget() {
        Packages packages = new Packages();
        SystemLaunchPreflight preflight = new SystemLaunchPreflight(SELF, packages);
        assertEquals("self-thaw-prohibited", preflight.thawTarget(SELF, 0, 0, true).reason);
        assertEquals(SystemLaunchPreflight.Outcome.NO_ACTION, preflight.thawTarget(null, 0, 0, true).outcome);
        assertEquals(0, packages.reads);
    }

    @Test public void unknownUninstalledAndSuspendedStatesKeepOriginalFrameworkFailure() {
        for (State state : new State[]{State.unknown("missing-capability"),
                State.known(false, 0, false, false, false), State.known(true, 3, false, true, true)}) {
            Packages packages = new Packages();
            packages.state = state;
            SystemLaunchPreflight.Result result = new SystemLaunchPreflight(SELF, packages)
                    .thawTarget(TARGET, 0, 0, true);
            assertNotEquals(SystemLaunchPreflight.Outcome.THAWED, result.outcome);
            assertEquals(0, packages.writes);
        }
    }

    @Test public void alreadyEnabledPackageIsUntouched() {
        Packages packages = new Packages();
        packages.state = READY;
        assertEquals(SystemLaunchPreflight.Outcome.NO_ACTION, new SystemLaunchPreflight(SELF, packages)
                .thawTarget(TARGET, 0, 0, true).outcome);
        assertEquals(0, packages.writes);
    }

    @Test public void unsupportedUnhideStopsBeforeAnyPartialMutation() {
        Packages packages = new Packages();
        packages.unhide = false;
        assertEquals("setter-unavailable", new SystemLaunchPreflight(SELF, packages)
                .thawTarget(TARGET, 0, 0, true).reason);
        assertEquals(0, packages.writes);
    }

    @Test public void setterFailureAndUnsuccessfulReadbackAreNotSuccess() {
        Packages packages = new Packages();
        packages.failure = "SecurityException";
        assertEquals(SystemLaunchPreflight.Outcome.FAILED, new SystemLaunchPreflight(SELF, packages)
                .thawTarget(TARGET, 0, 0, true).outcome);
        packages.failure = null;
        packages.after = FROZEN;
        assertEquals("thaw-readback-failed", new SystemLaunchPreflight(SELF, packages)
                .thawTarget(TARGET, 0, 0, true).reason);
    }

    @Test public void globalLockCheckRejectsHeldLockMissingFieldAndSameProcessReentry() {
        Service service = new Service();
        assertTrue(SystemLaunchPreflight.isOutsideGlobalLock(service, 101, 202));
        synchronized (service.mGlobalLock) {
            assertFalse(SystemLaunchPreflight.isOutsideGlobalLock(service, 101, 202));
        }
        assertFalse(SystemLaunchPreflight.isOutsideGlobalLock(service, 202, 202));
        assertFalse(SystemLaunchPreflight.isOutsideGlobalLock(new Object(), 101, 202));
        assertFalse(SystemLaunchPreflight.isOutsideGlobalLock(service, -1, 202));
    }

    @Test public void lockCheckFindsInheritedOemGlobalLock() {
        assertTrue(SystemLaunchPreflight.isOutsideGlobalLock(new OemService(), 101, 202));
    }

    private static class Service { final Object mGlobalLock = new Object(); }
    private static final class OemService extends Service { }

    private static final class Packages implements AutonomousBridgeCoordinator.PackageAccess {
        State state = FROZEN;
        State after = READY;
        boolean unhide = true;
        int reads;
        int writes;
        int user = -1;
        String failure;
        @Override public State read(String target, int user) {
            assertEquals(TARGET, target);
            this.user = user;
            reads++;
            return state;
        }
        @Override public AutonomousBridgeCoordinator.Capabilities capabilities() {
            return new AutonomousBridgeCoordinator.Capabilities(true, unhide, "fake");
        }
        @Override public String thaw(String target, int user, State before) {
            writes++;
            if (failure == null) state = after;
            return failure;
        }
    }
}
