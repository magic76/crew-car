package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AiDriveSessionPolicyTest {
    @Test public void boundedSpeedIsGainNotDistanceOrActualVelocity() {
        assertEquals(70, AiDriveSessionPolicy.speedGain("slow", 100));
        assertEquals(85, AiDriveSessionPolicy.speedGain("normal", 70));
        assertEquals(100, AiDriveSessionPolicy.speedGain("fast", 70));
        assertEquals(85, AiDriveSessionPolicy.speedGain("keep", 85));
        assertTrue(AiDriveSessionPolicy.FAST_GAIN <= CarTuning.MAX_GAIN_PERCENT);
        assertTrue(AiDriveSessionPolicy.SLOW_GAIN >= CarTuning.MIN_GAIN_PERCENT);
        assertEquals(85, AiDriveSessionPolicy.NORMAL_GAIN);
        assertTrue(AiDriveSessionPolicy.SLOW_GAIN < AiDriveSessionPolicy.NORMAL_GAIN);
        assertTrue(AiDriveSessionPolicy.NORMAL_GAIN < AiDriveSessionPolicy.FAST_GAIN);
    }

    @Test public void correctionsAreIndependentOfThrottleAndCanKeep() {
        assertEquals(-1, AiDriveSessionPolicy.steering("left", 0));
        assertEquals(1, AiDriveSessionPolicy.steering("right", 0));
        assertEquals(0, AiDriveSessionPolicy.steering("center", -1));
        assertEquals(-1, AiDriveSessionPolicy.steering("keep", -1));
        assertFalse(AiDriveSessionPolicy.validSteering("spin"));
        assertFalse(AiDriveSessionPolicy.validSpeed("reverse"));
        assertTrue(AiDriveSessionPolicy.validMotion("reverse"));
        assertTrue(AiDriveSessionPolicy.validMotion("stop"));
        assertFalse(AiDriveSessionPolicy.validMotion("turn-around"));
        assertEquals(-1, AiDriveSessionPolicy.throttleFor("reverse", 1));
        assertEquals(1, AiDriveSessionPolicy.throttleFor("forward", -1));
        assertEquals(1, AiDriveSessionPolicy.throttleFor("keep", 1));
        assertEquals(0, AiDriveSessionPolicy.throttleFor("stop", 1));
    }

    @Test public void staleControlAndAbsoluteDeadlineFailClosed() {
        assertEquals(30_000, AiDriveSessionPolicy.MAX_SESSION_MS);
        assertEquals(8_000, AiDriveSessionPolicy.STALE_CONTROL_MS);
        // The motor keeps running after 5 seconds when Gemini has corrected
        // heading/pace within the watchdog window.
        assertFalse(AiDriveSessionPolicy.shouldStop(5_000, 0, 4_000));
        assertFalse(AiDriveSessionPolicy.shouldStop(16_000, 0, 12_500));
        assertFalse(AiDriveSessionPolicy.shouldStop(29_999, 0, 26_500));
        assertTrue(AiDriveSessionPolicy.shouldStop(30_000, 0, 29_500));
        assertEquals("AI drive session max duration",
                AiDriveSessionPolicy.stopReason(30_000, 0, 29_500));
        // Missing model updates must STOP before the full 30 seconds.
        assertFalse(AiDriveSessionPolicy.shouldStop(7_999, 0, 0));
        assertTrue(AiDriveSessionPolicy.shouldStop(8_000, 0, 0));
        assertEquals("AI drive steering/speed updates stale",
                AiDriveSessionPolicy.stopReason(8_000, 0, 0));
        assertTrue(AiDriveSessionPolicy.shouldStop(99, 100, 100));
    }

    @Test public void invalidToolValuesThrow() {
        try { AiDriveSessionPolicy.speedGain("1000", 50); fail(); }
        catch (IllegalArgumentException expected) { }
        try { AiDriveSessionPolicy.steering("reverse", 0); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
