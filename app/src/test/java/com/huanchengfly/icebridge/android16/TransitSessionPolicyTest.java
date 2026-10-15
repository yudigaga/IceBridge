package com.huanchengfly.icebridge.android16;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransitSessionPolicyTest {
    private static final String TARGET = "com.example.target";
    private final TransitSessionPolicy policy = new TransitSessionPolicy();

    @Test public void sourceRemainsBoundToItsTransactionAcrossStagesAndSubmission() {
        assertTrue(policy.stage(1, 0, "com.example.source", TARGET, TransitSessionPolicy.THAWING,
                0xff2979ff, "light", 100));
        assertEquals("com.example.source", policy.active().sourcePackage);
        assertTrue(policy.stage(1, 0, "com.example.other", TARGET, TransitSessionPolicy.OPENING,
                0xff2979ff, "light", 200));
        assertEquals("com.example.source", policy.active().sourcePackage);
        assertTrue(policy.submitted(1));
        assertEquals("com.example.source", policy.active().sourcePackage);
        assertTrue(policy.active().submitted);
    }

    @Test public void replacementUsesItsOwnSourceAndOldCallbacksCannotRestoreThePriorSource() {
        assertTrue(policy.stage(1, 0, "com.example.first", TARGET, TransitSessionPolicy.OPENING,
                0xff2979ff, "light", 100));
        assertTrue(policy.stage(2, 0, "com.example.second", TARGET, TransitSessionPolicy.OPENING,
                0xff2979ff, "light", 200));
        assertFalse(policy.stage(1, 0, "com.example.first", TARGET, TransitSessionPolicy.OPENING,
                0xff2979ff, "light", 300));
        assertFalse(policy.submitted(1));
        assertFalse(policy.finish(1));
        assertEquals("com.example.second", policy.active().sourcePackage);
    }

    @Test public void unknownAndUnboundedSourcesUseTheGenericSource() {
        String[] sources = {null, "", " ", "*", " * ", new String(new char[256]).replace('\0', 'x')};
        for (int index = 0; index < sources.length; index++) {
            assertTrue(policy.stage(index + 1, 0, sources[index], TARGET, TransitSessionPolicy.OPENING,
                    0xff2979ff, "light", 100));
            assertNull(policy.active().sourcePackage);
        }
    }

    @Test public void legacyStageSignatureSupportsUnknownSourceAndKeepsAnExistingSource() {
        begin(1, TransitSessionPolicy.THAWING, 100);
        assertNull(policy.active().sourcePackage);
        assertTrue(policy.stage(2, 0, "com.example.source", TARGET, TransitSessionPolicy.THAWING,
                0xff2979ff, "light", 200));
        begin(2, TransitSessionPolicy.OPENING, 300);
        assertEquals("com.example.source", policy.active().sourcePackage);
    }

    @Test public void acceptingLaunchDoesNotClaimThatTargetIsDisplayed() {
        begin(1, TransitSessionPolicy.OPENING, 100);
        assertTrue(policy.submitted(1));
        assertTrue(policy.active().submitted);
        assertTrue(policy.targetDrawn(1, 0, TARGET));
        assertNull(policy.active());
    }

    @Test public void drawCanArriveBeforeTheLaunchBinderReturns() {
        begin(1, TransitSessionPolicy.OPENING, 100);
        assertTrue(policy.targetDrawn(1, 0, TARGET));
        assertFalse(policy.submitted(1));
        assertNull(policy.active());
    }

    @Test public void historicalTargetDrawCannotFinishThawing() {
        begin(1, TransitSessionPolicy.THAWING, 100);
        assertFalse(policy.targetDrawn(1, 0, TARGET));
        assertNotNull(policy.active());
    }

    @Test public void anotherPackageOrUserCannotDismissCurrentTransaction() {
        begin(1, TransitSessionPolicy.OPENING, 100);
        assertFalse(policy.targetDrawn(1, 10, TARGET));
        assertFalse(policy.targetDrawn(1, 0, "com.example.other"));
        assertNotNull(policy.active());
    }

    @Test public void newerTransactionReplacesOldCardAndKeepsItsOwnDeadline() {
        begin(1, TransitSessionPolicy.THAWING, 100);
        begin(2, TransitSessionPolicy.OPENING, 1000);
        assertEquals(2, policy.active().id);
        assertEquals(13000, policy.active().deadline);
        assertFalse(policy.timeout(1, 12000));
        assertFalse(policy.finish(1));
        assertFalse(policy.targetDrawn(1, 0, TARGET));
        assertEquals(2, policy.active().id);
    }

    @Test public void oldStageCannotReplaceANewerTransaction() {
        begin(2, TransitSessionPolicy.OPENING, 100);
        assertFalse(policy.stage(1, 0, TARGET, TransitSessionPolicy.THAWING, 0xff2979ff, "light", 200));
        assertEquals(2, policy.active().id);
    }

    @Test public void lateStageCannotResurrectFinishedCard() {
        begin(1, TransitSessionPolicy.OPENING, 100);
        assertTrue(policy.finish(1));
        assertFalse(policy.stage(1, 0, TARGET, TransitSessionPolicy.OPENING, 0xff2979ff, "light", 200));
        assertNull(policy.active());
        begin(2, TransitSessionPolicy.THAWING, 300);
        assertEquals(2, policy.active().id);
    }

    @Test public void stageChangeCannotKeepCardAliveForever() {
        begin(1, TransitSessionPolicy.THAWING, 100);
        begin(1, TransitSessionPolicy.OPENING, 12000);
        assertEquals(12100, policy.active().deadline);
        assertFalse(policy.timeout(1, 12099));
        assertTrue(policy.timeout(1, 12100));
        assertNull(policy.active());
    }

    @Test public void stageCannotRegressOrChangeTargetForSameTransaction() {
        begin(1, TransitSessionPolicy.OPENING, 100);
        assertFalse(policy.stage(1, 0, TARGET, TransitSessionPolicy.THAWING, 0, "light", 200));
        assertFalse(policy.stage(1, 0, "com.example.other", TransitSessionPolicy.OPENING, 0, "light", 200));
        assertFalse(policy.stage(1, 10, TARGET, TransitSessionPolicy.OPENING, 0, "light", 200));
        assertEquals(TransitSessionPolicy.OPENING, policy.active().stage);
    }

    @Test public void screenOffCancellationCannotBeUndoneByAnOldPhase() {
        begin(1, TransitSessionPolicy.THAWING, 100);
        assertTrue(policy.cancelAll());
        assertFalse(policy.stage(1, 0, TARGET, TransitSessionPolicy.OPENING, 0, "dark", 200));
        assertFalse(policy.cancelAll());
    }

    @Test public void malformedPresentationCannotAllocateASession() {
        assertFalse(policy.stage(0, 0, TARGET, TransitSessionPolicy.OPENING, 0, "light", 100));
        assertFalse(policy.stage(1, -1, TARGET, TransitSessionPolicy.OPENING, 0, "light", 100));
        assertFalse(policy.stage(1, 0, "", TransitSessionPolicy.OPENING, 0, "light", 100));
        assertFalse(policy.stage(1, 0, TARGET, 99, 0, "light", 100));
        assertNull(policy.active());
    }

    @Test public void appearanceIsBoundedAndSeedIsOpaque() {
        policy.stage(1, 0, TARGET, TransitSessionPolicy.THAWING, 0x002979ff, "unexpected", 100);
        assertEquals("system", policy.active().appearance);
        assertEquals(0xff2979ff, policy.active().seed);
    }

    @Test public void lightAndDarkCardsKeepSeedFamilyWithReadableText() {
        int[] seeds = {0xff2979ff, 0xff00796b, 0xff7e57c2, 0xffc54977, 0xffffee00, 0xff808080};
        for (int seed : seeds) {
            TransitSessionPolicy.Palette light = TransitSessionPolicy.Palette.fromSeed(seed, false);
            TransitSessionPolicy.Palette dark = TransitSessionPolicy.Palette.fromSeed(seed, true);
            assertNotEquals(light.card, dark.card);
            for (TransitSessionPolicy.Palette palette : new TransitSessionPolicy.Palette[]{light, dark}) {
                for (int surface : new int[]{palette.card, palette.inset}) {
                    assertTrue(TransitSessionPolicy.Palette.contrast(palette.title, surface) >= 4.5);
                    assertTrue(TransitSessionPolicy.Palette.contrast(palette.secondary, surface) >= 4.5);
                    assertTrue(TransitSessionPolicy.Palette.contrast(palette.accent, surface) >= 4.5);
                }
            }
        }
    }

    @Test public void neutralThemeNeverPicksAnUnrelatedHue() {
        for (boolean dark : new boolean[]{false, true}) {
            TransitSessionPolicy.Palette palette = TransitSessionPolicy.Palette.fromSeed(0xff808080, dark);
            for (int color : new int[]{palette.card, palette.inset, palette.title, palette.secondary, palette.accent}) {
                assertEquals((color >>> 16) & 255, (color >>> 8) & 255);
                assertEquals((color >>> 8) & 255, color & 255);
            }
        }
    }

    private void begin(long id, int stage, long now) {
        assertTrue(policy.stage(id, 0, TARGET, stage, 0xff2979ff, "light", now));
    }
}
