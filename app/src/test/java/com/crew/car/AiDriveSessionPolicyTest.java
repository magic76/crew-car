package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AiDriveSessionPolicyTest {
    @Test public void boundedSpeedIsGainNotDistanceOrActualVelocity() {
        assertEquals(50, AiDriveSessionPolicy.speedGain("slow", 80));
        assertEquals(65, AiDriveSessionPolicy.speedGain("normal", 50));
        assertEquals(80, AiDriveSessionPolicy.speedGain("fast", 50));
        assertEquals(65, AiDriveSessionPolicy.speedGain("keep", 65));
        assertTrue(AiDriveSessionPolicy.FAST_GAIN <= CarTuning.MAX_GAIN_PERCENT);
        assertTrue(AiDriveSessionPolicy.SLOW_GAIN >= CarTuning.MIN_GAIN_PERCENT);
    }

    @Test public void correctionsAreIndependentOfThrottleAndCanKeep() {
        assertEquals(-1, AiDriveSessionPolicy.steering("left", 0));
        assertEquals(1, AiDriveSessionPolicy.steering("right", 0));
        assertEquals(0, AiDriveSessionPolicy.steering("center", -1));
        assertEquals(-1, AiDriveSessionPolicy.steering("keep", -1));
        assertFalse(AiDriveSessionPolicy.validSteering("spin"));
        assertFalse(AiDriveSessionPolicy.validSpeed("reverse"));
    }

    @Test public void staleControlAndAbsoluteDeadlineFailClosed() {
        assertFalse(AiDriveSessionPolicy.shouldStop(8499, 0, 0));
        assertTrue(AiDriveSessionPolicy.shouldStop(8500, 0, 0));
        assertFalse(AiDriveSessionPolicy.shouldStop(21000, 0, 19500));
        assertTrue(AiDriveSessionPolicy.shouldStop(30000, 0, 29990));
        assertTrue(AiDriveSessionPolicy.shouldStop(99, 100, 100));
        assertEquals("AI drive session max duration",
                AiDriveSessionPolicy.stopReason(30000, 0, 29000));
    }

    @Test public void invalidToolValuesThrow() {
        try { AiDriveSessionPolicy.speedGain("1000", 50); fail(); }
        catch (IllegalArgumentException expected) { }
        try { AiDriveSessionPolicy.steering("reverse", 0); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
