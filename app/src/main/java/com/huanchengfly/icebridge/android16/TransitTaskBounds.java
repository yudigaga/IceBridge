package com.huanchengfly.icebridge.android16;

/** An immutable task snapshot; framework objects and mutable Rects never reach the UI queue. */
public final class TransitTaskBounds {
    public final long transitId, taskInstance, observedAt;
    public final int taskId, userId, displayId, windowingMode;
    public final String packageName;
    public final int left, top, right, bottom;

    public TransitTaskBounds(long transitId, long taskInstance, int taskId, int userId,
                             String packageName, int displayId, int windowingMode,
                             int left, int top, int right, int bottom, long observedAt) {
        this.transitId = transitId; this.taskInstance = taskInstance;
        this.taskId = taskId; this.userId = userId; this.packageName = packageName;
        this.displayId = displayId; this.windowingMode = windowingMode;
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        this.observedAt = observedAt;
    }

    public boolean isFreeform() {
        return taskId >= 0 && userId >= 0 && displayId == 0 && windowingMode == 5
                && right > left && bottom > top;
    }
}
