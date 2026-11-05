package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CarWifiPinPolicyTest {
    @Test public void onlyExplicitSsidCanSelectCar() {
        assertTrue(CarWifiPinPolicy.validSsid("WLtoys_6405"));
        assertTrue(CarWifiPinPolicy.validSsid("Car 5G"));
        assertFalse(CarWifiPinPolicy.validSsid(null));
        assertFalse(CarWifiPinPolicy.validSsid(""));
        assertFalse(CarWifiPinPolicy.validSsid("abcdefghijklmnopqrstuvwxyz0123456789"));
        assertFalse(CarWifiPinPolicy.validSsid("car\nnetwork"));
    }

    @Test public void supportsOpenAndWpa2WithoutEverSavingSecrets() {
        assertTrue(CarWifiPinPolicy.validPassphrase(""));
        assertTrue(CarWifiPinPolicy.validPassphrase("12345678"));
        assertTrue(CarWifiPinPolicy.validPassphrase("MySecretPass!"));
        assertFalse(CarWifiPinPolicy.validPassphrase(null));
        assertFalse(CarWifiPinPolicy.validPassphrase("1234567"));
        assertFalse(CarWifiPinPolicy.validPassphrase("a".repeat(64)));
        assertFalse(CarWifiPinPolicy.validPassphrase("foo\npassword"));
    }

    @Test public void explicitCarSelectionIsFailClosed() {
        assertTrue(CarWifiPinPolicy.mayUseGenericWifi(false));
        assertFalse(CarWifiPinPolicy.mayUseGenericWifi(true));
    }
}
