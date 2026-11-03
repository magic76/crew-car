package com.crew.car;

/**
 * A direction-relative nudge, NOT route planning or a distance-to-target estimate.
 * Only the human / Gemini can supply an explicit approximate front bearing.
 */
public final class TargetApproachPolicy {
    public static final int MIN_DURATION_MS = 150;
    public static final int MAX_DURATION_MS = 600;
    public static final int TURN_RECENTER_MS = 650;
    public static final int MAX_PULSES = 3;
    public static final long AUTHORIZATION_MS = 20_000L;
    public static final long MIN_PULSE_SPACING_MS = 750L;
    private TargetApproachPolicy() {}

    public static String validate(String bearing, int durationMs) {
        if (steering(bearing) == Integer.MIN_VALUE)
            return "blocked: target bearing must be front_left/ahead/front_right";
        if (durationMs < MIN_DURATION_MS || durationMs > MAX_DURATION_MS)
            return "blocked: duration_ms must be 150..600";
        return null;
    }

    public static int steering(String bearing) {
        if ("front_left".equals(bearing)) return -1;
        if ("ahead".equals(bearing)) return 0;
        if ("front_right".equals(bearing)) return 1;
        return Integer.MIN_VALUE;
    }
}
