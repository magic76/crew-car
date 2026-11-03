package com.crew.car;

/**
 * Gemini may propose a coarse person bearing from FPV, never a distance or a speed.
 * One explicit speech request produces at most one supervised short motor lease.
 */
public final class PersonBearingPolicy {
    private PersonBearingPolicy() {}

    /** Unknown/ambiguous view must not cause any motor movement. */
    public static String intentFor(String bearing) {
        if (bearing == null) return null;
        switch (bearing) {
            case "left": return "front_left_small";
            case "center": return "forward_small";
            case "right": return "front_right_small";
            default: return null;
        }
    }
}
