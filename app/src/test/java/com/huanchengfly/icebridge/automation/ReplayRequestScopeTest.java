package com.huanchengfly.icebridge.automation;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.Assert.*;

public class ReplayRequestScopeTest {
    @Test public void bothHooksAcceptOneReplayButConsumeTheTokenOnlyOnce() {
        AtomicInteger consumptions = new AtomicInteger();
        Predicate<String> consume = token -> consumptions.incrementAndGet() == 1;
        try (ReplayRequestScope ignored = ReplayRequestScope.open()) {
            assertTrue(ReplayRequestScope.accept("replay", consume));
            assertTrue("The second hook must recognize this same verified request",
                    ReplayRequestScope.accept("replay", consume));
            assertEquals(1, consumptions.get());
            assertFalse(ReplayRequestScope.accept("unregistered", consume));
            assertFalse(ReplayRequestScope.accept("unregistered", consume));
            assertEquals("Failed validation also belongs to this request", 2, consumptions.get());
        }
    }

    @Test public void leavingScopeDoesNotAuthorizeTheNextRequestOrUnscopedCall() {
        AtomicInteger consumptions = new AtomicInteger();
        Predicate<String> consume = token -> consumptions.incrementAndGet() == 1;
        try (ReplayRequestScope ignored = ReplayRequestScope.open()) {
            assertTrue(ReplayRequestScope.accept("replay", consume));
        }
        try (ReplayRequestScope ignored = ReplayRequestScope.open()) {
            assertFalse(ReplayRequestScope.accept("replay", consume));
        }
        assertFalse(ReplayRequestScope.accept("replay", consume));
        assertFalse(ReplayRequestScope.accept("replay", consume));
        assertEquals("Every unscoped call must perform its own real validation", 4, consumptions.get());
    }

    @Test public void exceptionExitStillClearsVerifiedReplayIdentity() {
        AtomicInteger consumptions = new AtomicInteger();
        Predicate<String> consume = token -> consumptions.incrementAndGet() == 1;
        try {
            try (ReplayRequestScope ignored = ReplayRequestScope.open()) {
                assertTrue(ReplayRequestScope.accept("replay", consume));
                throw new IllegalStateException("framework-launch-failed");
            }
        } catch (IllegalStateException expected) {
            assertEquals("framework-launch-failed", expected.getMessage());
        }
        assertFalse(ReplayRequestScope.accept("replay", consume));
        assertEquals(2, consumptions.get());
    }

    @Test public void nestedRequestCannotInheritParentTokensAndRestoresItsParentOnExit() {
        AtomicInteger consumptions = new AtomicInteger();
        Set<String> available = new HashSet<>(Arrays.asList("parent", "child"));
        Predicate<String> consume = token -> {
            consumptions.incrementAndGet();
            return available.remove(token);
        };
        try (ReplayRequestScope parent = ReplayRequestScope.open()) {
            assertTrue(ReplayRequestScope.accept("parent", consume));
            try (ReplayRequestScope child = ReplayRequestScope.open()) {
                assertFalse("A nested launch cannot reuse the enclosing launch's verification",
                        ReplayRequestScope.accept("parent", consume));
                assertTrue(ReplayRequestScope.accept("child", consume));
                assertTrue(ReplayRequestScope.accept("child", consume));
            }
            assertTrue("The original request must regain its own verification",
                    ReplayRequestScope.accept("parent", consume));
            assertFalse("The parent cannot inherit its child's verification",
                    ReplayRequestScope.accept("child", consume));
            assertEquals(4, consumptions.get());
        }
        assertFalse(ReplayRequestScope.accept("parent", consume));
        assertEquals(5, consumptions.get());
    }
}
