package com.huanchengfly.icebridge.android16;

/** Correlates a drawn destination window without requiring an app's routing Intent to copy extras. */
public final class TransitWindowCorrelation {
    private static final long MAX_AGE_MILLIS = 20_000L;

    public interface Pending {
        long id();
        int userId();
        String targetPackage();
        long startedAt();
        long openingAt();
        boolean completed();
    }

    private TransitWindowCorrelation() { }

    /**
     * An explicit token can only identify its own transaction. An untagged same-package destination
     * may finish the most recent eligible opening, including after an app's no-display trampoline.
     */
    public static Pending match(Iterable<? extends Pending> pending, long explicitId,
                                int user, String packageName, long frameAt) {
        if (pending == null || explicitId < 0 || user < 0 || packageName == null || packageName.isEmpty()) return null;
        Pending newest = null;
        for (Pending candidate : pending) {
            if (!eligible(candidate, user, frameAt)
                    || !packageName.equals(candidate.targetPackage())) continue;
            if (explicitId > 0) {
                if (candidate.id() == explicitId) return candidate;
            } else if (newer(candidate, newest)) newest = candidate;
        }
        return explicitId > 0 ? null : newest;
    }

    /** A confirmed system permission window interrupts an opening; it is never target-ready proof. */
    public static Pending interrupted(Iterable<? extends Pending> pending, int user, long frameAt) {
        if (pending == null || user < 0) return null;
        Pending newest = null;
        for (Pending candidate : pending) {
            if (eligible(candidate, user, frameAt) && newer(candidate, newest)) newest = candidate;
        }
        return newest;
    }

    private static boolean eligible(Pending pending, int user, long frameAt) {
        if (pending == null || pending.id() <= 0 || pending.userId() != user || pending.completed()) return false;
        long started = pending.startedAt();
        long opening = pending.openingAt();
        // Compare before subtracting so old queued draw events cannot acknowledge a new launch.
        return started >= 0 && frameAt >= started && frameAt - started <= MAX_AGE_MILLIS
                && opening > 0 && opening <= frameAt;
    }

    private static boolean newer(Pending candidate, Pending previous) {
        if (previous == null) return true;
        if (candidate.openingAt() != previous.openingAt()) return candidate.openingAt() > previous.openingAt();
        if (candidate.startedAt() != previous.startedAt()) return candidate.startedAt() > previous.startedAt();
        return candidate.id() > previous.id();
    }
}
