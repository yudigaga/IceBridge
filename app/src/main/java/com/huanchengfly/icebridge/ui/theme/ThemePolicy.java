package com.huanchengfly.icebridge.ui.theme;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.function.IntUnaryOperator;

/** Pure theme selection policy. A seed identifies a color family, independent of light/dark mode. */
public final class ThemePolicy {
    public static final int DEFAULT_SEED_COLOR = 0xff2979ff;
    public static final String INTERVAL_DAILY = "daily";
    public static final String INTERVAL_LAUNCH = "launch";
    public static final String APPEARANCE_SYSTEM = "system";
    public static final String APPEARANCE_LIGHT = "light";
    public static final String APPEARANCE_DARK = "dark";

    // Bounded, chromatic seeds. The UI derives accessible light/dark tones from the same seed.
    private static final int[] RANDOM_SEEDS = {
            DEFAULT_SEED_COLOR, 0xff00796b, 0xff388e3c, 0xff7e57c2, 0xff6750a4,
            0xffc54977, 0xffc85b4c, 0xffa76a1b, 0xff876d19, 0xff496b8a
    };

    private ThemePolicy() {}

    public static int[] seedColors() { return RANDOM_SEEDS.clone(); }

    public static State initial() {
        return restore(DEFAULT_SEED_COLOR, false, INTERVAL_DAILY, APPEARANCE_SYSTEM, 0, "");
    }

    /** Restores persisted values defensively without drawing a new color or starting a session. */
    public static State restore(int fixedSeedColor, boolean randomEnabled, String randomInterval,
                                String appearance, int randomSeedColor, String randomDate) {
        return new State(opaque(fixedSeedColor), randomEnabled, normalizeInterval(randomInterval),
                normalizeAppearance(appearance), containsSeed(randomSeedColor) ? randomSeedColor : 0,
                normalizeDate(randomDate));
    }

    /** Called for foreground refreshes and at local midnight. Launch mode never rotates here. */
    public static State resolve(State state, LocalDate today, IntUnaryOperator random) {
        Objects.requireNonNull(today, "today");
        if (!state.randomEnabled) return state;
        if (!containsSeed(state.randomSeedColor)
                || (INTERVAL_DAILY.equals(state.randomInterval)
                && !today.toString().equals(state.randomDate))) {
            return withRandomColor(state, today, random);
        }
        return state;
    }

    /** The app calls this once for a real user launch, never for services or Activity recreation. */
    public static State beginUserSession(State state, LocalDate today, IntUnaryOperator random) {
        Objects.requireNonNull(today, "today");
        if (state.randomEnabled && INTERVAL_LAUNCH.equals(state.randomInterval)) {
            return withRandomColor(state, today, random);
        }
        return resolve(state, today, random);
    }

    /** Selecting a fixed color keeps the random switch unchanged; disabling it restores this color. */
    public static State setFixedSeedColor(State state, int color) {
        int seed = opaque(color);
        if (seed == state.fixedSeedColor) return state;
        return new State(seed, state.randomEnabled, state.randomInterval, state.appearance,
                state.randomSeedColor, state.randomDate);
    }

    public static State setRandomEnabled(State state, boolean enabled, LocalDate today,
                                         IntUnaryOperator random) {
        Objects.requireNonNull(today, "today");
        if (state.randomEnabled == enabled) return resolve(state, today, random);
        State updated = new State(state.fixedSeedColor, enabled, state.randomInterval,
                state.appearance, state.randomSeedColor, state.randomDate);
        // Turning random on is an explicit request to immediately choose a new visible color.
        return enabled ? withRandomColor(updated, today, random, state.getSeedColor()) : updated;
    }

    /** A cadence change preserves the visible color and begins its new daily window today. */
    public static State setRandomInterval(State state, String interval, LocalDate today,
                                          IntUnaryOperator random) {
        Objects.requireNonNull(today, "today");
        String normalized = normalizeInterval(interval);
        if (normalized.equals(state.randomInterval)) return resolve(state, today, random);
        State updated = new State(state.fixedSeedColor, state.randomEnabled, normalized,
                state.appearance, state.randomSeedColor,
                state.randomEnabled ? today.toString() : state.randomDate);
        return resolve(updated, today, random);
    }

