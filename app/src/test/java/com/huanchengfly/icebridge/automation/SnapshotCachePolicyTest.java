package com.huanchengfly.icebridge.automation;

import org.junit.Test;
import static org.junit.Assert.*;

public class SnapshotCachePolicyTest {
    @Test public void lastGoodSnapshotExpiresAndClockResetCannotMakeItFresh() {
        assertTrue(SnapshotCachePolicy.isUsable(1000, 1001));
        assertFalse(SnapshotCachePolicy.isUsable(1000, 1000 + SnapshotCachePolicy.MAX_AGE_MILLIS));
        assertFalse(SnapshotCachePolicy.isUsable(1000, 999));
        assertFalse(SnapshotCachePolicy.isUsable(0, 1000));
    }

    @Test public void unknownOrDifferentUserCannotConsumeOwnerSnapshot() {
        assertTrue(SnapshotCachePolicy.matchesUser(0, 0));
        assertFalse(SnapshotCachePolicy.matchesUser(0, 10));
        assertFalse(SnapshotCachePolicy.matchesUser(-1, -1));
        assertFalse(SnapshotCachePolicy.matchesUser(0, -10000));
    }
}
