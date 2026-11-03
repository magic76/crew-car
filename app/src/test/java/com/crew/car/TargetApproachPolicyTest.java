package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public class TargetApproachPolicyTest {
    @Test public void directionsAlwaysIncludeForwardAxis() {
        for (String bearing : new String[]{"front_left","ahead","front_right"}) {
            assertNull(TargetApproachPolicy.validate(bearing,150));
            assertNull(TargetApproachPolicy.validate(bearing,600));
        }
        assertEquals(-1,TargetApproachPolicy.steering("front_left"));
        assertEquals(0,TargetApproachPolicy.steering("ahead"));
        assertEquals(1,TargetApproachPolicy.steering("front_right"));
    }

    @Test public void cannotDriveWithoutBearingOrWithLongLease() {
        for (String invalid : new String[]{null,"left","right","backward","target","around",""}) {
            assertNotNull(TargetApproachPolicy.validate(invalid,250));
        }
        assertNotNull(TargetApproachPolicy.validate("ahead",149));
        assertNotNull(TargetApproachPolicy.validate("ahead",601));
        assertNotNull(TargetApproachPolicy.validate("ahead",30_000));
    }

    @Test public void noLongCruiseOrInfiniteToolCalls() {
        assertTrue(TargetApproachPolicy.TURN_RECENTER_MS <= 650);
        assertTrue(TargetApproachPolicy.MAX_DURATION_MS <= 650);
        assertTrue(TargetApproachPolicy.MAX_PULSES <= 3);
        assertTrue(TargetApproachPolicy.AUTHORIZATION_MS <= 20_000);
        assertTrue(TargetApproachPolicy.MIN_PULSE_SPACING_MS >=
                TargetApproachPolicy.MAX_DURATION_MS);
    }
}
