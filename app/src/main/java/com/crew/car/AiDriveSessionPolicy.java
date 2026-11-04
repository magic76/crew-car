package com.crew.car;

/**
 * Bounded supervised AI driving: Android owns periodic motor TX and expiry;
 * Gemini only updates steering/relative throttle. Percentages are OEM packet
 * gains, NOT measured speed or safe stopping distances.
 */
public final class AiDriveSessionPolicy {
    public static final int MAX_SESSION_MS = 5_000;
    public static final int STALE_CONTROL_MS = 5_000;
    public static final int STEERING_HOLD_MS = 900;
    public static final int VIDEO_REVIEW_INTERVAL_MS = 1_500;
    public static final int SLOW_GAIN = 50;
    public static final int NORMAL_GAIN = 65;
    public static final int FAST_GAIN = 80;

    private AiDriveSessionPolicy() {}

    public static boolean validSteering(String direction) {
        return "left".equals(direction) || "right".equals(direction) ||
                "center".equals(direction) || "keep".equals(direction);
    }

    public static boolean validSpeed(String speed) {
        return "slow".equals(speed) || "normal".equals(speed) ||
                "fast".equals(speed) || "keep".equals(speed);
    }

    public static int steering(String direction, int previous) {
        if (!validSteering(direction)) throw new IllegalArgumentException("invalid steering");
        switch (direction) {
            case "left": return -1;
            case "right": return 1;
            case "center": return 0;
            default: return previous;
        }
    }

    public static int speedGain(String speed, int previousGain) {
        if (!validSpeed(speed)) throw new IllegalArgumentException("invalid speed");
        switch (speed) {
            case "slow": return SLOW_GAIN;
            case "normal": return NORMAL_GAIN;
            case "fast": return FAST_GAIN;
            default: return previousGain;
        }
    }

    public static boolean shouldStop(long now, long startedAt, long lastControlAt) {
        return now < startedAt || lastControlAt < startedAt ||
                now - startedAt >= MAX_SESSION_MS ||
                now - lastControlAt >= STALE_CONTROL_MS;
    }

    public static String stopReason(long now, long startedAt, long lastControlAt) {
        if (now < startedAt || lastControlAt < startedAt) return "invalid session time";
        if (now - startedAt >= MAX_SESSION_MS) return "AI drive session max duration";
        if (now - lastControlAt >= STALE_CONTROL_MS) return "AI drive steering/speed updates stale";
        return "";
    }
}
