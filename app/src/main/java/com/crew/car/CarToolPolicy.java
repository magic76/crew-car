package com.crew.car;

/**
 * Pure-Java guardrail for untrusted Gemini tool arguments.
 * Physical authorization and camera/network gates are enforced separately by the Activity.
 */
public final class CarToolPolicy {
    public static final int MIN_MOVE_MS = 100;
    public static final int MAX_MOVE_MS = 650;
    /** Each hold is a bounded lease, never an indefinite autonomous drive. */
    public static final int MIN_HOLD_MS = 500;
    public static final int MAX_HOLD_MS = 30000;
    public static final int DEFAULT_HOLD_MS = 15000;

    private CarToolPolicy() {}

    public static boolean isSupported(String name) {
        return "drive_car".equals(name) || "stop_car".equals(name) ||
                "lock_car".equals(name) || "get_car_status".equals(name) ||
                "get_camera_status".equals(name) || "get_network_status".equals(name) ||
                "set_drive_state".equals(name);
    }

    public static String validateHold(String steering, String throttle, int leaseMs) {
        if (!"left".equals(steering) && !"right".equals(steering) &&
                !"center".equals(steering) && !"keep".equals(steering))
            return "blocked: steering must be left/right/center/keep";
        if (!"forward".equals(throttle) && !"reverse".equals(throttle) &&
                !"neutral".equals(throttle) && !"keep".equals(throttle))
            return "blocked: throttle must be forward/reverse/neutral/keep";
        if (leaseMs < MIN_HOLD_MS || leaseMs > MAX_HOLD_MS)
            return "blocked: lease_ms must be between 500 and 30000";
        return null;
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
