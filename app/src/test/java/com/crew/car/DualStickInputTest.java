package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DualStickInputTest {
    private static final float W = 1000f, H = 400f;

    @Test public void twoThumbsDriveAndSteerSimultaneously() {
        DualStickInput s = new DualStickInput();
        s.down(2, 10, 200, W, H);
        assertEquals(-1, s.steering());
        assertEquals(0, s.throttle());
        s.down(7, 920, 18, W, H);
        assertEquals(-1, s.steering());
        assertEquals(1, s.throttle());
        s.move(2, 230, 200, W, H);
        assertEquals(1, s.steering());
        assertEquals(1, s.throttle());
    }

    @Test public void releasingSteerDoesNotCancelThrottle() {
        DualStickInput s = new DualStickInput();
        s.down(1, 10, 160, W, H);
        s.down(3, 970, 390, W, H);
        assertEquals(-1, s.steering());
        assertEquals(-1, s.throttle());
        s.up(1);
        assertEquals(0, s.steering());
        assertEquals(-1, s.throttle());
        s.up(3);
        assertEquals(0, s.throttle());
    }

    @Test public void movingAcrossZonesNeverStealsTheOtherThumb() {
        DualStickInput s = new DualStickInput();
        s.down(1, 10, 200, W, H);
        s.move(1, 999, 0, W, H);
        assertEquals(1, s.steering());
        assertEquals(0, s.throttle());
        s.down(2, 900, 360, W, H);
        assertEquals(-1, s.throttle());
        s.move(2, 0, 0, W, H);
        assertEquals(1, s.throttle());
        assertEquals(-1, s.steering() < 0 ? -1 : s.steering()); // still controlled by left
    }

    @Test public void centerTouchesDoNotTriggerMotion() {
        DualStickInput s = new DualStickInput();
        s.down(1, 500, 100, W, H);
        assertFalse(s.hasActivePointer());
        assertEquals(0, s.steering());
        assertEquals(0, s.throttle());
    }

    @Test public void deadZonesAndCancelAreNeutral() {
        DualStickInput s = new DualStickInput();
        s.down(1, 130, 100, W, H);
        s.down(2, 900, 200, W, H);
        assertEquals(0, s.steering());
        assertEquals(0, s.throttle());
        s.move(1, 10, 100, W, H);
        s.move(2, 900, 390, W, H);
        s.cancel();
        assertFalse(s.hasActivePointer());
        assertEquals(0, s.steering());
        assertEquals(0, s.throttle());
    }

    @Test public void unknownPointerDoesNotReleaseOtherAxis() {
        DualStickInput s = new DualStickInput();
        s.down(8, 100, 200, W, H);
        s.down(9, 900, 10, W, H);
        s.up(12);
        assertTrue(s.hasActivePointer());
        assertEquals(1, s.throttle());
        s.up(8);
        assertEquals(1, s.throttle());
        s.up(9);
        assertFalse(s.hasActivePointer());
    }

    @Test public void impossibleCoordinatesReleaseFailClosed() {
        DualStickInput s = new DualStickInput();
        s.down(2, 100, 200, W, H);
        s.move(2, Float.NaN, 0, W, H);
        assertEquals(0, s.steering());
        assertFalse(s.hasActivePointer());
        s.down(2, 999, 30, -1, H);
        assertEquals(0, s.throttle());
    }
}
