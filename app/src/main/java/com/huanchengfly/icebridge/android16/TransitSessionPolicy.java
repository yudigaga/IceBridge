package com.huanchengfly.icebridge.android16;

import com.huanchengfly.icebridge.automation.SmallWindowLayout;

/** Pure state for the transient card. A submitted launch is not a displayed destination. */
public final class TransitSessionPolicy {
    public static final int THAWING = 0;
    public static final int OPENING = 1;
    public static final long TIMEOUT_MILLIS = 12_000;

    private long highestId;
    private Session active;

    public synchronized boolean stage(long id, int userId, String targetPackage, int stage,
                                      int seed, String appearance, long now) {
        return stage(id, userId, null, targetPackage, stage, seed, appearance, now);
    }

    public synchronized boolean stage(long id, int userId, String sourcePackage,
                                      String targetPackage, int stage, int seed,
                                      String appearance, long now) {
        return stage(id, userId, sourcePackage, targetPackage, stage, seed, appearance, null, now);
    }

    public synchronized boolean stage(long id, int userId, String sourcePackage,
                                      String targetPackage, int stage, int seed,
                                      String appearance, SmallWindowLayout smallWindowLayout, long now) {
        if (id < 1 || userId < 0 || targetPackage == null || targetPackage.isEmpty()
                || targetPackage.length() > 255 || (stage != THAWING && stage != OPENING)
                || id < highestId || (id == highestId && active == null)) return false;
        if (active != null && id == active.id) {
            if (active.userId != userId || !active.targetPackage.equals(targetPackage)
                    || stage < active.stage) return false;
            active = new Session(id, userId, active.sourcePackage, targetPackage, stage, seed, appearance,
                    active.smallWindowLayout, active.taskBounds, active.confirmedByTransitToken,
                    active.startedAt, active.deadline, active.submitted);
        } else {
            highestId = id;
            active = new Session(id, userId, normalizeSource(sourcePackage), targetPackage, stage, seed, appearance,
                    smallWindowLayout, null, false, now, now + TIMEOUT_MILLIS, false);
        }
        return true;
    }

    public synchronized boolean submitted(long id) {
        if (active == null || active.id != id || active.stage != OPENING) return false;
        active = new Session(active.id, active.userId, active.sourcePackage, active.targetPackage, active.stage,
                active.seed, active.appearance, active.smallWindowLayout, active.taskBounds,
                active.confirmedByTransitToken, active.startedAt, active.deadline, true);
        return true;
    }

    /** Only the current transaction's exact task can move an already visible card. */
    public synchronized boolean targetBounds(long id, TransitTaskBounds bounds) {
        if (active == null || active.id != id || active.smallWindowLayout == null || bounds == null
                || !bounds.isFreeform() || active.userId != bounds.userId
                || !active.targetPackage.equals(bounds.packageName)
                || (bounds.transitId > 0 && bounds.transitId != id)
                || bounds.observedAt < active.startedAt) return false;
        TransitTaskBounds previous = active.taskBounds;
        // The worker's unique-visible-task query has no launch token. Configuration callbacks
        // without a token can only update that already identified task, never choose one by package.
        if (previous == null && bounds.transitId == 0
                && (bounds.taskInstance != 0 || active.stage != OPENING)) return false;
        if (previous != null && (bounds.observedAt < previous.observedAt
                || ((active.confirmedByTransitToken || bounds.transitId == 0)
                && (previous.taskId != bounds.taskId || (previous.taskInstance > 0
                && previous.taskInstance != bounds.taskInstance))))) return false;
        active = new Session(active.id, active.userId, active.sourcePackage, active.targetPackage, active.stage,
                active.seed, active.appearance, active.smallWindowLayout, bounds,
                active.confirmedByTransitToken || bounds.transitId == id,
                active.startedAt, active.deadline, active.submitted);
        return true;
    }

    public synchronized boolean targetDrawn(long id, int userId, String targetPackage) {
        if (active == null || active.id != id || active.userId != userId
                || active.stage != OPENING || !active.targetPackage.equals(targetPackage)) return false;
        active = null;
        return true;
    }

    public synchronized boolean finish(long id) {
        if (active == null || active.id != id) return false;
        active = null;
        return true;
    }

    public synchronized boolean timeout(long id, long now) {
        if (active == null || active.id != id || now < active.deadline) return false;
        active = null;
        return true;
    }

    public synchronized boolean cancelAll() {
        boolean hadActive = active != null;
        active = null;
        return hadActive;
    }

    public synchronized Session active() { return active; }

    private static String normalizeSource(String sourcePackage) {
        if (sourcePackage == null) return null;
        String normalized = sourcePackage.trim();
        return normalized.isEmpty() || "*".equals(normalized) || normalized.length() > 255
                ? null : normalized;
    }

    public static final class Session {
        public final long id;
        public final int userId;
        public final String sourcePackage;
        public final String targetPackage;
        public final int stage;
        public final int seed;
        public final String appearance;
        public final SmallWindowLayout smallWindowLayout;
        public final TransitTaskBounds taskBounds;
        public final boolean confirmedByTransitToken;
        public final long startedAt;
        public final long deadline;
        public final boolean submitted;

