package com.huanchengfly.icebridge.ui;

/** Rejects results from a superseded request or a screen that has left the foreground. */
public final class RefreshGeneration {
    private long generation;

    public synchronized long begin() { return ++generation; }
    public synchronized void invalidate() { generation++; }
    public synchronized boolean accepts(long request) { return request == generation; }
}
