# Crew Car v0.1.15 — AI conversation shortcut, half-duplex playback, and bounded throttle hold

## What changed

The landscape driving header now has **AI 說話** and **打斷 AI** controls without opening Settings. The AI shortcut reconnects Gemini Live with the encrypted local key (if not already connected) and starts the phone microphone after setupComplete and Android microphone permission. When Live is connected, the same shortcut toggles microphone on/off.

**Half duplex / no spontaneous barge-in:** when Gemini starts outputting 24 kHz PCM audio, Crew Car pauses the physical AudioRecord microphone on the mic capture thread and sends `realtimeInput.audioStreamEnd` exactly once for that paused stream. Incoming output continues playing through AudioTrack. Once `serverContent.turnComplete` arrives and the estimated queued playback has drained, microphone capture resumes automatically, provided the user still has it enabled. The assistant's ongoing reply is **not** interrupted by ambient speech.

Only the explicit **打斷 AI** button clears the AudioTrack buffer, sends a user client-content interrupt instruction, releases the microphone mute and ends any active AI driving lease with STOP. Gemini Live's server-side output could still begin another turn after the interrupt message; confirm its behavior with real audio. No claim of perfect echo cancellation.

## Sustained AI command (not physical speed control)

Previously the model only had `drive_car` pulses lasting 100–650ms. The new `set_drive_state` tool takes:

```json
{"steering":"center","throttle":"forward","lease_ms":3000}
```

It schedules one **bounded 3-second state**, transmitted by the **existing** periodic car motor writer (10–100ms per current user tuning). The model does **not** need to send a second tool call to simulate the pedal being continuously held.

For example, turn left while still accelerating:

```json
{"steering":"left","throttle":"keep","lease_ms":2000}
```

`keep` is supported only while the preceding AI hold lease is still active. Other values: steering `left/right/center/keep`, throttle `forward/reverse/neutral/keep`. The lease **must** be 500–5000ms; it is never infinite. STOP, explicit lock, expiry, stale camera, stale Gemini Live video uplink, Wi-Fi loss, disconnect, changing settings, manual priority, or app pause all cancel the active hold. Losing the camera/Live/network safety gate additionally disarms and requires human to re-arm.

`get_car_status` now contains `ai_hold_active`, `ai_steering`, `ai_throttle` and `ai_lease_remaining_ms`. The driving header shows the AI pedal/steering state and remaining milliseconds (local state only). The device has no speed sensor, odometer, obstacle radar or vehicle acknowledgment. **「定速」 here is fixed control amplitude, not measured cruise control speed.**

AI cannot unlock the car; the physical human must arm it. The camera is the only sensor. Keep it in sight at all times; never use the longer lease for unobserved road driving.

## Android and Gemini acceptance

Build on a machine with Android SDK and JDK 17:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.crew.car/.MainActivity
adb logcat -v time -s CrewCar:D AndroidRuntime:E
```

1. With wheels off the ground and no API Key: tap AI shortcut. It must guide the user to enter a Key, not unlock the motor.
2. With the Key already stored, tap AI shortcut and verify Gemini connects via mobile data while car Wi-Fi remains active; permission to record audio is requested if needed.
3. Begin a conversation. Verify audio uploads while speaking, then on the **first Gemini audio output** the microphone is paused and logged. The assistant must finish without ambient speech interrupting it, and the microphone should resume only after `turnComplete` and local audio drain.
4. During a spoken response, tap **打斷 AI**. Confirm immediate local audio flush, new microphone recording, no model continuing its previous answer, and any AI movement sends STOP. Test the case where Gemini starts another turn despite the interrupt prompt.
5. Verify that simply tapping the AI shortcut to stop the mic sends an audioStreamEnd and playback still works.
6. With explicit human arm, camera streaming into Live and wheels raised, say: **「往前開三秒」**. Expect `set_drive_state`, constant forward motor packets via the single control ticker, UI countdown, and STOP at 3 seconds.
7. While AI holds forward throttle, say **「保持油門，往左轉兩秒」**. Expected `steering=left,throttle=keep,lease_ms=2000` with continuous throttle and bounded renewal.
8. Say **「回正」** during an active lease; confirm steering centers without unintentionally stopping held forward throttle.
9. Verify `stop_car`, `lock_car`, red manual STOP and UI interrupt stop the lease immediately, and no stale queued command starts afterward.
10. Pull camera Wi-Fi, pause Live, stop camera frames, leave the app or manually take over: each must stop AI holding. Once the camera/Live safety gate is stale, require manual re-arm.
11. Verify invalid `lease_ms=999999`, missing direction, `keep` without prior lease and unsupported custom profiles are rejected.
12. On regular validated Internet Wi-Fi without the car, the model may talk and tools can report network state, but AI movement must remain blocked.

## Key telemetry

`[AI] drive lease accepted`, `[SAFETY] STOP reason=AI lease expired`, `[SAFETY] STOP reason=AI camera/Live/network gate stale`, `[AUDIO] assistant speaking`, `[AUDIO] assistant finished`, and `[AUDIO] user tapped interrupt`.

This version's code changes are source-reviewed, but **no physical vehicle, Android SDK build or real Live audio verification was performed in ChatGPT's environment**.

## v0.1.16: hold behavior regression and continuous AI cruise

**Manual (v0.1.17 restored v0.1.14):** With wheels raised, press and hold ▲ forward while staying inside the button. The 180ms manual refresh + 500ms deadman preserves continuous TX at the selected UDP interval. Release the original finger **or move outside that button** -> neutral STOP; re-press to move again. Repeat ▼ reverse. Hold left steering with a second finger while holding accelerator; releasing steering must not release the right accelerator. ACTION_CANCEL, app pause, STOP, lock and Wi-Fi loss must stop motion. The v0.1.16 thumb-drift latch and 1.6s deadman were **removed**.

**AI:** Start manual-armed Live with fresh video, say "持續向前開十五秒" -> `set_drive_state(center,forward,15000)`. At ~5s it must still have TX traffic; do not stop at the old 5s limit. Say "稍微左轉" while moving -> `steer_car(left,550)`, followed by automatic steering neutral `axes:0:1`, **without neutral throttle during turn**. A corresponding right-turn call behaves the same. A legacy `drive_car(left,...)` while cruise is active should be blocked with a helpful message, *not brake*. After the original 15s cruise time (unless explicitly renewed by a new `set_drive_state`), neutral STOP must fire automatically. Test no camera frame, no Live queue, Wi-Fi disconnect, phone background, manual takeover, lock, and the red STOP for immediate termination. Verify no unlimited movement beyond 30s from a single tool call.

The app performs camera freshness and local UDP sending, but cannot know speed, distances or obstacles, and there is no motor ACK. All continuous-driving tests require direct physical supervision and immediate access to the car power switch.
