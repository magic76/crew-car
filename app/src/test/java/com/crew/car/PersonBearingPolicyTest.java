package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PersonBearingPolicyTest {
    @Test public void coarseBearingsProduceOnlyShortForwardMoves() {
        for (String direction : new String[]{"left","center","right"}) {
            String intent = PersonBearingPolicy.intentFor(direction);
            assertNotNull(intent);
            DriveIntentPolicy.Plan plan = DriveIntentPolicy.plan(intent);
            assertNotNull(plan);
            assertEquals(500, plan.leaseMs);
            assertEquals("forward", plan.throttle);
            assertEquals("left".equals(direction) ? "left" :
                    "right".equals(direction) ? "right" : "center", plan.steering);
            assertEquals(!"center".equals(direction), plan.stopWithSteering);
        }
    }

    @Test public void ambiguityOrInvalidDirectionsCannotMove() {
        for (String value : new String[]{null,"","unknown","uncertain","back","forward",
                "follow","left_right","RIGHT","far","spin"}) {
            assertNull("rejected: " + value, PersonBearingPolicy.intentFor(value));
        }
    }
}
