package com.crew.car;

/**
 * One semantic intent -> one deterministic local motor lease.
 * No model-supplied milliseconds, no distance/odometry assumptions, no authorization window.
 * Motor arming, network/focus gates, manual priority and STOP are applied by CarControl.
 */
public final class DriveIntentPolicy {
    public static final int NUDGE_MS = 500;
    public static final int FORWARD_MS = 2500;
    public static final int FRONT_DIAGONAL_MS = 2000;
    public static final int CRUISE_MS = 15000;
    public static final int TURN_RECENTER_MS = 650;

    public static final class Plan {
        public final String intent;
        public final String steering;
        public final String throttle;
        public final int leaseMs;

        private Plan(String intent, String steering, String throttle, int leaseMs) {
            this.intent = intent;
            this.steering = steering;
            this.throttle = throttle;
            this.leaseMs = leaseMs;
        }
    }

    private DriveIntentPolicy() {}

    /** Null means reject; no implicit reverse or indefinite motion is possible. */
    public static Plan plan(String intent) {
        if (intent == null) return null;
        switch (intent) {
            case "forward_small":
                return new Plan(intent, "center", "forward", NUDGE_MS);
            case "forward":
                return new Plan(intent, "center", "forward", FORWARD_MS);
            case "forward_continuous":
                return new Plan(intent, "center", "forward", CRUISE_MS);
            case "backward_small":
                return new Plan(intent, "center", "reverse", NUDGE_MS);
            case "backward":
                return new Plan(intent, "center", "reverse", 2000);
            case "front_left_small":
                return new Plan(intent, "left", "forward", NUDGE_MS);
            case "front_right_small":
                return new Plan(intent, "right", "forward", NUDGE_MS);
            case "front_left":
                return new Plan(intent, "left", "forward", FRONT_DIAGONAL_MS);
            case "front_right":
                return new Plan(intent, "right", "forward", FRONT_DIAGONAL_MS);
            default:
                return null;
        }
    }
}
