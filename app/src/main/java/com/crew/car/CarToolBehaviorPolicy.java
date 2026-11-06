package com.crew.car;

/**
 * Gemini Live: motion/state-changing calls must return a result before the
 * assistant speaks about the outcome. FPV steering corrections remain
 * non-blocking to avoid holding the voice path during ongoing review.
 */
public final class CarToolBehaviorPolicy {
    private CarToolBehaviorPolicy() {}

    public static String forName(String name) {
        return "adjust_ai_drive_session".equals(name) ? "NON_BLOCKING" : "BLOCKING";
    }
}
