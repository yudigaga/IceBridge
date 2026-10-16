package com.huanchengfly.icebridge.android16;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure policy for deciding whether a native link should preserve its original Intent. */
public final class NativeBridgePolicy {
    private NativeBridgePolicy() {
    }

    /** A caller token is also present on ordinary starts; requestCode=-1 proves no result is owed. */
    public static boolean canReplaceWebSource(Integer requestCode, boolean callerBoundPayload) {
        return requestCode != null && requestCode == -1 && !callerBoundPayload;
    }

    /**
     * Selects the candidate that is actually frozen when several applications share one host
     * rule. If the snapshot is usable but none of the candidates is frozen, retain the rule
     * order; the caller will then preserve the original Intent rather than bridge it.
     */
    public static String selectCandidate(List<String> candidates, Set<String> frozenPackages) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (frozenPackages != null && !frozenPackages.isEmpty()) {
            Set<String> frozenCandidates = new HashSet<>();
            for (String candidate : candidates) {
                if (candidate != null && frozenPackages.contains(candidate)) {
                    frozenCandidates.add(candidate);
                }
            }
            // A shared host with multiple frozen targets is ambiguous. Choosing by rule order
            // can wake the wrong app, so leave the original resolver in control instead.
            if (frozenCandidates.size() == 1) {
                return frozenCandidates.iterator().next();
            }
            if (frozenCandidates.size() > 1) {
                return null;
            }
        }
        return candidates.get(0);
    }

    public static boolean shouldBypass(boolean prewarmOnly, Set<String> frozenPackages,
                                       String target) {
        return shouldBypass(prewarmOnly, false, frozenPackages, target);
    }

    public static boolean shouldBypass(boolean prewarmOnly, boolean matchedWebRule,
                                       Set<String> frozenPackages, String target) {
        return shouldBypass(prewarmOnly, matchedWebRule, true, frozenPackages, target);
    }

    public static boolean shouldBypass(boolean prewarmOnly, boolean matchedWebRule,
                                       boolean onlyFrozen, Set<String> frozenPackages, String target) {
        // Enabled-target replacement is limited to reviewed rules with unified routing enabled.
        if (matchedWebRule && !onlyFrozen && target != null) return false;
        // Payment prewarming is deliberately independent: Alipay keeps its original SDK intent.
        if (prewarmOnly) {
            return false;
        }
        // An empty/null snapshot means the package state is unknown. Do not take ownership of a
        // user launch in that state: doing so can create a second launch when the platform later
        // resolves the original request. A later click will use the refreshed snapshot.
        if (frozenPackages == null || frozenPackages.isEmpty() || target == null) {
            return true;
        }
        return !frozenPackages.contains(target);
    }
}
