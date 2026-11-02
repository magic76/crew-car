package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotorAuthorityPolicyTest {
    @Test public void lockedAlwaysDeniesNewMotorClaims() {
        assertEquals(MotorAuthorityPolicy.Owner.LOCKED,
                MotorAuthorityPolicy.current(false, true, true, true));
        assertFalse(MotorAuthorityPolicy.canClaim(
                MotorAuthorityPolicy.Owner.LOCKED, MotorAuthorityPolicy.Owner.VISION));
    }
    @Test public void idleCanGrantOneAutomationOnly() {
        assertTrue(MotorAuthorityPolicy.canClaim(MotorAuthorityPolicy.Owner.IDLE,
                MotorAuthorityPolicy.Owner.GEMINI));
        assertTrue(MotorAuthorityPolicy.canClaim(MotorAuthorityPolicy.Owner.IDLE,
                MotorAuthorityPolicy.Owner.VISION));
        assertFalse(MotorAuthorityPolicy.canClaim(MotorAuthorityPolicy.Owner.GEMINI,
                MotorAuthorityPolicy.Owner.VISION));
        assertFalse(MotorAuthorityPolicy.canClaim(MotorAuthorityPolicy.Owner.VISION,
                MotorAuthorityPolicy.Owner.GEMINI));
    }
    @Test public void ownerOnlyMayUpdateItself() {
        assertTrue(MotorAuthorityPolicy.canUpdate(MotorAuthorityPolicy.Owner.VISION,
                MotorAuthorityPolicy.Owner.VISION));
        assertFalse(MotorAuthorityPolicy.canUpdate(MotorAuthorityPolicy.Owner.VISION,
                MotorAuthorityPolicy.Owner.GEMINI));
        assertFalse(MotorAuthorityPolicy.canUpdate(MotorAuthorityPolicy.Owner.IDLE,
                MotorAuthorityPolicy.Owner.MANUAL));
    }
    @Test public void manualIsHigherPriority() {
        assertEquals(MotorAuthorityPolicy.Owner.MANUAL,
                MotorAuthorityPolicy.current(true, true, true, true));
        assertTrue(MotorAuthorityPolicy.mayPreempt(MotorAuthorityPolicy.Owner.GEMINI));
        assertTrue(MotorAuthorityPolicy.mayPreempt(MotorAuthorityPolicy.Owner.VISION));
    }
}
