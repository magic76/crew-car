# Crew Car v0.1.29 — AI Drive Session acceptance checklist

## User model

A single explicit human utterance, e.g. 「開始 AI 駕駛／持續前進，幫我調方向」, calls `start_ai_drive_session`. The Android local motor ticker then continuously transmits low-gain forward throttle. Gemini may change steering and packet throttle gain **in place**, without sending STOP between updates. It is **not** unbounded, not obstacle avoidance, and not a measured speed controller.

`adjust_ai_drive_session(session_id, steering, speed)` uses the exact ID from the start response, heading left/right/center/keep and gain slow/normal/fast/keep (50/65/80%, capped by user manual tuning). Steering left/right lasts at most 900ms, recenters, **throttle remains forward**. No update extends the 30s hard session deadline.

Android periodically queues text prompting Gemini to analyze the latest FPV and issue a steering/speed adjustment or STOP if uncertain (about every 3s, depending on Gemini audio activity). A queued frame is not proof that Gemini understood the image. If it fails to update for 8.5s, STOP; no automatic restart.

## Wheels-off-ground checks first

1. Arm on WL Tech only; no AI session starts without manual unlock, fresh camera and Gemini Live. Unknown/unverified KL is rejected. Verify `start_ai_drive_session` returns a nonzero `session_id`.
2. Inspect TX: after start, the serial writer sends periodic `ai-session:0:1:50` packets at the configured WL cadence, not one packet per cloud tool call. Initial packet gain is 50% (subject to user manual tuning ceiling).
3. While continuing to transmit, update same ID with `{"steering":"left","speed":"normal"}`. The motor packet contains left steering + forward throttle at ≤65% throttle gain. After about 900ms steering recenters **without a neutral throttle gap**; the session's absolute deadline stays unchanged.
4. Update speed `slow`, `normal`, `fast` with `steering=keep`. TX throttle bytes must change, and never exceed configured tuning or the 80% session cap. These are gains, not a km/h measurement.
5. Update just heading, `speed=keep`, confirm speed is retained. `center` immediately recenters, retains throttle. `keep/keep` can refresh the last review timestamp without extending the absolute deadline.
6. Send a wrong/old `session_id`: must be blocked with **zero motor state changes**. A queued adjustment after STOP/lock cannot revive motion.
7. Delay/disable Live video or camera stream: video-freshness watchdog must STOP and require re-arm if necessary. Simulate absent AI control updates for >8.5s, confirm STOP. At 30s session deadline, confirm STOP regardless of ongoing updates.
8. Manual joystick, emergency STOP, app background, Live disconnect, Wi-Fi loss or transport send failure must immediately cancel the session. STOP and lock override same-batch start/adjust tool calls.
9. Confirm log entries: `[AI_SESSION]` start, review requests, update with ID/steering/gain, and `[SAFETY] STOP`. No API key or raw camera pixels logged. All TX is application-side only, no hardware acknowledgement.
10. Check older `drive_intent`, `guide_toward_person` and manually selected person alignment remain separate. They cannot claim ownership or extend an active session.

## Known limits

This is a Wi-Fi toy car with no depth/ToF, collision sensing, odometry, or physical STOP acknowledgement. No camera freshness check guarantees clearance. Do not let it move in a direction with people, animals, obstacles or stairs. Initial tests MUST have wheels raised. Ground tests require a cleared private area with direct supervision and physical cutoff. Gemini updates are best-effort high-latency image judgments, not deterministic real-time obstacle avoidance.

## Build

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
adb logcat -d -s CrewCar:D | grep -E '\[AI_SESSION\]|\[TOOL\]|\[SAFETY\]|\[TX\]'
```
