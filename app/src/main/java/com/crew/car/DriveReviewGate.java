package com.crew.car;

/**
 * Prevents overlapping Live visual-review turns. The motor ticker never waits
 * on this state: it keeps its own absolute five-second STOP deadline.
 *
 * Caller also enforces FIRST_REVIEW_DELAY_MS / MIN_REVIEW_INTERVAL_MS.
 * This class only grants one pending cloud review at a time.
 */
public final class DriveReviewGate {
    public static final long FIRST_REVIEW_DELAY_MS = 700;
    public static final long MIN_REVIEW_INTERVAL_MS = AiDriveSessionPolicy.VIDEO_REVIEW_INTERVAL_MS;
    public static final long IN_FLIGHT_TIMEOUT_MS = 4_500;

    private long pendingSessionId;
    private long pendingSince;
    private long queuedCount;
    private long skippedCount;
    private long expiredCount;

    public synchronized boolean begin(long sessionId, long nowMs) {
        if (sessionId <= 0 || nowMs < 0) return false;
        if (pendingSessionId != 0) {
            long age = nowMs - pendingSince;
            if (age >= 0 && age < IN_FLIGHT_TIMEOUT_MS) {
                skippedCount++;
                return false;
            }
            // The pending model response never arrived. Avoid deadlock while
            // keeping a strictly bounded number of requests per 5s session.
            expiredCount++;
            pendingSessionId = 0;
        }
        pendingSessionId = sessionId;
        pendingSince = nowMs;
        queuedCount++;
        return true;
    }

    /** A tool call for a stale session cannot complete a newer review. */
    public synchronized void completeTool(long sessionId) {
        if (pendingSessionId == sessionId && sessionId > 0) clearPending();
    }

    /** Gemini may respond with text/audio instead of calling an adjustment tool. */
    public synchronized void completeTurn() { clearPending(); }

    public synchronized void reset() { clearPending(); }

    private void clearPending() {
        pendingSessionId = 0;
        pendingSince = 0;
    }

    public synchronized boolean isPending() { return pendingSessionId != 0; }
    public synchronized long pendingAgeMs(long nowMs) {
        return pendingSessionId == 0 ? -1 : Math.max(0, nowMs - pendingSince);
    }
    public synchronized long queuedCount() { return queuedCount; }
    public synchronized long skippedCount() { return skippedCount; }
    public synchronized long expiredCount() { return expiredCount; }
}
