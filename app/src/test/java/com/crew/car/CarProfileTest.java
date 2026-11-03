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
    @Test public void visibleVehicleChoicesDoNotExposeUnverifiedKL() {
        String[] options = CarProfile.visibleOptions();
        assertArrayEquals(new String[]{"自訂封包", "WL Tech FPV（現有）"}, options);
        assertEquals(2, options.length);
        assertEquals(CarProfile.CUSTOM, CarProfile.visibleFromIndex(0));
        assertEquals(CarProfile.WL_TECH, CarProfile.visibleFromIndex(1));
    }
    @Test public void hiddenSavedKlOpensAsSafeVisibleWlWithoutDeletingKlIdentity() {
        assertEquals(CarProfile.WL_TECH, CarProfile.visibleFromIndex(2));
        assertEquals(CarProfile.WL_TECH, CarProfile.visibleFromIndex(-1));
        assertEquals(CarProfile.WL_TECH, CarProfile.visibleFromIndex(99));
        assertEquals(CarProfile.KL_FPV, CarProfile.fromIndex(2));
        assertFalse(KlFpvProtocol.isVerified());
    }
    @Test public void isolationAndNoGuessedKLAddress() {
        assertNotEquals(CarProfile.WL_TECH.settingKey("host"),
                CarProfile.KL_FPV.settingKey("host"));
        assertEquals("172.16.11.1", CarProfile.WL_TECH.defaultHost());
        assertEquals("", CarProfile.KL_FPV.defaultHost());
        assertEquals("", CarProfile.KL_FPV.defaultPort());
    }
    @Test public void legacyCustomPreferencesRemainAvailable() {
        assertTrue(CarProfile.CUSTOM.usesLegacySettings());
        assertTrue(CarProfile.WL_TECH.usesLegacySettings());
        assertFalse(CarProfile.KL_FPV.usesLegacySettings());
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
