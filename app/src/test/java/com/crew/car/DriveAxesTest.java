package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DriveAxesTest {
    private void assertAxes(float x, float y, int steering, int throttle) {
        assertArrayEquals(new int[]{steering, throttle},
                DriveAxes.fromPoint(x, y, 300, 300));
    }

    @Test public void eightDirectionsAndNeutral() {
        assertAxes(150, 150, 0, 0);
        assertAxes(150, 10, 0, 1);
        assertAxes(150, 290, 0, -1);
        assertAxes(10, 150, -1, 0);
        assertAxes(290, 150, 1, 0);
        assertAxes(10, 10, -1, 1);
        assertAxes(290, 10, 1, 1);
        assertAxes(10, 290, -1, -1);
        assertAxes(290, 290, 1, -1);
    }

    @Test public void deadzoneAndBadCoordinatesStop() {
        assertAxes(150, 120, 0, 0);
        assertAxes(150, 180, 0, 0);
        assertAxes(180, 150, 0, 0);
        assertArrayEquals(new int[]{0,0}, DriveAxes.fromPoint(Float.NaN, 7, 300, 300));
        assertArrayEquals(new int[]{0,0}, DriveAxes.fromPoint(10, 10, 0, 300));
    }
}
