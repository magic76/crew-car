package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriveIntentPolicyTest {
    @Test public void shortAndNormalForwardAreDifferentLengths() {
        DriveIntentPolicy.Plan nudge = DriveIntentPolicy.plan("forward_small");
        DriveIntentPolicy.Plan move = DriveIntentPolicy.plan("forward");
        DriveIntentPolicy.Plan cruise = DriveIntentPolicy.plan("forward_continuous");
        assertEquals(500, nudge.leaseMs);
        assertEquals(5000, move.leaseMs);
        assertEquals(5000, cruise.leaseMs);
        assertEquals("center", cruise.steering);
        assertEquals("forward", cruise.throttle);
        assertEquals("forward", nudge.throttle);
        assertEquals("forward", move.throttle);
        assertEquals("center", move.steering);
        assertTrue(nudge.leaseMs < move.leaseMs);
        assertEquals(move.leaseMs, cruise.leaseMs);
        assertTrue(cruise.leaseMs <= DriveIntentPolicy.SUPERVISED_CRUISE_MS);
    }
    @Test public void frontDirectionsCombineSteeringWithForwardThrottle() {
        for (String intent : new String[]{"front_left","front_right",
                "front_left_small","front_right_small"}) {
            DriveIntentPolicy.Plan plan = DriveIntentPolicy.plan(intent);
            assertNotNull(plan);
            assertEquals("forward", plan.throttle);
            assertEquals(intent.startsWith("front_left") ? "left" : "right", plan.steering);
            assertTrue(plan.leaseMs >= CarToolPolicy.MIN_HOLD_MS);
            assertTrue(plan.leaseMs <= DriveIntentPolicy.TURN_RECENTER_MS);
            assertTrue("both axes must stop for " + intent, plan.stopWithSteering);
        }
    }
    @Test public void schedulerStopsBothAxesAtLeaseOrSteeringExpiry() {
        assertFalse(DriveIntentPolicy.mustStopBothAxes(599, 600, 650, true));
        assertTrue("diagonal lease expiry must STOP before recenter",
                DriveIntentPolicy.mustStopBothAxes(600, 600, 650, true));
        assertTrue("future longer diagonal must STOP at steering expiry",
                DriveIntentPolicy.mustStopBothAxes(650, 2000, 650, true));
        assertFalse("normal steering-only correction retains its active throttle lease",
                DriveIntentPolicy.mustStopBothAxes(650, 2000, 650, false));
        assertTrue("forward throttle also stops at deadline",
                DriveIntentPolicy.mustStopBothAxes(900, 900, 0, false));
    }

    @Test public void preservesExplicitBackwardsSpeechWithoutLongBlindCruise() {
        assertEquals("reverse", DriveIntentPolicy.plan("backward").throttle);
        assertEquals(900, DriveIntentPolicy.plan("backward").leaseMs);
        assertEquals(500, DriveIntentPolicy.plan("backward_small").leaseMs);
        assertNull(DriveIntentPolicy.plan("backward_continuous"));
    }
    @Test public void modelCannotInventIndefiniteTimeOrInPlaceCircle() {
        for (String intent : new String[]{null, "", "reverse", "backward_continuous",
                "left", "right", "circle", "spin", "follow_person",
                "forward_forever", "until_stop"}) {
            assertNull("must reject " + intent, DriveIntentPolicy.plan(intent));
        }
        assertTrue(DriveIntentPolicy.FRONT_DIAGONAL_MS <= DriveIntentPolicy.TURN_RECENTER_MS);
        assertNotNull(DriveIntentPolicy.plan("forward_continuous"));
        for (String intent : new String[]{"forward", "backward", "front_left", "front_right",
                "forward_small", "backward_small", "front_left_small", "front_right_small"}) {
            DriveIntentPolicy.Plan plan = DriveIntentPolicy.plan(intent);
            assertTrue(intent + " exceeded five-second cap", plan.leaseMs <= 5000);
            if (!intent.startsWith("front_")) assertFalse(plan.stopWithSteering);
        }
    }
}
