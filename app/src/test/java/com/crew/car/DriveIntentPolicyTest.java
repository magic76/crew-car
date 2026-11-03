package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriveIntentPolicyTest {
    @Test public void shortAndNormalForwardAreDifferentLengths() {
        DriveIntentPolicy.Plan nudge = DriveIntentPolicy.plan("forward_small");
        DriveIntentPolicy.Plan move = DriveIntentPolicy.plan("forward");
        DriveIntentPolicy.Plan cruise = DriveIntentPolicy.plan("forward_continuous");
        assertEquals(500, nudge.leaseMs);
        assertEquals(2500, move.leaseMs);
        assertEquals(15000, cruise.leaseMs);
        assertEquals("forward", nudge.throttle);
        assertEquals("forward", move.throttle);
        assertEquals("center", move.steering);
        assertTrue(nudge.leaseMs < move.leaseMs);
        assertTrue(move.leaseMs < cruise.leaseMs);
    }
    @Test public void frontDirectionsCombineSteeringWithForwardThrottle() {
        for (String intent : new String[]{"front_left","front_right",
                "front_left_small","front_right_small"}) {
            DriveIntentPolicy.Plan plan = DriveIntentPolicy.plan(intent);
            assertNotNull(plan);
            assertEquals("forward", plan.throttle);
            assertEquals(intent.startsWith("front_left") ? "left" : "right", plan.steering);
            assertTrue(plan.leaseMs >= CarToolPolicy.MIN_HOLD_MS);
            assertTrue(plan.leaseMs <= CarToolPolicy.MAX_HOLD_MS);
        }
    }
    @Test public void preservesExplicitBackwardsSpeechWithoutLongBlindCruise() {
        assertEquals("reverse", DriveIntentPolicy.plan("backward").throttle);
        assertEquals(2000, DriveIntentPolicy.plan("backward").leaseMs);
        assertEquals(500, DriveIntentPolicy.plan("backward_small").leaseMs);
        assertNull(DriveIntentPolicy.plan("backward_continuous"));
    }
    @Test public void modelCannotInventIndefiniteTimeOrInPlaceCircle() {
        for (String intent : new String[]{null, "", "reverse", "backward_continuous",
                "left", "right", "circle", "spin", "follow_person",
                "forward_forever", "until_stop"}) {
            assertNull("must reject " + intent, DriveIntentPolicy.plan(intent));
        }
        assertTrue(DriveIntentPolicy.TURN_RECENTER_MS < DriveIntentPolicy.FRONT_DIAGONAL_MS);
        assertTrue(DriveIntentPolicy.CRUISE_MS <= CarToolPolicy.MAX_HOLD_MS);
    }
}
