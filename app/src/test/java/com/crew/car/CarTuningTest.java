package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CarTuningTest {
    @Test public void preservesOemCadenceAndOutput() {
        assertEquals(50, CarTuning.OEM.intervalMs);
        assertEquals(100, CarTuning.OEM.steeringPercent);
        assertEquals(100, CarTuning.OEM.throttlePercent);
        assertArrayEquals(WltoysProtocol.control(1, 1),
                WltoysProtocol.control(1, 1, CarTuning.OEM));
        assertArrayEquals(WltoysProtocol.control(0, 0),
                WltoysProtocol.control(0, 0, CarTuning.preset(0)));
    }
    @Test public void presetsStayInsideBounds() {
        for (int index = 0; index < 4; index++) {
            CarTuning t = CarTuning.preset(index);
            assertTrue(t.intervalMs >= 40 && t.intervalMs <= 100);
            assertTrue(t.steeringPercent >= 50 && t.steeringPercent <= 100);
            assertTrue(t.throttlePercent >= 50 && t.throttlePercent <= 100);
        }
    }
    @Test public void softerPowerNeverExceedsOem() {
        byte[] oem = WltoysProtocol.control(1, 1, CarTuning.OEM);
        byte[] soft = WltoysProtocol.control(1, 1, CarTuning.preset(0));
        assertTrue((soft[9] & 255) < (oem[9] & 255));
        assertTrue((soft[10] & 255) < (oem[10] & 255));
        assertEquals(((soft[9] ^ soft[10] ^ soft[11]) & 255), soft[14] & 255);
    }
    @Test public void positiveAndNegativeGainsAreSymmetric() {
        CarTuning t = new CarTuning(60, 50, 50);
        byte[] positive = WltoysProtocol.control(1, 1, t);
        byte[] negative = WltoysProtocol.control(-1, -1, t);
        assertEquals(256, (positive[9] & 255) + (negative[9] & 255));
        assertEquals(256, (positive[10] & 255) + (negative[10] & 255));
    }
    @Test public void disallowsOutOfRangeOrMalformedTuning() {
        for (int interval : new int[]{0, 35, 55, 110}) {
            try { new CarTuning(interval, 100, 100); fail("interval " + interval); }
            catch (IllegalArgumentException expected) { }
        }
        for (int gain : new int[]{0, 49, 71, 105}) {
            try { new CarTuning(50, gain, 100); fail("gain " + gain); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
