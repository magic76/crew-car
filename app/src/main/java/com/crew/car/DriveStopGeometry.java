package com.crew.car;

/**
 * Shared cockpit geometry for the independent emergency STOP hit target.
 * Pure Java, so UI clearance can be regression-tested without Android.
 */
public final class DriveStopGeometry {
    public static final int STOP_WIDTH_DP = 92;
    public static final int STOP_HEIGHT_DP = 56;
    public static final int STOP_MARGIN_DP = 8;
    public static final int SAFETY_GAP_DP = 12;
    private static final int THROTTLE_BASE_BOTTOM_DP = 80;

    private DriveStopGeometry() {}

    /** Top edge of bottom-aligned floating emergency STOP. */
    public static float stopTopPx(float heightPx, float density) {
        return heightPx - (STOP_HEIGHT_DP + STOP_MARGIN_DP) * density;
    }

    /**
     * Right throttle's bottom edge must never intersect the STOP hitbox.
     * The vertical offset still shifts the throttle upward when requested.
     */
    public static float throttleBottomPx(float heightPx, float density, int offsetDp) {
        if (density <= 0 || !Float.isFinite(density) || !Float.isFinite(heightPx) ||
                offsetDp < -24 || offsetDp > 24)
            throw new IllegalArgumentException("invalid cockpit geometry");
        float safeBottom = stopTopPx(heightPx, density) - SAFETY_GAP_DP * density;
        return Math.min(safeBottom,
                heightPx - (THROTTLE_BASE_BOTTOM_DP - offsetDp) * density);
    }
}
