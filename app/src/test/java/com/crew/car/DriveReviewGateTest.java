package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DriveReviewGateTest {
    @Test public void blocksOverlappingRequestsUntilMatchingToolOrTurnComplete() {
        DriveReviewGate gate = new DriveReviewGate();
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
