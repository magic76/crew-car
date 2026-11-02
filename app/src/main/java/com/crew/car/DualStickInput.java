package com.crew.car;

/**
 * Pure Java two-thumb input state. Steering occupies the left 26% of the surface;
 * throttle occupies the right 26%. The central 48% is reserved for the FPV image.
 *
 * Pointer IDs never change ownership when a finger slides across the display.
 * Lifting one finger releases ONLY its own axis; cancelling releases both.
 */
public final class DualStickInput {
    public static final float SIDE_FRACTION = 0.26f;
    public static final float DEAD_ZONE = 0.26f;
    private float sideFraction = SIDE_FRACTION;
    private float deadZone = DEAD_ZONE;
    private int steeringPointer = -1;
    private int throttlePointer = -1;
    private int steering;
    private int throttle;

    public void configure(float sideFraction, float deadZone) {
        if (sideFraction < .18f || sideFraction > .26f ||
                deadZone < .15f || deadZone > .35f)
            throw new IllegalArgumentException("input geometry invalid");
        cancel();
        this.sideFraction = sideFraction;
        this.deadZone = deadZone;
    }
    public int steering() { return steering; }
    public int throttle() { return throttle; }
    public boolean hasActivePointer() { return steeringPointer != -1 || throttlePointer != -1; }

    public void down(int id, float x, float y, float width, float height) {
        if (id < 0 || !valid(x, y, width, height)) return;
        if (x < width * sideFraction && steeringPointer == -1) {
            steeringPointer = id;
            steering = mapAxis(x, width * sideFraction, false);
        } else if (x >= width * (1f - sideFraction) && throttlePointer == -1) {
            throttlePointer = id;
            throttle = mapAxis(y, height, true);
        }
    }

    public void move(int id, float x, float y, float width, float height) {
        if (!valid(x, y, width, height)) {
            up(id);
            return;
        }
        // A finger leaving its own zone centers that axis instead of sustaining motion
        // across the video or accidentally taking control of the opposite thumb.
        if (id == steeringPointer)
            steering = x >= 0f && x < width * sideFraction && y >= 0f && y <= height
                    ? mapAxis(x, width * sideFraction, false) : 0;
        if (id == throttlePointer)
            throttle = x >= width * (1f - sideFraction) && x <= width &&
                    y >= 0f && y <= height
                    ? mapAxis(y, height, true) : 0;
    }

    public void up(int id) {
        if (id == steeringPointer) {
            steeringPointer = -1;
            steering = 0;
        }
        if (id == throttlePointer) {
            throttlePointer = -1;
            throttle = 0;
        }
    }

    public void cancel() {
        steeringPointer = -1;
        throttlePointer = -1;
        steering = 0;
        throttle = 0;
    }

    private static boolean valid(float x, float y, float width, float height) {
        return Float.isFinite(x) && Float.isFinite(y) &&
                Float.isFinite(width) && Float.isFinite(height) &&
                width > 0f && height > 0f;
    }

    private int mapAxis(float coordinate, float extent, boolean inverted) {
        float normalized = (coordinate - extent / 2f) / (extent / 2f);
        int direction = normalized < -deadZone ? -1 :
                normalized > deadZone ? 1 : 0;
        return inverted ? -direction : direction;
    }
}
