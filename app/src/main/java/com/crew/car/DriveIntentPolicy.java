package com.crew.car;

/**
 * One spoken command -> a conservative on-device motor lease.
 * No depth/odometry/obstacle clearance is measured; this is NOT navigation.
 * Voice 'continuous' means one short, video-fresh lease, never unlimited travel.
 */
public final class DriveIntentPolicy {
    public static final int NUDGE_MS = 500;
    public static final int FORWARD_MS = 900;
    /** Explicit continuous speech gets one capped 2-second lease, NOT auto-renewal. */
    public static final int SUPERVISED_CRUISE_MS = 2000;
    public static final int REVERSE_MS = 900;
    public static final int FRONT_DIAGONAL_MS = 600;
    public static final int TURN_RECENTER_MS = 650;

    public static final class Plan {
        public final String intent;
        public final String steering;
        public final String throttle;
        public final int leaseMs;
        /** A diagonal must end steering AND throttle together, never coast straight. */
        public final boolean stopWithSteering;

        private Plan(String intent, String steering, String throttle, int leaseMs,
                     boolean stopWithSteering) {
            this.intent = intent;
            this.steering = steering;
            this.throttle = throttle;
            this.leaseMs = leaseMs;
            this.stopWithSteering = stopWithSteering;
        }
    }

    private DriveIntentPolicy() {}

    /**
     * Pure scheduler decision, checked before any steering re-center or motor TX.
     * Returning true means send neutral on BOTH axes, never a straight tail.
     */
    static boolean mustStopBothAxes(long now, long deadline, long steeringReleaseAt,
                                    boolean stopWithSteering) {
        return now >= deadline ||
                (stopWithSteering && steeringReleaseAt > 0 && now >= steeringReleaseAt);
    }

    /** Null rejects unsupported or indefinite motion. */
    public static Plan plan(String intent) {
        if (intent == null) return null;
        switch (intent) {
            case "forward_small":
                return new Plan(intent, "center", "forward", NUDGE_MS, false);
            case "forward":
                return new Plan(intent, "center", "forward", FORWARD_MS, false);
            case "forward_continuous":
                return new Plan(intent, "center", "forward", SUPERVISED_CRUISE_MS, false);
            case "backward_small":
                return new Plan(intent, "center", "reverse", NUDGE_MS, false);
            case "backward":
                return new Plan(intent, "center", "reverse", REVERSE_MS, false);
            case "front_left_small":
                return new Plan(intent, "left", "forward", NUDGE_MS, true);
            case "front_right_small":
                return new Plan(intent, "right", "forward", NUDGE_MS, true);
            case "front_left":
                return new Plan(intent, "left", "forward", FRONT_DIAGONAL_MS, true);
            case "front_right":
                return new Plan(intent, "right", "forward", FRONT_DIAGONAL_MS, true);
            default:
                return null;
        }
    }
}
