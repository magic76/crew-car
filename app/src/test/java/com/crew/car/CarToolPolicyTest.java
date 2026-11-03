package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CarToolPolicyTest {
    @Test public void knownTools() {
        for (String name : new String[]{"drive_car","stop_car","lock_car",
                "get_car_status","get_camera_status","get_network_status","approach_target"}) {
            assertTrue(name, CarToolPolicy.isSupported(name));
        }
        assertFalse(CarToolPolicy.isSupported("unlock_car"));
        assertFalse(CarToolPolicy.isSupported("drive_sequence"));
        assertFalse(CarToolPolicy.isSupported(""));
    }

    @Test public void approachToolRejectsBlindLongOrReverseRequests() {
        assertNull(CarToolPolicy.validateApproach("front_left",150));
        assertNull(CarToolPolicy.validateApproach("ahead",600));
        assertNull(CarToolPolicy.validateApproach("front_right",300));
        assertNotNull(CarToolPolicy.validateApproach("left",300));
        assertNotNull(CarToolPolicy.validateApproach("backward",300));
        assertNotNull(CarToolPolicy.validateApproach("ahead",30000));
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
