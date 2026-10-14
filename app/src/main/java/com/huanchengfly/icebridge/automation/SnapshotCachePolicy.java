package com.huanchengfly.icebridge.automation;

/** Pure safeguards for a last-known-good configuration and its owning Android user. */
public final class SnapshotCachePolicy {
    public static final long MAX_AGE_MILLIS = 120_000L;
    private SnapshotCachePolicy() { }
    public static boolean isUsable(long loadedAt, long now) {
        return loadedAt > 0 && now >= loadedAt && now - loadedAt < MAX_AGE_MILLIS;
    }
    public static boolean matchesUser(int snapshotUser, int requestUser) {
        return snapshotUser >= 0 && requestUser >= 0 && snapshotUser == requestUser;
    }
}
