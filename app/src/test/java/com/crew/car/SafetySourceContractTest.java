package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public class SafetySourceContractTest {
    @Test public void ownerCannotBeBothAiAndVisionFromPolicy() {
        assertEquals(MotorAuthorityPolicy.Owner.VISION,
                MotorAuthorityPolicy.current(true, false, false, true));
        assertFalse(MotorAuthorityPolicy.canClaim(MotorAuthorityPolicy.Owner.VISION,
                MotorAuthorityPolicy.Owner.GEMINI));
    }
    @Test public void manualCanPreemptAutomatedStatesWithoutClaimingAnExtraStream() {
        for (MotorAuthorityPolicy.Owner o : new MotorAuthorityPolicy.Owner[]{
                MotorAuthorityPolicy.Owner.GEMINI,MotorAuthorityPolicy.Owner.VISION}) {
            assertTrue(MotorAuthorityPolicy.mayPreempt(o));
            assertFalse(MotorAuthorityPolicy.canClaim(o, MotorAuthorityPolicy.Owner.MANUAL));
        }
    }
}
