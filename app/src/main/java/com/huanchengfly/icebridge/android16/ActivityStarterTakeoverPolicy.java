package com.huanchengfly.icebridge.android16;

/** Value-only guards for cancelling one framework request after its route has been reserved. */
public final class ActivityStarterTakeoverPolicy {
    /** Framework abort, converted by ActivityStarter.getExternalResult to caller-visible success. */
    public static final int START_ABORTED = 102;

    private ActivityStarterTakeoverPolicy() { }

    public static boolean callerMatches(String sourcePackage, String callingPackage,
                                        int binderUid, int requestedUid, int realCallingUid) {
        return sourcePackage != null && !sourcePackage.isEmpty()
                && sourcePackage.equals(callingPackage) && binderUid >= 0
                && (requestedUid == -1 || requestedUid == binderUid)
                && (realCallingUid == -1 || realCallingUid == binderUid);
    }

    /** Uses identity, not equals: another ActivityStarter or a recycled Request must pass through. */
    public static final class Frame<T> {
        public final T reservation;
        private final Object starter;
        private final Object request;
        private boolean suppressed;

        public Frame(Object starter, Object request, T reservation) {
            if (starter == null || request == null || reservation == null) {
                throw new IllegalArgumentException("takeover-frame-incomplete");
            }
            this.starter = starter;
            this.request = request;
            this.reservation = reservation;
        }

        public boolean owns(Object candidateStarter, Object candidateRequest) {
            return !suppressed && starter == candidateStarter && request == candidateRequest;
        }

        /** Call only after all fallible eligibility checks and reflective writes have succeeded. */
        public boolean suppress(Object candidateStarter, Object candidateRequest) {
            if (!owns(candidateStarter, candidateRequest)) return false;
            suppressed = true;
            return true;
        }

        public boolean wasSuppressed() { return suppressed; }
    }
}
