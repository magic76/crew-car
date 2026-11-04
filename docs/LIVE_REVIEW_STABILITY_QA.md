# Crew Car v0.1.33 — Live review stability diagnostic checklist

## What changed
- **Cloud FPV reviews are single-flight.** Android schedules one review ~700ms after a new 5-second drive window, then no faster than every 2.5s. Only one review may be outstanding. The pending review releases when Gemini sends the matching `adjust_ai_drive_session` tool call, sends a STOP, finishes its turn, or disconnects. Hung reviews expire after 4.5s; the motor's 5s STOP deadline is independent.
- **Compact review text**, instead of repeatedly inserting a large instruction block. Existing live FPV streaming remains up to one 448px, quality-60 JPEG per second, as before. This change does **not** change motor UDP packet size/cadence, JPEG parameters or gain.
- **Stop reasons are separated:** `AI drive session max duration` is a normal five-second stop; `AI FPV video queue stale` is camera/queued-image freshness failure, not proof of Wi-Fi loss; `Gemini Live disconnected` indicates a cloud WebSocket failure; `Car Wi-Fi disconnected` indicates loss of an Android Wi-Fi network. Manual takeover, user STOP and TX errors also remain distinct.
- FPV-stale or Live control-gate failure **stops motor immediately**, but does not unnecessarily revoke manual arming while the car Wi-Fi remains available. Loss of car Wi-Fi **disarms**.
- Driving HUD and `get_car_status` display the last stop category. New status telemetry includes `ai_review_in_flight`, `ai_review_pending_age_ms`, `ai_review_queued`, `ai_review_overlap_skipped`, `ai_review_timed_out`.

## QA
1. On a private, unobstructed surface, issue one 5s AI command and inspect `[AI_REVIEW]` logs: at most one pending request until tool/turn completion, never a queue of overlapping prompts. Confirm Android stops at 5s even if Gemini never responds.
2. Let AI run to the five-second limit. Driving HUD must say **正常：5 秒行駛時間到**, not disconnected.
3. Temporarily pause FPV while keeping car Wi-Fi and Gemini up. AI must stop and display **FPV 畫面逾時**, while manual pad can still be used after new human touch.
4. Disconnect Gemini WebSocket while car Wi-Fi remains present. Motor must STOP, HUD should show **Gemini Live 或 App 駕駛狀態中斷**, and manual control should not be incorrectly labeled a car Wi-Fi failure.
5. Disconnect car Wi-Fi. Motor STOP and disarm; HUD says **車子 Wi-Fi 連線已失去**. Reconnect and explicitly touch manual controls to arm.
6. Trigger E-STOP or touch manual during AI, verify immediate preemption; last-stop category must distinguish.
7. Verify `[VIDEO]` JPEG bytes, `[HEVC]` received/decoded counts and `[LIVE]` WS failed/closed codes. The cause of an actual disconnection still requires hardware/field logs.

This is a request-pacing and classification fix, **not a promise that cellular/Gemini WebSocket service or FPV Wi-Fi cannot disconnect**. No hardware measurements, odometry, collision detection or device ACK are introduced.
