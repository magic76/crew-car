package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VisionFollowPlannerTest {
    private PersonTracker.Box person(float cx, float h, float confidence) {
        return new PersonTracker.Box(cx-.08f, .35f, cx+.08f, .35f+h, confidence);
    }
    @Test public void forwardWhenCenteredAndNotApparentlyClose() {
        VisionFollowPlanner.Step step = VisionFollowPlanner.decide(person(.50f,.13f,.95f));
        assertEquals(0, step.steer);
        assertEquals(1, step.throttle);
    }
    @Test public void canSteerWhileGoingForward() {
        VisionFollowPlanner.Step left = VisionFollowPlanner.decide(person(.40f,.13f,.90f));
        VisionFollowPlanner.Step right = VisionFollowPlanner.decide(person(.60f,.13f,.90f));
        assertEquals(-1, left.steer); assertEquals(1, left.throttle);
        assertEquals(1, right.steer); assertEquals(1, right.throttle);
    }
    @Test public void nearbyTargetMustStop() {
        VisionFollowPlanner.Step step = VisionFollowPlanner.decide(person(.5f,.24f,.9f));
        assertEquals(0, step.steer); assertEquals(0, step.throttle);
    }
    @Test public void offscreenOrVeryDistantTargetMustStop() {
        for (PersonTracker.Box b : new PersonTracker.Box[]{
                person(.25f,.13f,.9f),person(.75f,.13f,.9f),
                person(.5f,.06f,.9f),person(.5f,.13f,.3f)}) {
            VisionFollowPlanner.Step step = VisionFollowPlanner.decide(b);
            assertEquals(0,step.steer);assertEquals(0,step.throttle);
        }
    }
    @Test public void nullAndLowConfidenceAlwaysStop() {
        assertFalse(VisionFollowPlanner.decide(null).moving());
        assertFalse(VisionFollowPlanner.decide(person(.5f,.16f,.59f)).moving());
    }
}
