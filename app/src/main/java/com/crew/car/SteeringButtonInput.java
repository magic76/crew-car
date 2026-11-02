package com.crew.car;

/** A left-thumb steering press owns one pointer until release or area exit. */
public final class SteeringButtonInput {
    private int pointerId = -1;
    private int direction;

    public int steering() { return direction; }

    public void down(int id, float x, float y, float width, float height) {
        if (pointerId >= 0 || id < 0) return;
        int hit = hit(x, y, width, height);
        if (hit == 0) return;
        pointerId = id;
        direction = hit;
    }

    public void move(int id, float x, float y, float width, float height) {
        if (id == pointerId && hit(x, y, width, height) != direction) cancel();
    }

    public void up(int id) {
        if (id == pointerId) cancel();
    }

    public void cancel() {
        pointerId = -1;
        direction = 0;
    }

    public static int hit(float x, float y, float width, float height) {
        if (!Float.isFinite(x) || !Float.isFinite(y) ||
                !Float.isFinite(width) || !Float.isFinite(height) ||
                width <= 0 || height <= 0 || x < 0 || x >= width || y < 0 || y > height)
            return 0;
        float divider = width * .06f;
        float split = width * .5f;
        if (x < split - divider) return -1;
        if (x > split + divider) return 1;
        return 0;
    }
}
