package com.crew.car;

import java.nio.charset.StandardCharsets;

/** Fail-closed rules for selecting a user's dedicated RC car access point. */
public final class CarWifiPinPolicy {
    private CarWifiPinPolicy() { }

    public static boolean validSsid(String ssid) {
        return ssid != null && !ssid.isEmpty() &&
                ssid.getBytes(StandardCharsets.UTF_8).length <= 32 &&
                ssid.indexOf('\n') < 0 && ssid.indexOf('\r') < 0;
    }

    /** Empty means open Wi-Fi. Otherwise WPA2 passphrase (not persisted/logged). */
    public static boolean validPassphrase(String password) {
        if (password == null) return false;
        if (password.isEmpty()) return true;
        if (password.length() < 8 || password.length() > 63) return false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (c < 32 || c > 126) return false;
        }
        return true;
    }

    /** A pinned but unavailable car AP MUST NOT fall back to arbitrary Wi-Fi. */
    public static boolean mayUseGenericWifi(boolean pinnedMode) {
        return !pinnedMode;
    }
}
