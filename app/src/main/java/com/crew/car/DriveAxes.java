package com.crew.car;

/** Stateless 8-way touch-pad mapping, independent of Android and motor hardware. */
public final class DriveAxes {
    private static final float DEAD_ZONE = 0.24f;
    private DriveAxes() { }

    /** Returns [steering(-1..1), throttle(-1..1)]; positive throttle means forward. */
    public static int[] fromPoint(float x, float y, float width, float height) {
        if (width <= 0 || height <= 0 || !Float.isFinite(x) || !Float.isFinite(y))
            return new int[]{0, 0};
        float horizontal = (x - width / 2f) / (width / 2f);
        float vertical = (height / 2f - y) / (height / 2f);
        int steer = horizontal < -DEAD_ZONE ? -1 : horizontal > DEAD_ZONE ? 1 : 0;
        int throttle = vertical > DEAD_ZONE ? 1 : vertical < -DEAD_ZONE ? -1 : 0;
        return new int[]{steer, throttle};
    }
}
