package com.huanchengfly.icebridge.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class RefreshGenerationTest {
    @Test public void oldEngineResultCannotReplaceModeChangedResult() {
        RefreshGeneration requests = new RefreshGeneration();
        long oldIceBox = requests.begin();
        long newRoot = requests.begin();
        assertFalse(requests.accepts(oldIceBox));
        assertTrue(requests.accepts(newRoot));
    }

    @Test public void leavingScreenRejectsInFlightCompletion() {
        RefreshGeneration requests = new RefreshGeneration();
        long request = requests.begin();
        requests.invalidate();
        assertFalse(requests.accepts(request));
        assertTrue(requests.accepts(requests.begin()));
    }
}
