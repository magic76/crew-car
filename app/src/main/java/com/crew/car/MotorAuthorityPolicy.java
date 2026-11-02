package com.crew.car;

/** Pure, explicit ownership checks for a single car motor output channel. */
public final class MotorAuthorityPolicy {
    public enum Owner { LOCKED, IDLE, MANUAL, GEMINI, VISION }
    private MotorAuthorityPolicy() { }

    public static Owner current(boolean armed, boolean manual,
                                boolean geminiActive, boolean visionActive) {
        if (!armed) return Owner.LOCKED;
        if (manual) return Owner.MANUAL;
        if (visionActive) return Owner.VISION;
        if (geminiActive) return Owner.GEMINI;
        return Owner.IDLE;
    }

    /** Only an idle, armed car may grant new automated motion authority. */
    public static boolean canClaim(Owner current, Owner requested) {
        return current == Owner.IDLE &&
                (requested == Owner.GEMINI || requested == Owner.VISION);
    }

    /** Existing holders may update only their own active motor stream. */
    public static boolean canUpdate(Owner current, Owner sender) {
        return current == sender && current != Owner.IDLE &&
                current != Owner.LOCKED;
    }

    /** A human E-STOP / physical manual thumb may always preempt automation. */
    public static boolean mayPreempt(Owner current) {
        return current == Owner.GEMINI || current == Owner.VISION;
    }
}