        private Session(long id, int userId, String sourcePackage, String targetPackage, int stage, int seed,
                        String appearance, SmallWindowLayout smallWindowLayout, TransitTaskBounds taskBounds,
                        boolean confirmedByTransitToken, long startedAt, long deadline, boolean submitted) {
            this.id = id;
            this.userId = userId;
            this.sourcePackage = sourcePackage;
            this.targetPackage = targetPackage;
            this.stage = stage;
            this.seed = seed | 0xff000000;
            this.appearance = "dark".equals(appearance) ? "dark"
                    : "light".equals(appearance) ? "light" : "system";
            this.smallWindowLayout = smallWindowLayout;
            this.taskBounds = taskBounds;
            this.confirmedByTransitToken = confirmedByTransitToken;
            this.startedAt = startedAt;
            this.deadline = deadline;
            this.submitted = submitted;
        }
    }

    /** Native colors share the app palette's HSL tones without loading Compose into system_server. */
    public static final class Palette {
        public final int card;
        public final int inset;
        public final int title;
        public final int secondary;
        public final int accent;
        public final int outline;

        private Palette(int seed, boolean dark) {
            double[] hsl = hsl(seed);
            double hue = hsl[0];
            boolean neutral = hsl[1] < 0.001;
            card = color(hue, neutral ? 0 : 0.28, dark ? 0.12 : 0.98);
            inset = color(hue, neutral ? 0 : 0.31, dark ? 0.18 : 0.91);
            title = readable(hue, neutral ? 0 : 0.15, dark ? 0.94 : 0.115, dark, card, inset);
            secondary = readable(hue, neutral ? 0 : 0.10, dark ? 0.735 : 0.355, dark, card, inset);
            accent = readable(hue, neutral ? 0 : Math.max(0.42, Math.min(0.72, hsl[1])),
                    dark ? 0.735 : 0.365, dark, card, inset);
            outline = color(hue, neutral ? 0 : 0.15, dark ? 0.27 : 0.825);
        }

        public static Palette fromSeed(int seed, boolean dark) { return new Palette(seed, dark); }

        public static double contrast(int first, int second) {
            double a = luminance(first);
            double b = luminance(second);
            return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
        }

        private static int readable(double hue, double saturation, double lightness,
                                    boolean dark, int... surfaces) {
            int candidate = color(hue, saturation, lightness);
            for (int attempts = 0; attempts < 200; attempts++) {
                boolean readable = true;
                for (int surface : surfaces) if (contrast(candidate, surface) < 4.5) readable = false;
                if (readable) return candidate;
                lightness = Math.max(0, Math.min(1, lightness + (dark ? 0.005 : -0.005)));
                candidate = color(hue, saturation, lightness);
            }
            return candidate;
        }

        private static double luminance(int color) {
            return 0.2126 * linear((color >>> 16) & 255)
                    + 0.7152 * linear((color >>> 8) & 255) + 0.0722 * linear(color & 255);
        }

        private static double linear(int channel) {
            double normalized = channel / 255.0;
            return normalized <= 0.04045 ? normalized / 12.92
                    : Math.pow((normalized + 0.055) / 1.055, 2.4);
        }

        private static double[] hsl(int color) {
            double red = ((color >>> 16) & 255) / 255.0;
            double green = ((color >>> 8) & 255) / 255.0;
            double blue = (color & 255) / 255.0;
            double maximum = Math.max(red, Math.max(green, blue));
            double minimum = Math.min(red, Math.min(green, blue));
            double delta = maximum - minimum;
            if (delta < 0.001) return new double[]{210, 0, (maximum + minimum) / 2};
            double hue = maximum == red ? ((green - blue) / delta) % 6
                    : maximum == green ? (blue - red) / delta + 2 : (red - green) / delta + 4;
            return new double[]{(hue * 60 + 360) % 360,
                    delta / (1 - Math.abs(maximum + minimum - 1)), (maximum + minimum) / 2};
        }

        private static int color(double hue, double saturation, double lightness) {
            double chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
            double segment = hue / 60;
            double intermediate = chroma * (1 - Math.abs(segment % 2 - 1));
            double red, green, blue;
            if (segment < 1) { red = chroma; green = intermediate; blue = 0; }
            else if (segment < 2) { red = intermediate; green = chroma; blue = 0; }
            else if (segment < 3) { red = 0; green = chroma; blue = intermediate; }
            else if (segment < 4) { red = 0; green = intermediate; blue = chroma; }
            else if (segment < 5) { red = intermediate; green = 0; blue = chroma; }
            else { red = chroma; green = 0; blue = intermediate; }
            double match = lightness - chroma / 2;
            return 0xff000000 | (channel(red + match) << 16) | (channel(green + match) << 8)
                    | channel(blue + match);
        }

        private static int channel(double value) {
            return Math.max(0, Math.min(255, (int) Math.round(value * 255)));
        }
    }
}
