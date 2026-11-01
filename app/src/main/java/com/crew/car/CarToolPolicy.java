package com.crew.car;

/**
 * Pure-Java guardrail for untrusted Gemini tool arguments.
 * Physical authorization and camera/network gates are enforced separately by the Activity.
 */
public final class CarToolPolicy {
    public static final int MIN_MOVE_MS = 100;
    public static final int MAX_MOVE_MS = 650;

    private CarToolPolicy() {}

    public static boolean isSupported(String name) {
        return "drive_car".equals(name) || "stop_car".equals(name) ||
                "lock_car".equals(name) || "get_car_status".equals(name) ||
                "get_camera_status".equals(name) || "get_network_status".equals(name);
    }

    /** A null result means valid. All other strings explain why execution is blocked. */
    public static String validateMove(String direction, int ms) {
        if (!"forward".equals(direction) && !"backward".equals(direction) &&
                !"left".equals(direction) && !"right".equals(direction))
            return "blocked: invalid direction (use stop_car to stop)";
        if (ms < MIN_MOVE_MS || ms > MAX_MOVE_MS)
            return "blocked: duration_ms must be between 100 and 650";
        return null;
    }
}
