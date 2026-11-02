package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AiHoldPolicyTest {
    @Test public void acceptsIndependentSteeringAndThrottle() {
        for (String steer : new String[]{"left","right","center","keep"}) {
            for (String gas : new String[]{"forward","reverse","neutral","keep"}) {
                assertNull(steer + "/" + gas, CarToolPolicy.validateHold(steer, gas, 2000));
            }
        }
    }

    @Test public void enforcesBoundedLease() {
        assertNull(CarToolPolicy.validateHold("center", "forward", 500));
        assertNull(CarToolPolicy.validateHold("center", "forward", 5000));
        for (int ms : new int[]{-10, 0, 100, 499, 5001, 60_000}) {
            assertNotNull("duration " + ms,
                    CarToolPolicy.validateHold("center", "forward", ms));
        }
    }

    @Test public void rejectsInvalidCommandsBeforeHardwareAccess() {
        assertNotNull(CarToolPolicy.validateHold(null, "forward", 2000));
        assertNotNull(CarToolPolicy.validateHold("left", null, 2000));
        assertNotNull(CarToolPolicy.validateHold("diagonal", "forward", 2000));
        assertNotNull(CarToolPolicy.validateHold("right", "accelerate", 2000));
        assertTrue(CarToolPolicy.isSupported("set_drive_state"));
        assertFalse(CarToolPolicy.isSupported("unlock_car"));
        assertFalse(CarToolPolicy.isSupported("set_speed_kmh"));
    }
}
