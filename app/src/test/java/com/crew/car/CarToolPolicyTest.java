package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CarToolPolicyTest {
    @Test public void knownTools() {
        for (String name : new String[]{"drive_intent","stop_car","lock_car",
                "get_car_status","get_camera_status","get_network_status"}) {
            assertTrue(name, CarToolPolicy.isSupported(name));
        }
        assertFalse(CarToolPolicy.isSupported("unlock_car"));
        assertFalse(CarToolPolicy.isSupported("drive_sequence"));
        assertFalse(CarToolPolicy.isSupported("drive_car"));
        assertFalse(CarToolPolicy.isSupported("set_drive_state"));
        assertFalse(CarToolPolicy.isSupported("steer_car"));
        assertFalse(CarToolPolicy.isSupported(""));
    }

    @Test public void oneSemanticToolReplacesOldDirectionAuthorization() {
        assertNull(CarToolPolicy.validateDriveIntent("forward"));
        assertNull(CarToolPolicy.validateDriveIntent("forward_small"));
        assertNull(CarToolPolicy.validateDriveIntent("forward_continuous"));
        assertNull(CarToolPolicy.validateDriveIntent("front_left"));
        assertNull(CarToolPolicy.validateDriveIntent("front_right"));
        assertNull(CarToolPolicy.validateDriveIntent("backward"));
        assertNull(CarToolPolicy.validateDriveIntent("backward_small"));
        assertNotNull(CarToolPolicy.validateDriveIntent("backward_continuous"));
        assertNotNull(CarToolPolicy.validateDriveIntent("left"));
        assertNotNull(CarToolPolicy.validateDriveIntent(null));
        assertFalse(CarToolPolicy.isSupported("approach_target"));
    }

    @Test public void validBoundaries() {
        assertNull(CarToolPolicy.validateMove("forward", 100));
        assertNull(CarToolPolicy.validateMove("left", 650));
        assertNull(CarToolPolicy.validateMove("backward", 350));
    }

    @Test public void rejectsUnboundedOrUnsafeMoves() {
        assertNotNull(CarToolPolicy.validateMove("forward", 651));
        assertNotNull(CarToolPolicy.validateMove("forward", 0));
        assertNotNull(CarToolPolicy.validateMove("forward", -100));
        assertNotNull(CarToolPolicy.validateMove("stop", 300));
        assertNotNull(CarToolPolicy.validateMove("around", 300));
        assertNotNull(CarToolPolicy.validateMove(null, 300));
    }
}
