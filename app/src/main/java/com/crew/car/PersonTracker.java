package com.crew.car;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Display-only, single-target frame-to-frame bounding-box matcher.
 * NO facial recognition, identity claims, control commands or automatic target selection.
 * When a selected target is lost, it never switches to another person on its own.
 */
public final class PersonTracker {
    public static final class Box {
        public final float left, top, right, bottom, score;
        public Box(float left, float top, float right, float bottom, float score) {
            if (!Float.isFinite(left) || !Float.isFinite(top) ||
                    !Float.isFinite(right) || !Float.isFinite(bottom) ||
                    !Float.isFinite(score) || left < 0f || top < 0f ||
                    right > 1f || bottom > 1f || right <= left || bottom <= top)
                throw new IllegalArgumentException("invalid normalized person rectangle");
            this.left = left; this.top = top;
            this.right = right; this.bottom = bottom; this.score = score;
        }
        public float width() { return right - left; }
        public float height() { return bottom - top; }
        public float centerX() { return (left + right) * .5f; }
        public float centerY() { return (top + bottom) * .5f; }
        public boolean contains(float x, float y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    private final List<Box> boxes = new ArrayList<>();
    private int selectedIndex = -1;
    private Box previousTarget;
    private boolean lost;
    private long lastFrameAt;
    private long lastSelectedAt;

    public synchronized void clear() {
        boxes.clear();
        selectedIndex = -1;
        previousTarget = null;
        lost = false;
        lastFrameAt = 0;
        lastSelectedAt = 0;
    }

    /** Only an explicit on-screen tap can initially lock or reselect a target. */
    public synchronized boolean select(float x, float y, long now) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || now <= 0) return false;
        int candidate = -1;
        float smallestArea = Float.MAX_VALUE;
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            if (box.contains(x, y) && box.width() * box.height() < smallestArea) {
                smallestArea = box.width() * box.height();
                candidate = i;
            }
        }
        if (candidate < 0) return false;
        selectedIndex = candidate;
        previousTarget = boxes.get(candidate);
        lost = false;
        lastSelectedAt = now;
        return true;
    }

    public synchronized void observe(List<Box> detections, long now) {
        if (now <= lastFrameAt || detections == null) return;
        lastFrameAt = now;
        boxes.clear();
        boxes.addAll(detections);

        if (previousTarget == null) { selectedIndex = -1; return; }
        // Once the target becomes stale/lost, do not silently re-target another person.
        if (lost || now - lastSelectedAt > 1500) {
            selectedIndex = -1;
            lost = true;
            return;
        }
        float best = 0f;
        int match = -1;
        for (int i = 0; i < boxes.size(); i++) {
            Box next = boxes.get(i);
            float overlap = iou(previousTarget, next);
            float distance = (float) Math.hypot(
                    next.centerX() - previousTarget.centerX(),
                    next.centerY() - previousTarget.centerY());
            if (overlap >= .25f && distance <= .20f && overlap > best) {
                best = overlap;
                match = i;
            }
        }
        selectedIndex = match;
        if (match < 0) {
            lost = true;
        } else {
            previousTarget = boxes.get(match);
            lastSelectedAt = now;
        }
    }

    public synchronized void expire(long now) {
        if (lastFrameAt == 0 || now - lastFrameAt <= 1200) return;
        boxes.clear();
        selectedIndex = -1;
        if (previousTarget != null) lost = true;
    }

    public synchronized List<Box> boxes() {
        return Collections.unmodifiableList(new ArrayList<>(boxes));
    }
    public synchronized int selectedIndex() { return selectedIndex; }
    public synchronized boolean hasSelection() { return previousTarget != null; }
    public synchronized boolean targetLost() { return lost; }
    /** Never expose the last-known stale box as an active target. */
    public synchronized Box selectedBox() {
        return !lost && selectedIndex >= 0 && selectedIndex < boxes.size()
                ? boxes.get(selectedIndex) : null;
    }
    public synchronized long lastFrameAt() { return lastFrameAt; }

    public static float iou(Box a, Box b) {
        float left = Math.max(a.left, b.left);
        float top = Math.max(a.top, b.top);
        float right = Math.min(a.right, b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
        float areaA = a.width() * a.height();
        float areaB = b.width() * b.height();
        float union = areaA + areaB - intersection;
        return union <= 0 ? 0 : intersection / union;
    }
}
