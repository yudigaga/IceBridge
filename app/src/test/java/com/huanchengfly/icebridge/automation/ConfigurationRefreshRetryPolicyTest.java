package com.huanchengfly.icebridge.automation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConfigurationRefreshRetryPolicyTest {
    @Test public void firstFailedRefreshRetriesBeforeTheOldMinuteWindow() {
        ConfigurationRefreshRetryPolicy policy = new ConfigurationRefreshRetryPolicy();

        long retryAt = policy.nextDelayMillis(false);

        assertEquals(2_000L, retryAt);
        assertTrue(retryAt < 60_000L);
        // The next attempt is now healthy; it returns to the normal refresh cadence.
        assertEquals(60_000L, policy.nextDelayMillis(true));
    }

    @Test public void consecutiveFailuresBackOffThenRemainCapped() {
        ConfigurationRefreshRetryPolicy policy = new ConfigurationRefreshRetryPolicy();
        long[] expected = {2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 60_000L};

        for (long delay : expected) assertEquals(delay, policy.nextDelayMillis(false));
        for (int attempt = 0; attempt < 10_000; attempt++) {
            assertEquals(60_000L, policy.nextDelayMillis(false));
        }
    }

    @Test public void successfulRefreshResetsAnExhaustedFailureSequence() {
        ConfigurationRefreshRetryPolicy policy = new ConfigurationRefreshRetryPolicy();
        for (int attempt = 0; attempt < 20; attempt++) policy.nextDelayMillis(false);

        assertEquals(60_000L, policy.nextDelayMillis(true));
        assertEquals(2_000L, policy.nextDelayMillis(false));
        assertEquals(4_000L, policy.nextDelayMillis(false));
    }

    @Test public void repeatedHealthyRefreshesDoNotAdvanceTheFailureSequence() {
        ConfigurationRefreshRetryPolicy policy = new ConfigurationRefreshRetryPolicy();

        for (int attempt = 0; attempt < 20; attempt++) {
            assertEquals(60_000L, policy.nextDelayMillis(true));
        }
        assertEquals(2_000L, policy.nextDelayMillis(false));
    }
}
