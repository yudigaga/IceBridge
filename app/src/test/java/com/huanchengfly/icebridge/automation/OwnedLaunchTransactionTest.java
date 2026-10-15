package com.huanchengfly.icebridge.automation;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.huanchengfly.icebridge.automation.OwnedLaunchTransaction.Outcome;
import static org.junit.Assert.*;

public class OwnedLaunchTransactionTest {
    @Test public void successfulRouteNeverReplaysAndCannotBeExecutedAgain() {
        OwnedLaunchTransaction transaction = new OwnedLaunchTransaction();
        AtomicInteger routes = new AtomicInteger();
        AtomicInteger replays = new AtomicInteger();
        OwnedLaunchTransaction.Operation route = () -> { routes.incrementAndGet(); return true; };
        OwnedLaunchTransaction.Operation replay = () -> { replays.incrementAndGet(); return true; };
        assertEquals(Outcome.ROUTED, transaction.execute(route, replay));
        assertEquals(Outcome.IGNORED, transaction.execute(route, replay));
        assertEquals(Outcome.IGNORED, transaction.abandon(replay));
        assertFalse(transaction.cancel());
        assertEquals(1, routes.get());
        assertEquals(0, replays.get());
    }

    @Test public void falseCheckedExceptionAndErrorEachReplayOriginalExactlyOnce() {
        for (int failure = 0; failure < 3; failure++) {
            OwnedLaunchTransaction transaction = new OwnedLaunchTransaction();
            AtomicInteger routes = new AtomicInteger();
            AtomicInteger replays = new AtomicInteger();
            final int mode = failure;
            OwnedLaunchTransaction.Operation route = () -> {
                routes.incrementAndGet();
                if (mode == 1) throw new Exception("route-failed");
                if (mode == 2) throw new AssertionError("route-error");
                return false;
            };
            OwnedLaunchTransaction.Operation replay = () -> { replays.incrementAndGet(); return true; };
            assertEquals(Outcome.REPLAYED, transaction.execute(route, replay));
            assertEquals(Outcome.IGNORED, transaction.abandon(replay));
            assertEquals(Outcome.IGNORED, transaction.execute(route, replay));
            assertEquals(1, routes.get());
            assertEquals(1, replays.get());
        }
    }

    @Test public void replayFailureOrThrowableConsumesTheOnlyReplayAttempt() {
        for (int failure = 0; failure < 3; failure++) {
            OwnedLaunchTransaction transaction = new OwnedLaunchTransaction();
            AtomicInteger replays = new AtomicInteger();
            final int mode = failure;
            OwnedLaunchTransaction.Operation replay = () -> {
                replays.incrementAndGet();
                if (mode == 1) throw new Exception("replay-failed");
                if (mode == 2) throw new AssertionError("replay-error");
                return false;
            };
            assertEquals(Outcome.REPLAY_FAILED, transaction.execute(() -> false, replay));
            assertEquals(Outcome.IGNORED, transaction.abandon(replay));
            assertFalse(transaction.cancel());
            assertEquals(1, replays.get());
        }
    }

    @Test public void cancellationConsumesOwnershipWithoutRoutingOrReplaying() {
        OwnedLaunchTransaction transaction = new OwnedLaunchTransaction();
        AtomicInteger operations = new AtomicInteger();
        OwnedLaunchTransaction.Operation unexpected = () -> { operations.incrementAndGet(); return true; };
        assertTrue(transaction.cancel());
        assertFalse(transaction.cancel());
        assertEquals(Outcome.IGNORED, transaction.execute(unexpected, unexpected));
        assertEquals(Outcome.IGNORED, transaction.abandon(unexpected));
        assertEquals(0, operations.get());
    }

    @Test public void abandonmentReplaysOnlyOnceWithoutASeparateExecution() {
        OwnedLaunchTransaction transaction = new OwnedLaunchTransaction();
        AtomicInteger replays = new AtomicInteger();
        OwnedLaunchTransaction.Operation replay = () -> { replays.incrementAndGet(); return true; };
        assertEquals(Outcome.REPLAYED, transaction.abandon(replay));
        assertEquals(Outcome.IGNORED, transaction.abandon(replay));
        assertEquals(Outcome.IGNORED, transaction.execute(() -> true, replay));
        assertEquals(1, replays.get());
    }

    @Test public void runningRouteCannotBeCancelledOrReplayedByConcurrentCallers() throws Exception {
        OwnedLaunchTransaction transaction = new OwnedLaunchTransaction();
        AtomicInteger routes = new AtomicInteger();
        AtomicInteger replays = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OwnedLaunchTransaction.Operation replay = () -> { replays.incrementAndGet(); return true; };
        try {
            Future<Outcome> running = executor.submit(() -> transaction.execute(() -> {
                routes.incrementAndGet();
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new Exception("route-release-timeout");
                return true;
            }, replay));
            assertTrue("The winning route must have actually started", started.await(5, TimeUnit.SECONDS));
            assertFalse("An in-flight route already consumed ownership", transaction.cancel());
            assertEquals(Outcome.IGNORED, transaction.abandon(replay));
            assertEquals(Outcome.IGNORED, transaction.execute(() -> {
                routes.incrementAndGet();
                return false;
            }, replay));
            assertEquals(0, replays.get());
            release.countDown();
            assertEquals(Outcome.ROUTED, running.get(5, TimeUnit.SECONDS));
            assertEquals(1, routes.get());
            assertEquals(0, replays.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
