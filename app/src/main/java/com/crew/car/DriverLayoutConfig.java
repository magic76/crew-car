package com.crew.car;

public final class DriverLayoutConfig {
    public final int sidePercent;
    public final int gapDp;
    public final int offsetDp;
    public final int deadZonePercent;
    public final boolean buttonSteering;
    public final boolean haptics;

    public DriverLayoutConfig(int sidePercent, int gapDp, int offsetDp,
                              int deadZonePercent, boolean buttonSteering, boolean haptics) {
        if (sidePercent < 18 || sidePercent > 26 ||
                gapDp < 8 || gapDp > 30 ||
                offsetDp < -24 || offsetDp > 24 ||
                deadZonePercent < 15 || deadZonePercent > 35)
            throw new IllegalArgumentException("driver UI settings invalid");
        this.sidePercent = sidePercent;
        this.gapDp = gapDp;
        this.offsetDp = offsetDp;
        this.deadZonePercent = deadZonePercent;
        this.buttonSteering = buttonSteering;
        this.haptics = haptics;
    }

    public static DriverLayoutConfig defaults() {
        return new DriverLayoutConfig(20, 10, 0, 26, true, true);
    }

    public float sideFraction() { return sidePercent / 100f; }
    public float videoFraction() { return 1f - 2f * sideFraction(); }
}
