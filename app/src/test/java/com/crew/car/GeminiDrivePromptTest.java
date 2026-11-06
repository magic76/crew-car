package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class GeminiDrivePromptTest {
    @Test public void livePromptExplainsActualAndroidToolBridge() {
        String p = GeminiDrivePrompt.instruction();
        assertTrue(p.contains("App 已於 Live setup 宣告"));
        assertTrue(p.contains("不要因為自己是語言模型"));
        assertTrue(p.contains("get_car_status"));
        assertTrue(p.contains("get_network_status"));
        assertTrue(p.contains("start_ai_drive_session"));
        assertTrue(p.contains("adjust_ai_drive_session"));
        assertTrue(p.contains("drive_intent"));
        assertTrue(p.contains("stop_car"));
    }

    @Test public void safetyAndContinuedDrivingAreBothExplicit() {
        String p = GeminiDrivePrompt.instruction();
        assertTrue(p.contains("30秒"));
        assertTrue(p.contains("8秒"));
        assertTrue(p.contains("session_id"));
        assertTrue(p.contains("keep/keep/keep"));
        assertTrue(p.contains("手動接管"));
        assertTrue(p.contains("車端ACK"));
        assertTrue(p.contains("禁止到期自動重啟"));
    }

    @Test public void motorActionsWaitForToolResultsButVideoCorrectionsStayFast() {
        assertEquals("BLOCKING", CarToolBehaviorPolicy.forName("start_ai_drive_session"));
        assertEquals("BLOCKING", CarToolBehaviorPolicy.forName("drive_intent"));
        assertEquals("BLOCKING", CarToolBehaviorPolicy.forName("stop_car"));
        assertEquals("BLOCKING", CarToolBehaviorPolicy.forName("get_car_status"));
        assertEquals("NON_BLOCKING", CarToolBehaviorPolicy.forName("adjust_ai_drive_session"));
    }
}
