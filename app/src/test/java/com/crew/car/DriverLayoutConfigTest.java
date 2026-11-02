package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriverLayoutConfigTest {
    @Test public void defaultVideoGetsSixtyPercent() {
        DriverLayoutConfig c = DriverLayoutConfig.defaults();
        assertEquals(20, c.sidePercent);
        assertEquals(.60f, c.videoFraction(), .0001f);
        assertTrue(c.buttonSteering);
    }

    @Test public void allSideWidthsLeaveAtLeastFortyEightPercentVideo() {
        for (int p=18; p<=26; p++) {
            DriverLayoutConfig c = new DriverLayoutConfig(p,10,0,26,false,true);
            assertTrue(c.videoFraction() >= .48f);
        }
    }

    @Test public void invalidGeometryIsRejected() {
        for (int side : new int[]{0, 17, 27, 50}) {
            try { new DriverLayoutConfig(side,10,0,26,true,true); fail(); }
            catch (IllegalArgumentException expected) { }
        }
        try { new DriverLayoutConfig(20,31,0,26,true,true); fail(); }
        catch (IllegalArgumentException expected) { }
        try { new DriverLayoutConfig(20,10,25,26,true,true); fail(); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void leftAndRightButtonPointersAreIndependentFromOtherFingers() {
        SteeringButtonInput s = new SteeringButtonInput();
        s.down(1, 10, 40, 200, 120);
        assertEquals(-1, s.steering());
        s.up(5); // right throttle release must not reset steering
        assertEquals(-1, s.steering());
        s.move(1, 160, 40, 200, 120);
        assertEquals(0, s.steering()); // steering finger leaves original button
        s.down(2, 190, 40, 200, 120);
        assertEquals(1, s.steering());
        s.up(2);
        assertEquals(0, s.steering());
    }

    @Test public void buttonDividerAndCancellationAreNeutral() {
        SteeringButtonInput s = new SteeringButtonInput();
        s.down(1, 100, 50, 200, 120); // central divider
        assertEquals(0, s.steering());
        s.down(2, Float.NaN, 50, 200, 120);
        assertEquals(0, s.steering());
        s.down(3, 160, 50, 200, 120);
        assertEquals(1, s.steering());
        s.cancel();
        assertEquals(0, s.steering());
    }

    @Test public void legacyStickRemainsAvailableWithAdjustedWidth() {
        DualStickInput s = new DualStickInput();
        s.configure(.20f, .26f);
        s.down(1, 0, 100, 1000, 300);
        assertEquals(-1, s.steering());
        s.up(1);
        assertEquals(0, s.steering());
        s.configure(.26f, .26f);
        s.down(2, 245, 100, 1000, 300);
        assertEquals(1, s.steering());
    }
}
