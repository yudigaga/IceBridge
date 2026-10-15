package com.huanchengfly.icebridge.ui.theme;

import org.junit.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import static org.junit.Assert.*;

public class ThemePolicyTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final IntUnaryOperator FIRST = bound -> 0;

    @Test public void freshInstallUsesFixedBlueAndSystemAppearance() {
        ThemePolicy.State state = ThemePolicy.initial();
        assertFalse(state.isRandomEnabled());
        assertEquals(ThemePolicy.DEFAULT_SEED_COLOR, state.getSeedColor());
        assertEquals(ThemePolicy.APPEARANCE_SYSTEM, state.getAppearance());
        assertEquals(ThemePolicy.INTERVAL_DAILY, state.getRandomInterval());
    }

    @Test public void fixedColorIsOpaqueAndSurvivesDatesAndSessions() {
        ThemePolicy.State fixed = ThemePolicy.setFixedSeedColor(ThemePolicy.initial(), 0x33447799);
        assertEquals(0xff447799, fixed.getSeedColor());
        assertSame(fixed, ThemePolicy.resolve(fixed, TODAY.plusDays(1), failingRandom()));
        assertSame(fixed, ThemePolicy.beginUserSession(fixed, TODAY.plusDays(3), failingRandom()));
    }

    @Test public void enablingRandomChoosesImmediatelyAndDiffersFromVisibleFixedColor() {
        ThemePolicy.State state = ThemePolicy.setRandomEnabled(ThemePolicy.initial(), true, TODAY, FIRST);
        assertTrue(state.isRandomEnabled());
        assertNotEquals(ThemePolicy.DEFAULT_SEED_COLOR, state.getSeedColor());
        assertEquals(TODAY.toString(), state.getRandomDate());
    }

    @Test public void repeatedEnableAndDailyReadsDoNotConsumeRandomness() {
        ThemePolicy.State state = ThemePolicy.setRandomEnabled(ThemePolicy.initial(), true, TODAY, FIRST);
        assertSame(state, ThemePolicy.setRandomEnabled(state, true, TODAY, failingRandom()));
        assertSame(state, ThemePolicy.resolve(state, TODAY, failingRandom()));
        assertSame(state, ThemePolicy.beginUserSession(state, TODAY, failingRandom()));
    }

    @Test public void dailyRotatesOnceAtNewLocalDate() {
        AtomicInteger draws = new AtomicInteger();
        IntUnaryOperator random = bound -> { draws.incrementAndGet(); return 0; };
        ThemePolicy.State first = ThemePolicy.setRandomEnabled(ThemePolicy.initial(), true, TODAY, random);
        ThemePolicy.State next = ThemePolicy.resolve(first, TODAY.plusDays(1), random);
        assertNotEquals(first.getSeedColor(), next.getSeedColor());
        assertEquals(TODAY.plusDays(1).toString(), next.getRandomDate());
        assertSame(next, ThemePolicy.resolve(next, TODAY.plusDays(1), random));
        assertEquals(2, draws.get());
    }

    @Test public void dailyUsesDateEqualitySoClockOrTimezoneReversalDoesNotFreezeColor() {
        ThemePolicy.State first = ThemePolicy.setRandomEnabled(ThemePolicy.initial(), true, TODAY, FIRST);
        ThemePolicy.State earlier = ThemePolicy.resolve(first, TODAY.minusDays(1), FIRST);
        assertNotEquals(first.getSeedColor(), earlier.getSeedColor());
        assertEquals(TODAY.minusDays(1).toString(), earlier.getRandomDate());
    }

    @Test public void launchModeDoesNotRotateForForegroundRefreshOrMidnight() {
        ThemePolicy.State state = launchState();
        assertSame(state, ThemePolicy.resolve(state, TODAY, failingRandom()));
        assertSame(state, ThemePolicy.resolve(state, TODAY.plusDays(1), failingRandom()));
    }

    @Test public void launchModeChangesOnlyOnExplicitUserSession() {
        ThemePolicy.State first = launchState();
        ThemePolicy.State second = ThemePolicy.beginUserSession(first, TODAY, FIRST);
        assertNotEquals(first.getSeedColor(), second.getSeedColor());
        ThemePolicy.State third = ThemePolicy.beginUserSession(second, TODAY, FIRST);
        assertNotEquals(second.getSeedColor(), third.getSeedColor());
        assertSame(third, ThemePolicy.resolve(third, TODAY.plusDays(2), failingRandom()));
    }

    @Test public void launchInitializationDrawsOnlyOnce() {
        ThemePolicy.State restored = ThemePolicy.restore(ThemePolicy.DEFAULT_SEED_COLOR, true,
                ThemePolicy.INTERVAL_LAUNCH, ThemePolicy.APPEARANCE_SYSTEM, 0, "");
        AtomicInteger draws = new AtomicInteger();
        ThemePolicy.State state = ThemePolicy.beginUserSession(restored, TODAY,
                bound -> { draws.incrementAndGet(); return 0; });
        assertEquals(1, draws.get());
        assertSame(state, ThemePolicy.resolve(state, TODAY, failingRandom()));
    }

    @Test public void disablingRandomRestoresTheSavedCustomFixedColor() {
        ThemePolicy.State fixed = ThemePolicy.setFixedSeedColor(ThemePolicy.initial(), 0xffa33bb2);
        ThemePolicy.State random = ThemePolicy.setRandomEnabled(fixed, true, TODAY, FIRST);
        ThemePolicy.State disabled = ThemePolicy.setRandomEnabled(random, false, TODAY, failingRandom());
        assertEquals(fixed.getSeedColor(), disabled.getSeedColor());
        assertEquals(fixed.getFixedSeedColor(), random.getFixedSeedColor());
    }

    @Test public void changingFixedColorDuringRandomModePreservesCurrentRandomColor() {
        ThemePolicy.State random = launchState();
        ThemePolicy.State updated = ThemePolicy.setFixedSeedColor(random, 0xffaabbcc);
        assertEquals(random.getSeedColor(), updated.getSeedColor());
        assertTrue(updated.isRandomEnabled());
        assertEquals(0xffaabbcc,
                ThemePolicy.setRandomEnabled(updated, false, TODAY, failingRandom()).getSeedColor());
    }

    @Test public void changingCadencePreservesColorAndStartsNewDailyWindowToday() {
        ThemePolicy.State first = launchState();
        ThemePolicy.State daily = ThemePolicy.setRandomInterval(first, ThemePolicy.INTERVAL_DAILY,
                TODAY.plusDays(2), failingRandom());
        assertEquals(first.getSeedColor(), daily.getSeedColor());
        assertEquals(TODAY.plusDays(2).toString(), daily.getRandomDate());
        assertSame(daily, ThemePolicy.resolve(daily, TODAY.plusDays(2), failingRandom()));
        ThemePolicy.State launch = ThemePolicy.setRandomInterval(daily, ThemePolicy.INTERVAL_LAUNCH,
                TODAY.plusDays(2), failingRandom());
        assertEquals(daily.getSeedColor(), launch.getSeedColor());
    }

    @Test public void lightDarkAndSystemChangesRetainExactColorFamilyAndRandomAnchor() {
        ThemePolicy.State original = launchState();
        ThemePolicy.State state = original;
        for (String appearance : new String[]{ThemePolicy.APPEARANCE_LIGHT,
                ThemePolicy.APPEARANCE_DARK, ThemePolicy.APPEARANCE_SYSTEM}) {
            state = ThemePolicy.setAppearance(state, appearance);
            assertEquals(appearance, state.getAppearance());
            assertEquals(original.getSeedColor(), state.getSeedColor());
            assertEquals(original.getFixedSeedColor(), state.getFixedSeedColor());
            assertEquals(original.getRandomDate(), state.getRandomDate());
            assertEquals(original.getRandomInterval(), state.getRandomInterval());
        }
    }

    @Test public void persistedRoundTripPreservesCurrentColorAcrossProcessDeath() {
        ThemePolicy.State original = ThemePolicy.setAppearance(launchState(), ThemePolicy.APPEARANCE_DARK);
        ThemePolicy.State restored = roundTrip(original);
        assertEquals(original, restored);
        assertEquals(original.hashCode(), restored.hashCode());
        assertSame(restored, ThemePolicy.resolve(restored, TODAY.plusDays(5), failingRandom()));
    }

    @Test public void restoredDailyColorStaysStableWithinDayAndRotatesOnNextDay() {
        ThemePolicy.State original = ThemePolicy.setRandomEnabled(ThemePolicy.initial(), true, TODAY, FIRST);
        ThemePolicy.State restored = roundTrip(original);
        assertSame(restored, ThemePolicy.resolve(restored, TODAY, failingRandom()));
        assertNotEquals(restored.getSeedColor(),
                ThemePolicy.resolve(restored, TODAY.plusDays(1), FIRST).getSeedColor());
    }

    @Test public void invalidPersistedOptionsAndDateRecoverToSafeDefaults() {
        ThemePolicy.State broken = ThemePolicy.restore(0x123456, true, "weekly", "broken", 0xff123456,
                "2026-99-72");
        assertEquals(0xff123456, broken.getFixedSeedColor());
        assertEquals(ThemePolicy.INTERVAL_DAILY, broken.getRandomInterval());
        assertEquals(ThemePolicy.APPEARANCE_SYSTEM, broken.getAppearance());
        assertEquals("", broken.getRandomDate());
        ThemePolicy.State repaired = ThemePolicy.resolve(broken, TODAY, FIRST);
        assertEquals(TODAY.toString(), repaired.getRandomDate());
        assertNotEquals(broken.getSeedColor(), repaired.getSeedColor());
    }

    @Test public void everyRandomCandidateIsReachableWithoutEverRepeatingPreviousSeed() {
        int[] seeds = ThemePolicy.seedColors();
        for (int previous : seeds) {
            ThemePolicy.State state = ThemePolicy.restore(ThemePolicy.DEFAULT_SEED_COLOR, true,
                    ThemePolicy.INTERVAL_LAUNCH, ThemePolicy.APPEARANCE_SYSTEM, previous, TODAY.toString());
            Set<Integer> reachable = new HashSet<>();
            for (int choice = 0; choice < seeds.length - 1; choice++) {
                final int selected = choice;
                int color = ThemePolicy.beginUserSession(state, TODAY, bound -> {
                    assertEquals(seeds.length - 1, bound);
                    return selected;
                }).getSeedColor();
                assertNotEquals(previous, color);
                assertEquals(0xff000000, color & 0xff000000);
                reachable.add(color);
            }
            assertEquals(seeds.length - 1, reachable.size());
        }
    }

    @Test public void seedPaletteCannotBeMutatedByCallers() {
        int first = ThemePolicy.seedColors()[0];
        ThemePolicy.seedColors()[0] = 0;
        assertEquals(first, ThemePolicy.seedColors()[0]);
    }

    private static ThemePolicy.State launchState() {
        ThemePolicy.State interval = ThemePolicy.setRandomInterval(ThemePolicy.initial(),
                ThemePolicy.INTERVAL_LAUNCH, TODAY, failingRandom());
        return ThemePolicy.setRandomEnabled(interval, true, TODAY, FIRST);
    }

    private static ThemePolicy.State roundTrip(ThemePolicy.State state) {
        return ThemePolicy.restore(state.getFixedSeedColor(), state.isRandomEnabled(),
                state.getRandomInterval(), state.getAppearance(), state.getRandomSeedColor(),
                state.getRandomDate());
    }

    private static IntUnaryOperator failingRandom() {
        return bound -> { throw new AssertionError("This operation must not draw a new theme color"); };
    }
}
