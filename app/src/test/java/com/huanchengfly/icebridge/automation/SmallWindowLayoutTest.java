package com.huanchengfly.icebridge.automation;

import org.junit.Test;

import static org.junit.Assert.*;

public class SmallWindowLayoutTest {
    @Test public void missingPreferenceRetainsOriginalCenteredDimensions() {
        assertSame(SmallWindowLayout.DEFAULT, SmallWindowLayout.parse(null));
        assertEquals("70,60,50,50", SmallWindowLayout.DEFAULT.encode());
        SmallWindowLayout.Bounds bounds = SmallWindowLayout.DEFAULT.resolve(0, 0, 1000, 2000);
        assertEquals(new SmallWindowLayout.Bounds(150, 400, 850, 1600), bounds);
        assertEquals(700, bounds.width());
        assertEquals(1200, bounds.height());
        assertEquals(500, bounds.centerX());
        assertEquals(1000, bounds.centerY());
    }

    @Test public void everyPositionEndpointTouchesItsRequestedEdgeWithoutChangingSize() {
        for (int horizontal : new int[]{0, 100}) {
            for (int vertical : new int[]{0, 100}) {
                SmallWindowLayout.Bounds bounds = new SmallWindowLayout(30, 25, horizontal, vertical)
                        .resolve(35, 60, 1000, 2000);
                assertEquals(300, bounds.width());
                assertEquals(500, bounds.height());
                assertEquals(horizontal == 0 ? 35 : 735, bounds.left);
                assertEquals(vertical == 0 ? 60 : 1560, bounds.top);
                assertTrue(bounds.right <= 1035);
                assertTrue(bounds.bottom <= 2060);
            }
        }
    }

    @Test public void positionUsesRemainingTravelRatherThanAbsoluteScreenCoordinates() {
        SmallWindowLayout.Bounds bounds = new SmallWindowLayout(80, 50, 25, 75).resolve(10, 20, 1000, 2000);
        assertEquals(new SmallWindowLayout.Bounds(60, 770, 860, 1770), bounds);
        assertEquals(460, bounds.centerX());
        assertEquals(1270, bounds.centerY());
    }

    @Test public void roundTripHasStableIdentityAndTracksEachIndependentAdjustment() {
        SmallWindowLayout layout = new SmallWindowLayout(53, 47, 12, 83);
        assertEquals(layout, SmallWindowLayout.parse(layout.encode()));
        assertEquals(layout.hashCode(), SmallWindowLayout.parse(layout.encode()).hashCode());
        for (String changed : new String[]{"54,47,12,83", "53,48,12,83", "53,47,13,83", "53,47,12,84"}) {
            assertNotEquals(layout, SmallWindowLayout.parse(changed));
        }
        assertEquals("70,60,50,50", SmallWindowLayout.parse("070,060,050,050").encode());
    }

    @Test public void displayInsetsAndLandscapeRotationRemainWithinAvailableArea() {
        SmallWindowLayout layout = new SmallWindowLayout(95, 95, 100, 100);
        for (int[] display : new int[][]{{20, 80, 1080, 2200}, {80, 20, 2200, 1080}, {-200, -100, 500, 700}}) {
            SmallWindowLayout.Bounds bounds = layout.resolve(display[0], display[1], display[2], display[3]);
            assertTrue(bounds.left >= display[0]);
            assertTrue(bounds.top >= display[1]);
            assertEquals(display[0] + display[2], bounds.right);
            assertEquals(display[1] + display[3], bounds.bottom);
        }
    }

    @Test public void tinyAvailableAreasStillHavePositiveBounds() {
        SmallWindowLayout.Bounds bounds = new SmallWindowLayout(30, 25, 100, 100).resolve(7, 9, 1, 1);
        assertEquals(new SmallWindowLayout.Bounds(7, 9, 8, 10), bounds);
    }

    @Test public void malformedPreferencesAndOutOfRangeAdjustmentsAreRejected() {
        for (String invalid : new String[]{"", "70,60,50", "70,60,50,50,1", "70,60,50,", "70, 60,50,50",
                "70.5,60,50,50", "+70,60,50,50", "-70,60,50,50", "1000,60,50,50", "null",
                "29,60,50,50", "96,60,50,50", "70,24,50,50", "70,96,50,50", "70,60,101,50", "70,60,50,101"}) {
            rejects(() -> SmallWindowLayout.parse(invalid));
        }
        rejects(() -> new SmallWindowLayout(70, 60, -1, 50));
        rejects(() -> new SmallWindowLayout(70, 60, 50, -1));
    }

    @Test public void invalidDisplayOrOverflowCannotCreateOffscreenCoordinates() {
        rejects(() -> SmallWindowLayout.DEFAULT.resolve(0, 0, 0, 100));
        rejects(() -> SmallWindowLayout.DEFAULT.resolve(0, 0, 100, -1));
        rejects(() -> SmallWindowLayout.DEFAULT.resolve(Integer.MAX_VALUE, 0, 1, 100));
        rejects(() -> SmallWindowLayout.DEFAULT.resolve(0, Integer.MAX_VALUE, 100, 1));
        rejects(() -> new SmallWindowLayout.Bounds(0, 0, 0, 100));
        rejects(() -> new SmallWindowLayout.Bounds(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 100));
        SmallWindowLayout.Bounds maximum = SmallWindowLayout.DEFAULT.resolve(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertTrue(maximum.right <= Integer.MAX_VALUE);
        assertTrue(maximum.bottom <= Integer.MAX_VALUE);
        assertTrue(maximum.width() > 0);
    }

    private static void rejects(Runnable operation) {
        try { operation.run(); fail("invalid geometry accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
