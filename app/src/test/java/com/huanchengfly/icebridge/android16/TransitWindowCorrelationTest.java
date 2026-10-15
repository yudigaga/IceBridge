package com.huanchengfly.icebridge.android16;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class TransitWindowCorrelationTest {
    private static final String TARGET = "com.example.destination";
    private static final String OTHER = "com.example.other";

    @Test public void untaggedFinalWindowOfMultiHopRouteMatchesTheLatestOpening() {
        Entry old = entry(1, 0, TARGET, 100, 120);
        Entry current = entry(2, 0, TARGET, 200, 230);
        assertSame(current, TransitWindowCorrelation.match(Arrays.asList(current, old),
                0, 0, TARGET, 300));
        assertSame(current, TransitWindowCorrelation.match(Arrays.asList(old, current),
                0, 0, TARGET, 300));
    }

    @Test public void explicitOldTokenOnlyMatchesItsOwnTransaction() {
        Entry old = entry(1, 0, TARGET, 100, 120);
        Entry current = entry(2, 0, TARGET, 200, 230);
        assertSame(old, TransitWindowCorrelation.match(Arrays.asList(current, old),
                1, 0, TARGET, 300));
        assertNull(TransitWindowCorrelation.match(Collections.singletonList(current),
                1, 0, TARGET, 300));
        old.completed = true;
        assertNull(TransitWindowCorrelation.match(Arrays.asList(old, current),
                1, 0, TARGET, 300));
        old.completed = false;
        Entry fresh = entry(3, 0, TARGET, 20_000, 20_010);
        assertNull(TransitWindowCorrelation.match(Arrays.asList(old, fresh),
                1, 0, TARGET, 20_101));
    }

    @Test public void oldQueuedFrameCannotMatchANewerOpening() {
        Entry old = entry(1, 0, TARGET, 100, 120);
        Entry current = entry(2, 0, TARGET, 200, 230);
        assertSame(old, TransitWindowCorrelation.match(Arrays.asList(old, current),
                0, 0, TARGET, 220));
        assertNull(TransitWindowCorrelation.match(Collections.singletonList(current),
                0, 0, TARGET, 220));
        assertNull(TransitWindowCorrelation.match(Collections.singletonList(current),
                2, 0, TARGET, 220));
    }

    @Test public void frameBeforeWorkerStartIsRejectedEvenForExplicitToken() {
        Entry pending = entry(1, 0, TARGET, 200, 210);
        assertNull(TransitWindowCorrelation.match(Collections.singletonList(pending),
                1, 0, TARGET, 199));
        assertNull(TransitWindowCorrelation.interrupted(Collections.singletonList(pending), 0, 199));
    }

    @Test public void packageAndUserBothMustMatchForTargetReadiness() {
        Entry pending = entry(1, 10, TARGET, 100, 120);
        assertNull(TransitWindowCorrelation.match(Collections.singletonList(pending),
                0, 0, TARGET, 200));
        assertNull(TransitWindowCorrelation.match(Collections.singletonList(pending),
                1, 10, OTHER, 200));
        assertSame(pending, TransitWindowCorrelation.match(Collections.singletonList(pending),
                0, 10, TARGET, 200));
    }

    @Test public void selectionOrThawingWithoutOpeningCannotBeAcknowledged() {
        Entry pending = entry(1, 0, TARGET, 100, 0);
        assertNull(TransitWindowCorrelation.match(Collections.singletonList(pending),
                0, 0, TARGET, 200));
        assertNull(TransitWindowCorrelation.match(Collections.singletonList(pending),
                1, 0, TARGET, 200));
        assertNull(TransitWindowCorrelation.interrupted(Collections.singletonList(pending), 0, 200));
    }

    @Test public void completedAndExpiredTransactionsNeverMatch() {
        Entry completed = entry(1, 0, TARGET, 100, 120);
        completed.completed = true;
        Entry expired = entry(2, 0, TARGET, 100, 120);
        assertNull(TransitWindowCorrelation.match(Arrays.asList(completed, expired),
                0, 0, TARGET, 20_101));
        assertNull(TransitWindowCorrelation.match(Arrays.asList(completed, expired),
                1, 0, TARGET, 200));
        assertNull(TransitWindowCorrelation.interrupted(Arrays.asList(completed, expired), 0, 20_101));
    }

    @Test public void expiryBoundaryUsesFrameTimeRatherThanDeliveryTime() {
        Entry pending = entry(1, 0, TARGET, 100, 120);
        assertSame(pending, TransitWindowCorrelation.match(Collections.singletonList(pending),
                0, 0, TARGET, 20_100));
        assertNull(TransitWindowCorrelation.match(Collections.singletonList(pending),
                0, 0, TARGET, 20_101));
    }

    @Test public void permissionInterruptionChoosesNewestEligibleOpeningForItsUser() {
        Entry old = entry(1, 0, TARGET, 100, 120);
        Entry current = entry(2, 0, OTHER, 200, 230);
        Entry otherUser = entry(3, 10, TARGET, 250, 260);
        assertSame(current, TransitWindowCorrelation.interrupted(Arrays.asList(otherUser, old, current), 0, 300));
        assertSame(otherUser, TransitWindowCorrelation.interrupted(Arrays.asList(current, otherUser), 10, 300));
        assertSame(old, TransitWindowCorrelation.interrupted(Arrays.asList(old, current), 0, 220));
    }

    @Test public void permissionInterruptionNeverUsesAFutureOrCompletedOpening() {
        Entry completed = entry(1, 0, TARGET, 100, 120);
        completed.completed = true;
        Entry future = entry(2, 0, OTHER, 200, 230);
        assertNull(TransitWindowCorrelation.interrupted(Arrays.asList(completed, future), 0, 220));
    }

    @Test public void equalOpeningTimeUsesStartTimeThenIdDeterministically() {
        Entry old = entry(1, 0, TARGET, 100, 230);
        Entry laterStart = entry(2, 0, TARGET, 200, 230);
        Entry laterId = entry(3, 0, TARGET, 200, 230);
        assertSame(laterId, TransitWindowCorrelation.match(Arrays.asList(laterId, old, laterStart),
                0, 0, TARGET, 300));
        assertSame(laterId, TransitWindowCorrelation.interrupted(Arrays.asList(laterStart, laterId, old), 0, 300));
    }

    @Test public void nullValuesAndInvalidIdentitiesAreIgnored() {
        Entry invalid = entry(0, 0, TARGET, 100, 120);
        assertNull(TransitWindowCorrelation.match(Arrays.asList(null, invalid), 0, 0, TARGET, 200));
        assertNull(TransitWindowCorrelation.match(null, 0, 0, TARGET, 200));
        assertNull(TransitWindowCorrelation.match(Collections.singletonList(invalid), 0, -1, TARGET, 200));
        assertNull(TransitWindowCorrelation.match(Collections.singletonList(invalid), 0, 0, null, 200));
        assertNull(TransitWindowCorrelation.match(Collections.singletonList(
                entry(1, 0, TARGET, 100, 120)), -1, 0, TARGET, 200));
        assertNull(TransitWindowCorrelation.interrupted(null, 0, 200));
    }

    private static Entry entry(long id, int user, String target, long started, long opening) {
        return new Entry(id, user, target, started, opening);
    }

    private static final class Entry implements TransitWindowCorrelation.Pending {
        final long id;
        final int user;
        final String target;
        final long started;
        final long opening;
        boolean completed;

        Entry(long id, int user, String target, long started, long opening) {
            this.id = id; this.user = user; this.target = target;
            this.started = started; this.opening = opening;
        }

        @Override public long id() { return id; }
        @Override public int userId() { return user; }
        @Override public String targetPackage() { return target; }
        @Override public long startedAt() { return started; }
        @Override public long openingAt() { return opening; }
        @Override public boolean completed() { return completed; }
    }
}
