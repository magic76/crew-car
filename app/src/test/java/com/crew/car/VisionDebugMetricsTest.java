package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisionDebugMetricsTest {
    @Test public void validSamplesTrackActualCompletedRate() {
        VisionDebugMetrics s = new VisionDebugMetrics();
        s.observe(100,160,40,1,null);
        assertEquals(60,s.processingMs());
        assertEquals(40,s.inferenceMs());
        s.observe(300,390,70,2,
                new PersonTracker.Box(.4f,.2f,.6f,.6f,.9f));
        assertEquals(5f,s.fps(),.001f);
        assertEquals(90,s.processingMs());
        assertEquals(.9f,s.confidence(),.001f);
        assertEquals(2,s.people());
        assertEquals(110,s.resultAgeMs(500));
    }
    @Test public void outOfOrderResultCannotReverseMonotonicTime() {
        VisionDebugMetrics s = new VisionDebugMetrics();
        s.observe(200,250,10,1,null);
        s.observe(100,150,20,8,null);
        assertEquals(1,s.people());
        assertEquals(50,s.processingMs());
        s.reset();
        assertEquals(-1,s.resultAgeMs(500));
    }
    @Test public void neverClaimsActualImageExposureLatency() {
        VisionDebugMetrics s = new VisionDebugMetrics();
        s.observe(100,200,40,0,null);
        assertEquals(100,s.processingMs()); // local capture->callback only
        assertEquals(40,s.inferenceMs());
    }
}
