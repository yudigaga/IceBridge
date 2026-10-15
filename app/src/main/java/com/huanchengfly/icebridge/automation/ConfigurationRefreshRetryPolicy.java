package com.huanchengfly.icebridge.automation;

/** Worker-owned retry timing for incomplete system configuration initialization. */
public final class ConfigurationRefreshRetryPolicy {
    private static final long HEALTHY_INTERVAL_MILLIS = 60_000L;
    private static final long[] RETRY_DELAYS_MILLIS = {
            2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 60_000L
    };
    private int nextFailureIndex;

    /** A healthy refresh uses the normal interval and resets initialization failures. */
    public long nextDelayMillis(boolean healthy) {
        if (healthy) {
            nextFailureIndex = 0;
            return HEALTHY_INTERVAL_MILLIS;
        }
        long delay = RETRY_DELAYS_MILLIS[nextFailureIndex];
        // Saturate the index so a long-lived unavailable service cannot overflow a counter.
        if (nextFailureIndex < RETRY_DELAYS_MILLIS.length - 1) nextFailureIndex++;
        return delay;
    }
}
