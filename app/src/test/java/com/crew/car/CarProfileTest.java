package com.crew.car;
import org.junit.Test;
import static org.junit.Assert.*;
public class CarProfileTest {
    @Test public void stableIndices() {
        assertEquals(CarProfile.CUSTOM, CarProfile.fromIndex(0));
        assertEquals(CarProfile.WL_TECH, CarProfile.fromIndex(1));
        assertEquals(CarProfile.KL_FPV, CarProfile.fromIndex(2));
        assertEquals(CarProfile.WL_TECH, CarProfile.fromIndex(123));
    }
    @Test public void isolationAndNoGuessedKLAddress() {
        assertNotEquals(CarProfile.WL_TECH.settingKey("host"),
                CarProfile.KL_FPV.settingKey("host"));
        assertEquals("172.16.11.1", CarProfile.WL_TECH.defaultHost());
        assertEquals("", CarProfile.KL_FPV.defaultHost());
        assertEquals("", CarProfile.KL_FPV.defaultPort());
    }
    @Test public void klFailsClosed() {
        assertFalse(KlFpvProtocol.isVerified());
        try {
            KlFpvProtocol.encodeAxes(0, 1);
            fail("not verified");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("KL FPV"));
        }
    }
}
