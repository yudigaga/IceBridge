package com.huanchengfly.icebridge.android16;

import org.junit.Test;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IceBridgeModuleNativeBridgePolicyTest {
    private static final String FROZEN = "com.example.frozen";
    private static final String ENABLED = "com.example.enabled";

    @Test
    public void frozenTargetStillUsesBridge() {
        Set<String> frozenPackages = new HashSet<>(Collections.singleton(FROZEN));

        assertFalse(NativeBridgePolicy.shouldBypass(
                false, frozenPackages, FROZEN));
    }

    @Test
    public void enabledOrStaleTargetPassesThroughOriginalIntent() {
        Set<String> frozenPackages = new HashSet<>(Collections.singleton(FROZEN));

        assertTrue(NativeBridgePolicy.shouldBypass(
                false, frozenPackages, ENABLED));
    }

    @Test
    public void emptySnapshotPreservesOriginalIntentUntilStateIsKnown() {
        assertTrue(NativeBridgePolicy.shouldBypass(
                false, Collections.emptySet(), ENABLED));
        assertTrue(NativeBridgePolicy.shouldBypass(
                false, null, ENABLED));
    }

    @Test
    public void sharedHostPrefersTheFrozenCandidate() {
        assertEquals(FROZEN, NativeBridgePolicy.selectCandidate(
                Arrays.asList(ENABLED, FROZEN), Collections.singleton(FROZEN)));
    }


    @Test
    public void multipleFrozenCandidatesAreAmbiguousAndPreserveOriginalIntent() {
        Set<String> frozenPackages = new HashSet<>(Arrays.asList(FROZEN, ENABLED));

        assertEquals(null, NativeBridgePolicy.selectCandidate(
                Arrays.asList(FROZEN, ENABLED), frozenPackages));
    }

    @Test
    public void allEnabledCandidatesPreserveOriginalIntent() {
        assertTrue(NativeBridgePolicy.shouldBypass(
                false, Collections.singleton(ENABLED), FROZEN));
    }

    @Test
    public void emptyCandidatesAreSafe() {
        assertEquals(null, NativeBridgePolicy.selectCandidate(
                Collections.<String>emptyList(), Collections.singleton(FROZEN)));
        assertEquals(null, NativeBridgePolicy.selectCandidate(null, Collections.singleton(FROZEN)));
    }

    @Test
    public void paymentPrewarmDoesNotUseOrdinaryNativePassThroughPolicy() {
        Set<String> frozenPackages = new HashSet<>(Collections.singleton(FROZEN));

        assertFalse(NativeBridgePolicy.shouldBypass(
                true, frozenPackages, ENABLED));
    }
}
