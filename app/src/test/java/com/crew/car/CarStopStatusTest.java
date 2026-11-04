package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CarStopStatusTest {
    @Test public void distinguishesNormalExpiryFromRealDisconnection() {
        assertEquals("正常：30 秒行駛時間到",
                CarStopStatus.label("AI drive session max duration"));
        assertEquals("AI 控制更新逾時（8 秒未收到修正）",
                CarStopStatus.label("AI drive steering/speed updates stale"));
        assertEquals("FPV 畫面逾時（不代表 Wi-Fi 斷線）",
                CarStopStatus.label("AI FPV video queue stale"));
        assertEquals("車子 Wi-Fi 連線已失去",
                CarStopStatus.label("Car Wi-Fi disconnected"));
        assertEquals("Gemini Live 或 App 駕駛狀態中斷",
                CarStopStatus.label("Gemini Live disconnected"));
        assertEquals("手動搖桿接管", CarStopStatus.label("manual takeover"));
        assertEquals("尚無行駛紀錄", CarStopStatus.label("not_requested"));
    }
}
