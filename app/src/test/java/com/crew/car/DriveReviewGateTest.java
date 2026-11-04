package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DriveReviewGateTest {
    @Test public void blocksOverlappingRequestsUntilMatchingToolOrTurnComplete() {
        DriveReviewGate gate = new DriveReviewGate();
        assertEquals(2500, DriveReviewGate.MIN_REVIEW_INTERVAL_MS);
        assertTrue(gate.begin(10, 1000));
        assertFalse(gate.begin(10, 1500));
        assertFalse(gate.begin(11, 1700));
        gate.completeTool(9); // stale tool result
        assertTrue(gate.isPending());
        gate.completeTool(10);
        assertFalse(gate.isPending());
        assertTrue(gate.begin(11, 1800));
        gate.completeTurn();
        assertFalse(gate.isPending());
        assertEquals(2, gate.queuedCount());
        assertEquals(2, gate.skippedCount());
    }

    @Test public void hungReviewExpiresRatherThanBlockingNewExplicitUserSessionForever() {
        DriveReviewGate gate = new DriveReviewGate();
        assertTrue(gate.begin(5, 1000));
        assertFalse(gate.begin(6, 5499));
        assertTrue(gate.begin(6, 5500));
        gate.completeTool(5);
        assertTrue(gate.isPending());
        gate.completeTool(6);
        assertFalse(gate.isPending());
        assertEquals(1, gate.expiredCount());
    }

    @Test public void modelCanCorrectSameSessionRepeatedlyForFullThirtySeconds() {
        DriveReviewGate gate = new DriveReviewGate();
        long sessionId = 42;
        // Each FPV check completes before the next one; no new motor
        // session ID is required throughout the 30-second driving window.
        for (long at = 700; at <= 28_200; at += DriveReviewGate.MIN_REVIEW_INTERVAL_MS) {
            assertTrue("review " + at, gate.begin(sessionId, at));
            assertFalse("overlap " + at, gate.begin(sessionId, at + 100));
            gate.completeTool(sessionId);
            assertFalse(gate.isPending());
        }
        assertEquals(12, gate.queuedCount());
        assertEquals(12, gate.skippedCount());
        assertEquals(0, gate.expiredCount());
    }

    @Test public void resetClearsOldLiveConnectionPendingState() {
        DriveReviewGate gate = new DriveReviewGate();
        assertFalse(gate.begin(0, 100));
        assertTrue(gate.begin(1, 100));
        assertEquals(100, gate.pendingAgeMs(200));
        gate.reset();
        assertEquals(-1, gate.pendingAgeMs(300));
        assertTrue(gate.begin(2, 301));
    }
}
