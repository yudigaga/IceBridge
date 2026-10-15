package com.huanchengfly.icebridge.automation;

import java.util.Objects;

/** Window geometry relative to the available display area, shared by preview and launch. */
public final class SmallWindowLayout {
    public static final SmallWindowLayout DEFAULT = new SmallWindowLayout(70, 60, 50, 50);

    public final int widthPercent;
    public final int heightPercent;
    public final int horizontalPercent;
    public final int verticalPercent;

    public SmallWindowLayout(int widthPercent, int heightPercent, int horizontalPercent, int verticalPercent) {
        if (widthPercent < 30 || widthPercent > 95 || heightPercent < 25 || heightPercent > 95
                || horizontalPercent < 0 || horizontalPercent > 100
                || verticalPercent < 0 || verticalPercent > 100) {
            throw new IllegalArgumentException("small-window-layout-range");
        }
        this.widthPercent = widthPercent;
        this.heightPercent = heightPercent;
        this.horizontalPercent = horizontalPercent;
        this.verticalPercent = verticalPercent;
    }

    public String encode() {
        return widthPercent + "," + heightPercent + "," + horizontalPercent + "," + verticalPercent;
    }

    /** Missing preferences retain the original centered window; malformed preferences fail closed. */
    public static SmallWindowLayout parse(String encoded) {
        if (encoded == null) return DEFAULT;
        if (encoded.length() > 15) throw new IllegalArgumentException("small-window-layout-format");
        String[] values = encoded.split(",", -1);
        if (values.length != 4) throw new IllegalArgumentException("small-window-layout-format");
        int[] percentages = new int[4];
        for (int index = 0; index < values.length; index++) {
            if (!values[index].matches("[0-9]{1,3}")) {
                throw new IllegalArgumentException("small-window-layout-format");
            }
            percentages[index] = Integer.parseInt(values[index]);
        }
        return new SmallWindowLayout(percentages[0], percentages[1], percentages[2], percentages[3]);
    }

    /** Position is a percentage of the remaining travel, so each edge remains reachable. */
    public Bounds resolve(int left, int top, int width, int height) {
        if (width <= 0 || height <= 0 || (long) left + width > Integer.MAX_VALUE
                || (long) top + height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("small-window-display-bounds");
        }
        int resolvedWidth = Math.max(1, (int) Math.round(width * (widthPercent / 100.0)));
        int resolvedHeight = Math.max(1, (int) Math.round(height * (heightPercent / 100.0)));
        int resolvedLeft = left + (int) Math.round((width - resolvedWidth) * (horizontalPercent / 100.0));
        int resolvedTop = top + (int) Math.round((height - resolvedHeight) * (verticalPercent / 100.0));
        return new Bounds(resolvedLeft, resolvedTop, resolvedLeft + resolvedWidth, resolvedTop + resolvedHeight);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SmallWindowLayout)) return false;
        SmallWindowLayout layout = (SmallWindowLayout) other;
        return widthPercent == layout.widthPercent && heightPercent == layout.heightPercent
                && horizontalPercent == layout.horizontalPercent && verticalPercent == layout.verticalPercent;
    }

    @Override public int hashCode() {
        return Objects.hash(widthPercent, heightPercent, horizontalPercent, verticalPercent);
    }

    @Override public String toString() { return encode(); }

    public static final class Bounds {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Bounds(int left, int top, int right, int bottom) {
            if (left >= right || top >= bottom || (long) right - left > Integer.MAX_VALUE
                    || (long) bottom - top > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("small-window-bounds");
            }
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public int width() { return right - left; }
        public int height() { return bottom - top; }
        public int centerX() { return left + width() / 2; }
        public int centerY() { return top + height() / 2; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Bounds)) return false;
            Bounds bounds = (Bounds) other;
            return left == bounds.left && top == bounds.top && right == bounds.right && bottom == bounds.bottom;
        }

        @Override public int hashCode() { return Objects.hash(left, top, right, bottom); }
    }
}
