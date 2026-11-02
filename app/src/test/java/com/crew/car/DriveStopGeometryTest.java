package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DriveStopGeometryTest {
    @Test public void emergencyStopNeverOverlapsRightThrottleAtAnyAllowedOffset() {
        for (float density : new float[]{1f, 2f, 3f, 4f}) {
            for (float heightDp : new float[]{240f, 320f, 420f}) {
                float height = heightDp * density;
                for (int offset = -24; offset <= 24; offset++) {
                    float throttleEnd = DriveStopGeometry.throttleBottomPx(
                            height, density, offset);
                    float emergencyStart = DriveStopGeometry.stopTopPx(height, density);
                    assertTrue(throttleEnd + 12 * density <= emergencyStart + 0.01f);
                    assertTrue(throttleEnd < height);
                }
            }
        }
    }

    @Test public void smallerThrottleOffsetStillMovesHitboxUpwards() {
        float height = 280f;
        float raised = DriveStopGeometry.throttleBottomPx(height, 1f, -24);
        float neutral = DriveStopGeometry.throttleBottomPx(height, 1f, 0);
        assertTrue(raised < neutral);
    }

    @Test public void invalidGeometryFailsClosed() {
        try {
            DriveStopGeometry.throttleBottomPx(300, 1, 25);
            fail("unsupported offset accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            DriveStopGeometry.throttleBottomPx(300, 0, 0);
            fail("invalid density accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
