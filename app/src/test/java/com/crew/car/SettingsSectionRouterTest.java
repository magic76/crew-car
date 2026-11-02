package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SettingsSectionRouterTest {
    @Test public void routesAllOriginalFieldsToExpectedCategories() {
        assertEquals(SettingsSectionRouter.VEHICLE, SettingsSectionRouter.sectionForChunk(0));
        assertEquals(SettingsSectionRouter.AI_CAMERA, SettingsSectionRouter.sectionForChunk(1));
        assertEquals(SettingsSectionRouter.VEHICLE, SettingsSectionRouter.sectionForChunk(2));
        assertEquals(SettingsSectionRouter.CONTROLS, SettingsSectionRouter.sectionForChunk(3));
        assertEquals(SettingsSectionRouter.ADVANCED, SettingsSectionRouter.sectionForChunk(4));
        assertEquals(SettingsSectionRouter.CONTROLS, SettingsSectionRouter.sectionForChunk(5));
        assertEquals(SettingsSectionRouter.AI_CAMERA, SettingsSectionRouter.sectionForChunk(6));
        assertEquals(SettingsSectionRouter.ADVANCED, SettingsSectionRouter.sectionForChunk(7));
    }

    @Test public void rejectsUnknownChunks() {
        for (int index : new int[]{-1, 8, 9}) {
            try {
                SettingsSectionRouter.sectionForChunk(index);
                fail("unexpected chunk " + index);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void validatesPanelBoundariesAndAcceptsEmptyGroups() {
        assertTrue(SettingsSectionRouter.validCuts(
                new int[]{2, 9, 12, 18, 26, 27, 35, 44}, 44));
        assertTrue(SettingsSectionRouter.validCuts(
                new int[]{0, 0, 0, 0, 0, 0, 0, 0}, 0));
        assertFalse(SettingsSectionRouter.validCuts(
                new int[]{2, 9, 8, 18, 26, 27, 35, 44}, 44));
        assertFalse(SettingsSectionRouter.validCuts(
                new int[]{2, 9, 12, 18, 26, 27, 35, 45}, 44));
        assertFalse(SettingsSectionRouter.validCuts(null, 0));
    }
}
