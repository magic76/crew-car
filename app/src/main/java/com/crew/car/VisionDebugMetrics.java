package com.crew.car;

/** Device-only monotonic timing for detection. No camera frames or identifying data stored. */
public final class VisionDebugMetrics {
    private long lastFrameAt;
    private long lastResultAt;
    private long lastIntervalMs;
    private long processingMs;
    private long inferenceMs;
    private float fps;
    private float confidence;
    private float offset;
    private int people;

    public synchronized void reset() {
        lastFrameAt = lastResultAt = lastIntervalMs = processingMs = inferenceMs = 0L;
        fps = confidence = offset = 0f;
        people = 0;
    }

    public synchronized void observe(long capturedAt, long resultAt, long modelMs,
                                     int detectedPeople, PersonTracker.Box selected) {
        if (capturedAt <= 0 || resultAt < capturedAt || capturedAt <= lastFrameAt) return;
        if (lastFrameAt > 0) {
            long delta = capturedAt - lastFrameAt;
            if (delta > 0 && delta < 5000) {
                float instantaneous = 1000f / delta;
                fps = fps == 0f ? instantaneous : fps * .7f + instantaneous * .3f;
                lastIntervalMs = delta;
            }
        }
        lastFrameAt = capturedAt;
        lastResultAt = resultAt;
        inferenceMs = Math.max(0L, modelMs);
        processingMs = resultAt - capturedAt;
        people = detectedPeople;
        confidence = selected == null ? 0f : selected.score;
        offset = selected == null ? 0f : selected.centerX() - .5f;
    }

    public synchronized float fps() { return fps; }
    public synchronized long resultAgeMs(long now) {
        return lastResultAt == 0 ? -1 : Math.max(0, now - lastResultAt);
    }
    public synchronized long processingMs() { return processingMs; }
    public synchronized long inferenceMs() { return inferenceMs; }
    public synchronized int people() { return people; }
    public synchronized float confidence() { return confidence; }
    public synchronized float offset() { return offset; }
    public synchronized long lastIntervalMs() { return lastIntervalMs; }
}
