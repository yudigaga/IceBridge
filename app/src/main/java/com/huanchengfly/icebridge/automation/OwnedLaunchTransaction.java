package com.huanchengfly.icebridge.automation;

import java.util.concurrent.atomic.AtomicBoolean;

/** A one-use owner of a captured launch, including its single original-request replay. */
public final class OwnedLaunchTransaction {
    public enum Outcome { ROUTED, REPLAYED, REPLAY_FAILED, IGNORED }

    @FunctionalInterface
    public interface Operation {
        boolean run() throws Exception;
    }

    private final AtomicBoolean consumed = new AtomicBoolean();

    /** Release an unstarted owner without routing or replaying anything. */
    public boolean cancel() {
        return consumed.compareAndSet(false, true);
    }

    /**
     * Ownership is consumed before either operation runs. Later cancellation, abandonment, and
     * duplicate execution cannot create a second launch, including while the first is still running.
     */
    public Outcome execute(Operation route, Operation replay) {
        if (!consumed.compareAndSet(false, true)) return Outcome.IGNORED;
        try {
            if (route.run()) return Outcome.ROUTED;
        } catch (Throwable ignored) {
            // A failed route still owns the original request and must attempt its one replay.
        }
        try {
            return replay.run() ? Outcome.REPLAYED : Outcome.REPLAY_FAILED;
        } catch (Throwable ignored) {
            return Outcome.REPLAY_FAILED;
        }
    }

    /** Give up routing while retaining the same single-replay ownership rule. */
    public Outcome abandon(Operation replay) {
        return execute(() -> false, replay);
    }
}
