package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ThrottleHoldInputTest {
    @Test public void forwardRemainsHeldThroughThumbDrift() {
        ThrottleHoldInput p = new ThrottleHoldInput();
        p.down(2, 1);
        assertEquals(1, p.throttle());
        p.move(2, -100, -100);
        p.move(2, 800, 500);
        assertEquals(1, p.throttle());
        p.up(2);
        assertEquals(0, p.throttle());
    }
    @Test public void reverseStillHeldWhenSteeringFingerLifts() {
        ThrottleHoldInput p = new ThrottleHoldInput();
        p.down(8, -1);
        p.up(3);
        assertEquals(-1, p.throttle());
        p.up(8);
        assertEquals(0, p.throttle());
    }
    @Test public void cannotReverseWithoutReleasingOriginalFinger() {
        ThrottleHoldInput p = new ThrottleHoldInput();
        p.down(5, 1);
        p.down(6, -1);
        assertEquals(1, p.throttle());
        p.up(6);
        assertEquals(1, p.throttle());
        p.cancel();
        p.down(6, -1);
        assertEquals(-1, p.throttle());
    }
    @Test public void touchCancelOrInvalidInputIsNeutral() {
        ThrottleHoldInput p = new ThrottleHoldInput();
        p.down(-1, 1);
        p.down(3, 0);
        assertFalse(p.pressed());
        p.down(3, -1);
        p.cancel();
        assertEquals(0, p.throttle());
        assertFalse(p.pressed());
    }
}
