package com.crew.car;

/**
 * Fail-closed visual servo for an explicitly selected person.
 * Box size is NOT a distance measurement and this is NOT obstacle avoidance.
 * It produces only coarse low-power commands; no reverse and no search motion.
 */
public final class VisionFollowPlanner {
    private VisionFollowPlanner() {}

    public static final class Step {
        public final int steer, throttle;
        public final String reason;
        private Step(int steer, int throttle, String reason) {
            this.steer = steer; this.throttle = throttle; this.reason = reason;
        }
        public boolean moving() { return throttle > 0; }
    }

    public static Step decide(PersonTracker.Box target) {
        if (target == null || target.score < .60f)
            return new Step(0, 0, "target missing or low confidence");
        float x = target.centerX();
        float height = target.height();
        if (!Float.isFinite(x) || !Float.isFinite(height) ||
                height >= .23f)
            return new Step(0, 0, "target appears close; stop");
        if (height < .075f)
            return new Step(0, 0, "target too small to track safely");
        float error = x - .5f;
        if (Math.abs(error) > .20f)
            return new Step(0, 0, "target far off center; stop");
        int steer = error < -.065f ? -1 : error > .065f ? 1 : 0;
        // No automatic reverse, no continuous blind turning, no inferred metres.
        return new Step(steer, 1, steer == 0 ? "forward" : "forward with limited steering");
    }
}
