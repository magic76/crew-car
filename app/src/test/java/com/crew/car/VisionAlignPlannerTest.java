package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisionAlignPlannerTest {
    private PersonTracker.Box box(float center, float height) {
        return new PersonTracker.Box(center - .07f, .3f, center + .07f, .3f + height, .9f);
    }

    @Test public void firstStepThenMustWaitForPostStopImage() {
        VisionAlignPlanner p = new VisionAlignPlanner();
        p.start(1000, 999);
        VisionAlignPlanner.Decision a = p.observe(box(.38f, .14f), 1030, 1050, true, 0, 0, "");
        assertEquals(VisionAlignPlanner.Command.LEFT, a.command);
        assertEquals(1, p.pulses());
        assertEquals(VisionAlignPlanner.Command.WAIT,
                p.observe(box(.42f, .14f), 1100, 1110, true, 0, 0, "").command);
        assertEquals(VisionAlignPlanner.Command.WAIT,
                p.observe(box(.42f, .14f), 1220, 1230, false, 1070, 0, "").command);
        assertEquals(VisionAlignPlanner.Command.WAIT,
                p.observe(box(.42f, .14f), 1300, 1310, true, 1070, 1250,
                        "vision align pulse lease expired").command);
        VisionAlignPlanner right = new VisionAlignPlanner();
        right.start(1000, 999);
        assertEquals(VisionAlignPlanner.Command.RIGHT,
                right.observe(box(.62f, .14f), 1030, 1050, true, 0, 0, "").command);
        assertEquals(VisionAlignPlanner.Command.LEFT,
                p.observe(box(.42f, .14f), 1510, 1520, true, 1070, 1250,
                        "vision align pulse lease expired").command);
    }

    @Test public void alignedAndUnsafeTargetsNeverDrive() {
        VisionAlignPlanner p = new VisionAlignPlanner();
        p.start(1000, 990);
        assertEquals(VisionAlignPlanner.Command.ALIGNED,
                p.observe(box(.52f,.13f), 1020, 1030, true,0,0,"").command);
        p.start(2000, 1990);
        assertEquals(VisionAlignPlanner.Command.CANCEL,
                p.observe(box(.65f,.25f), 2020,2030,true,0,0,"").command);
        p.start(3000, 2990);
        assertEquals(VisionAlignPlanner.Command.CANCEL,
                p.observe(null, 3020,3030,true,0,0,"").command);
    }

    @Test public void staleFramesTimeoutAndNoImprovementFailClosed() {
        VisionAlignPlanner p = new VisionAlignPlanner();
        p.start(1000, 900);
        assertEquals(VisionAlignPlanner.Command.CANCEL,
                p.observe(box(.4f,.13f), 950,1300,true,0,0,"").command);
        p.start(2000, 1990);
        p.observe(box(.4f,.13f), 2010,2020,true,0,0,"");
        assertEquals(VisionAlignPlanner.Command.CANCEL,p.tick(3300,true).command);
        p.start(4000,3990);
        p.observe(box(.4f,.13f),4020,4030,true,0,0,"");
        assertEquals(VisionAlignPlanner.Command.CANCEL,
                p.observe(box(.3f,.13f),4600,4610,true,4050,4270,
                        "vision align pulse lease expired").command);
    }

    @Test public void stopMismatchAndTargetSwitchFailClosed() {
        VisionAlignPlanner p = new VisionAlignPlanner();
        p.start(1000, 999);
        assertEquals(VisionAlignPlanner.Command.LEFT,
                p.observe(box(.37f, .14f), 1030, 1040, true, 0, 0, "").command);
        assertEquals(VisionAlignPlanner.Command.CANCEL,
                p.observe(box(.42f, .14f), 1520, 1530, true, 1060, 1200,
                        "E-STOP").command);
        p.start(2000, 1999);
        assertEquals(VisionAlignPlanner.Command.CANCEL, p.tick(2050, false).command);
    }

    @Test public void neverChainMoreThanThreePulsesAndRejectOtherMotorOwner() {
        VisionAlignPlanner p = new VisionAlignPlanner();
        p.start(1000,999);
        assertEquals(VisionAlignPlanner.Command.CANCEL,
                p.observe(box(.4f,.14f), 1010,1020,false,0,0,"").command);
        p.start(2000,1999);
        assertEquals(VisionAlignPlanner.Command.LEFT,
                p.observe(box(.35f,.14f),2010,2020,true,0,0,"").command);
        assertEquals(VisionAlignPlanner.Command.LEFT,
                p.observe(box(.37f,.14f),2470,2480,true,2040,2200,
                        "vision align pulse lease expired").command);
        assertEquals(VisionAlignPlanner.Command.LEFT,
                p.observe(box(.39f,.14f),2940,2950,true,2500,2670,
                        "vision align pulse lease expired").command);
        assertEquals(VisionAlignPlanner.Command.CANCEL,
                p.observe(box(.41f,.14f),3410,3420,true,2970,3140,
                        "vision align pulse lease expired").command);
    }
}
