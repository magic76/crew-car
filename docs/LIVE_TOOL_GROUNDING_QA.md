# Crew Car v0.1.36 — Gemini Live says "I am only an LLM" / tool invocation debugging

## Observed symptom
The user reports Gemini Live WebSocket connects but the model verbally says it cannot drive because it is only a language model. In v0.1.35, the app actually **declared** drive tools in Live setup. A setupComplete ACK does **not** prove the model issued a tool call. Nor does it prove the offline toy-car Wi-Fi and FPV camera are ready.

Google documents that Live can execute application tools with Function Calling and that audio input and output can reduce tool-calling reliability. The model still decides whether to call a tool; prompting cannot guarantee all voice commands are obeyed. This update adds observable tool execution instead of treating a generic voice reply as movement.

## Changes
- Shorter, action-first **GeminiDrivePrompt** explicitly explains it is the Android Crew Car controller: it can invoke `start_ai_drive_session`, `drive_intent`, `adjust_ai_drive_session`, `stop_car` and read-only status tools, even though it cannot directly manipulate the physical radio. If asked whether it can control the car, it should inspect statuses via tools; if the destination is ambiguous, ask for direction rather than inventing an LLM-only restriction.
- **BLOCKING** function behavior for start/stop, short-drive, person alignment and read-only status tools ensures the model waits for the immediate Android tool result before speaking about the outcome. Fast periodic FPV steering adjustments retain **NON_BLOCKING** behavior to keep the voice session responsive.
- Actual model-to-Android tool calls are counted and logged as `[TOOL] Gemini invoked function...`; blocked results are counted separately. A Live handshake alone is not proof of model tool calling or physical car movement.
- Replace the old **測試 AI 查詢工具（不移動）** control with **檢查 AI 駕駛工具（不移動）**. It asks the model to call `get_car_status` and `get_network_status`, then after 8 seconds reports success only if the Android app **actually received** at least one of these read-only tool calls. It never sends motor movement.
- The Live settings panel shows declarations sent, received calls, blocked results and last tool name. `get_car_status` adds model tool counters and readiness metadata. Safety restrictions are unchanged.

## On-device diagnosis
1. Install v0.1.36, turn on the car, connect the **exact pinned car SSID** (not just Gemini 4G/5G), verify FPV is current. Connect Gemini Live on mobile data.
2. Open **設定 → AI 與攝影機 → 檢查 AI 駕駛工具（不移動）**.
3. Wait for 8-second diagnostic result. The **model must send `get_car_status` or `get_network_status`** to count as tool success. If the model replies conversationally without calling a tool, the status says no tool call. Check `adb logcat -s CrewCar:D`, scopes `LIVE`, `TOOL`, `TOOL_CHECK`, `NET`, `CAR_WIFI`, `VIDEO`.
4. Ask "你能操控這台 Crew Car 嗎？" — a healthy model should inspect status rather than claim it is fundamentally unable to call any tools.
5. **Only on a safe, unobstructed test surface, with physical E-STOP available**, say "前進一點" and check `[TOOL] name=drive_intent status=queued`; no drive tool call means the Android motor was **not** commanded. A queued packet does not confirm device acknowledgment or motion.
6. Then try an explicit human-requested 30s AI drive and observe the same session ID receives repeated `adjust_ai_drive_session` calls. Existing 8s stale-control watchdog, 30s absolute timeout, FPV/Live/Wi-Fi gates and human override still stop motion.
7. If tool calls succeed but return **blocked**, inspect connection, video freshness, car profile, foreground and lock state. A blocked result is **not** evidence that an LLM cannot operate tools.

## Important limitation
This is improved tool grounding and diagnostics, **not proof that Gemini will follow every voice command**. Reliability still depends on the model and the Live API; only physical testing confirms car movement. CI checks unit tests and APK compilation, not end-to-end Gemini responses.
