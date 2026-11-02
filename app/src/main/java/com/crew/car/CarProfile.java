package com.crew.car;

/** Stable vehicle identities. Spinner index 1 is the preexisting WL profile. */
public enum CarProfile {
    CUSTOM(0, "custom", "自訂封包"),
    WL_TECH(1, "wl-tech", "WL Tech FPV"),
    KL_FPV(2, "kl-fpv", "KL FPV");
    public final int spinnerIndex;
    public final String id;
    public final String label;
    CarProfile(int index, String id, String label) {
        spinnerIndex = index;
        this.id = id;
        this.label = label;
    }
    public static CarProfile fromIndex(int index) {
        if (index == 0) return CUSTOM;
        if (index == 2) return KL_FPV;
        return WL_TECH;
    }
    public String settingKey(String field) {
        if (field == null || !field.matches("[a-z_]+"))
            throw new IllegalArgumentException("invalid setting key");
        return "vehicle_" + id + "_" + field;
    }
    /** Existing custom and WL profiles may use preferences from older builds. */
    public boolean usesLegacySettings() { return this != KL_FPV; }
    public String defaultHost() { return this == WL_TECH ? "172.16.11.1" : ""; }
    public String defaultPort() { return this == WL_TECH ? "23458" : ""; }
}
