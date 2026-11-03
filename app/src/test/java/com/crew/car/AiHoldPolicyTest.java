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
        assertNull(CarToolPolicy.validateHold("center", "forward", 30000));
        assertEquals(15000, CarToolPolicy.DEFAULT_HOLD_MS);
        assertEquals(30000, CarToolPolicy.MAX_HOLD_MS);
        for (int ms : new int[]{-10, 0, 100, 499, 30001, 60_000}) {
            assertNotNull("duration " + ms,
                    CarToolPolicy.validateHold("center", "forward", ms));
        }
    }

    @Test public void rejectsInvalidCommandsBeforeHardwareAccess() {
        assertNotNull(CarToolPolicy.validateHold(null, "forward", 2000));
        assertNotNull(CarToolPolicy.validateHold("left", null, 2000));
        assertNotNull(CarToolPolicy.validateHold("diagonal", "forward", 2000));
        assertNotNull(CarToolPolicy.validateHold("right", "accelerate", 2000));
        // Internal motor lease helpers stay testable, but Gemini now advertises
        // only drive_intent; stale model calls to old movement tools are blocked.
        assertFalse(CarToolPolicy.isSupported("set_drive_state"));
        assertFalse(CarToolPolicy.isSupported("steer_car"));
        assertTrue(CarToolPolicy.isSupported("drive_intent"));
        assertNull(CarToolPolicy.validateSteer("left", 100));
        assertNull(CarToolPolicy.validateSteer("right", 550));
        assertNull(CarToolPolicy.validateSteer("center", 2000));
        assertNotNull(CarToolPolicy.validateSteer("up", 550));
        assertNotNull(CarToolPolicy.validateSteer("left", 2001));
        assertNotNull(CarToolPolicy.validateSteer("left", 0));
        assertFalse(CarToolPolicy.isSupported("unlock_car"));
        assertFalse(CarToolPolicy.isSupported("set_speed_kmh"));
    }
}
