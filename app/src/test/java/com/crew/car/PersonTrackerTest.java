package com.crew.car;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public final class PersonTrackerTest {
    private static PersonTracker.Box box(float x, float y) {
        return new PersonTracker.Box(x, y, x + .18f, y + .35f, .91f);
    }

    @Test public void neverAutoSelectsPeople() {
        PersonTracker tracker = new PersonTracker();
        tracker.observe(Arrays.asList(box(.10f,.10f), box(.65f,.10f)), 100);
        assertEquals(2, tracker.boxes().size());
        assertEquals(-1, tracker.selectedIndex());
        assertFalse(tracker.hasSelection());
    }

    @Test public void tapSelectsOnlyVisibleBoxAndTracksSmallMotion() {
        PersonTracker tracker = new PersonTracker();
        tracker.observe(Arrays.asList(box(.10f,.10f), box(.65f,.10f)), 100);
        assertFalse(tracker.select(.5f,.9f, 120));
        assertTrue(tracker.select(.70f,.20f, 130));
        assertEquals(1, tracker.selectedIndex());
        tracker.observe(Arrays.asList(box(.12f,.12f),box(.67f,.11f)), 250);
        assertEquals(1, tracker.selectedIndex());
        assertFalse(tracker.targetLost());
    }

    @Test public void missingTargetNeverSwitchesToOtherPerson() {
        PersonTracker tracker = new PersonTracker();
        tracker.observe(Arrays.asList(box(.10f,.10f),box(.65f,.10f)), 100);
        assertTrue(tracker.select(.14f,.2f, 120));
        tracker.observe(Collections.singletonList(box(.65f,.10f)), 250);
        assertTrue(tracker.targetLost());
        assertEquals(-1, tracker.selectedIndex());
        tracker.observe(Arrays.asList(box(.10f,.10f), box(.65f,.10f)), 360);
        assertEquals(-1, tracker.selectedIndex());
        assertTrue(tracker.targetLost());
        assertTrue(tracker.select(.14f,.2f, 400));
        assertFalse(tracker.targetLost());
    }

    @Test public void detectsStaleCameraAndRequiresManualRelock() {
        PersonTracker tracker = new PersonTracker();
        tracker.observe(Collections.singletonList(box(.10f,.10f)), 100);
        tracker.select(.15f,.2f, 110);
        tracker.expire(1500);
        assertTrue(tracker.targetLost());
        assertEquals(0, tracker.boxes().size());
        tracker.observe(Collections.singletonList(box(.11f,.10f)), 1600);
        assertEquals(-1, tracker.selectedIndex());
    }

    @Test public void invalidCoordinatesAndFramesCannotCreateFalseTargets() {
        PersonTracker tracker = new PersonTracker();
        try { new PersonTracker.Box(Float.NaN,0,.5f,.6f,.9f); fail(); }
        catch (IllegalArgumentException expected) {}
        tracker.observe(Collections.singletonList(box(.1f,.1f)), 100);
        tracker.observe(Collections.emptyList(), 99); // stale result ignored
        assertEquals(1, tracker.boxes().size());
        assertFalse(tracker.select(Float.NaN,.15f,125));
        assertFalse(tracker.select(.15f,.15f,0));
    }

    @Test public void intersectingBoxesHaveExpectedIoU() {
        assertEquals(1f, PersonTracker.iou(box(.1f,.1f),box(.1f,.1f)), .0001f);
        assertEquals(0f, PersonTracker.iou(box(.1f,.1f),box(.65f,.1f)), .0001f);
    }
}
