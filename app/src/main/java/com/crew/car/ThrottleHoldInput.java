package com.crew.car;

/**
 * Explicit physical accelerator/deadman state. Once pressed, direction belongs
 * to that pointer until UP or CANCEL. Pointer drift must not cut engine power.
 * There is NO sticky auto-cruise on manual controls.
 */
public final class ThrottleHoldInput {
    private int pointer = -1;
    private int throttle;

    public int throttle() { return throttle; }
    public boolean pressed() { return pointer >= 0; }

    public void down(int id, int direction) {
        if (id < 0 || pointer >= 0 || (direction != 1 && direction != -1)) return;
        pointer = id;
        throttle = direction;
    }

    /** Movement across the screen does not imply a finger release. */
    public void move(int id, float x, float y) {
        // Intentionally do nothing. Android ACTION_UP/CANCEL is authoritative.
    }

    public void up(int id) {
        if (id == pointer) cancel();
    }

    public void cancel() {
        pointer = -1;
        throttle = 0;
    }
}
