package com.crew.car;

/** Human-readable reason categories; never calls a stopped motor a lost network. */
public final class CarStopStatus {
    private CarStopStatus() {}

    public static String label(String reason) {
        if (reason == null || reason.isEmpty() || "not_requested".equals(reason))
            return "尚無行駛紀錄";
        if (reason.contains("AI drive session max duration") ||
                reason.contains("AI lease expired") ||
                reason.contains("motor deadline reached before transmit"))
            return "正常：30 秒行駛時間到";
        if (reason.contains("AI drive steering/speed updates stale"))
            return "AI 控制更新逾時（8 秒未收到修正）";
        if (reason.contains("FPV") || reason.contains("video gate") ||
                reason.contains("camera/target gate"))
            return "FPV 畫面逾時（不代表 Wi-Fi 斷線）";
        if (reason.contains("Car Wi-Fi") || reason.contains("Wi-Fi lost") ||
                reason.contains("car Wi-Fi"))
            return "車子 Wi-Fi 連線已失去";
        if (reason.contains("Gemini Live") || reason.contains("Live/foreground"))
            return "Gemini Live 或 App 駕駛狀態中斷";
        if (reason.contains("manual takeover"))
            return "手動搖桿接管";
        if (reason.contains("TX failure"))
            return "控制封包傳送失敗";
        if (reason.contains("stop") || reason.contains("STOP") ||
                reason.contains("E-STOP"))
            return "已要求停止馬達";
        return "已停止（詳情見 Debug 日誌）";
    }
}