    public static State setAppearance(State state, String appearance) {
        String normalized = normalizeAppearance(appearance);
        if (normalized.equals(state.appearance)) return state;
        return new State(state.fixedSeedColor, state.randomEnabled, state.randomInterval,
                normalized, state.randomSeedColor, state.randomDate);
    }

    private static State withRandomColor(State state, LocalDate today, IntUnaryOperator random) {
        return withRandomColor(state, today, random, state.getSeedColor());
    }

    private static State withRandomColor(State state, LocalDate today, IntUnaryOperator random,
                                          int previousColor) {
        Objects.requireNonNull(random, "random");
        int previousIndex = -1;
        for (int i = 0; i < RANDOM_SEEDS.length; i++) {
            if (RANDOM_SEEDS[i] == previousColor) previousIndex = i;
        }
        int count = RANDOM_SEEDS.length - (previousIndex >= 0 ? 1 : 0);
        int index = Math.floorMod(random.applyAsInt(count), count);
        if (previousIndex >= 0 && index >= previousIndex) index++;
        return new State(state.fixedSeedColor, true, state.randomInterval, state.appearance,
                RANDOM_SEEDS[index], today.toString());
    }

    private static int opaque(int color) { return 0xff000000 | (color & 0x00ffffff); }

    private static boolean containsSeed(int color) {
        for (int seed : RANDOM_SEEDS) if (seed == color) return true;
        return false;
    }

    private static String normalizeInterval(String interval) {
        return INTERVAL_LAUNCH.equals(interval) ? INTERVAL_LAUNCH : INTERVAL_DAILY;
    }

    private static String normalizeAppearance(String appearance) {
        if (APPEARANCE_LIGHT.equals(appearance)) return APPEARANCE_LIGHT;
        if (APPEARANCE_DARK.equals(appearance)) return APPEARANCE_DARK;
        return APPEARANCE_SYSTEM;
    }

    private static String normalizeDate(String date) {
        try {
            return date == null || date.isEmpty() ? "" : LocalDate.parse(date).toString();
        } catch (DateTimeParseException invalid) {
            return "";
        }
    }

    public static final class State {
        private final int fixedSeedColor;
        private final boolean randomEnabled;
        private final String randomInterval;
        private final String appearance;
        private final int randomSeedColor;
        private final String randomDate;

        private State(int fixedSeedColor, boolean randomEnabled, String randomInterval,
                      String appearance, int randomSeedColor, String randomDate) {
            this.fixedSeedColor = fixedSeedColor;
            this.randomEnabled = randomEnabled;
            this.randomInterval = randomInterval;
            this.appearance = appearance;
            this.randomSeedColor = randomSeedColor;
            this.randomDate = randomDate;
        }

        public int getFixedSeedColor() { return fixedSeedColor; }
        public boolean isRandomEnabled() { return randomEnabled; }
        public String getRandomInterval() { return randomInterval; }
        public String getAppearance() { return appearance; }
        public int getRandomSeedColor() { return randomSeedColor; }
        public String getRandomDate() { return randomDate; }
        public int getSeedColor() {
            return randomEnabled && containsSeed(randomSeedColor) ? randomSeedColor : fixedSeedColor;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof State)) return false;
            State state = (State) other;
            return fixedSeedColor == state.fixedSeedColor && randomEnabled == state.randomEnabled
                    && randomSeedColor == state.randomSeedColor
                    && randomInterval.equals(state.randomInterval) && appearance.equals(state.appearance)
                    && randomDate.equals(state.randomDate);
        }

        @Override public int hashCode() {
            return Objects.hash(fixedSeedColor, randomEnabled, randomInterval, appearance,
                    randomSeedColor, randomDate);
        }
    }
}
