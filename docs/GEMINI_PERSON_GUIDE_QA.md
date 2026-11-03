# Gemini-person guidance and bounded cruise QA (v0.1.28)

## Two distinct user requests

**持續前進** uses `drive_intent(forward_continuous)`: at most 2000ms forward once, with video freshness required throughout the lease. Never automatically renew it. Stale camera, unqueued Live JPEG, lost Live/Wi-Fi, lock, background, manual or STOP must halt it. Fresh imagery is **not** obstacle detection: only test in a cleared area with direct supervision.

**朝著前面的人走** uses `guide_toward_person({bearing:"left|center|right|unknown"})`: Gemini attempts a coarse LEFT/CENTER/RIGHT inference from recent FPV (the model may still be wrong). Each request permits **one 500ms nudge** only. LEFT/RIGHT means forward+steer simultaneously, both axes STOP at expiry. CENTER means forward 500ms and STOP. `unknown` is blocked, not guessed. No automatic next tool calls. Human does not need to first tap a MediaPipe bounding box. The existing local selected-person align mode is separate.

## Validation (wheels elevated first)

1. With motor locked, both movement tools are blocked.
2. With no camera or no Live JPEG queued within 1500ms, guided person and continuous intent are blocked; ordinary `forward_small` retains its prior manual-arming policy.
3. With a recent video stream in a clear test area, `forward_continuous` sends at most 2000ms of center/forward packets, then STOP. Interrupt the camera mid-action: watchdog must STOP and disarm (re-arm manually).
4. Ask Gemini to report current person bearing from one frame. Inspect `[TOOL] name=guide_toward_person personBearing=...`. If the model cannot see one identifiable target it should choose `unknown`/ask for clarification, with no movement.
5. For left/right guided nudge, inspect the motor stream: 500ms max diagonal, **no straight throttle tail**.
6. With a camera stream intentionally stale, no guided movement should occur even if Gemini claims it can see a person.
7. Send STOP or lock in same tool batch as guide: STOP/lock always takes priority. Manual thumb and backgrounding also STOP.
8. Repeat guided movement without a new human command: the model must **not** self-chain actions; inspect `[TOOL]` logs. Each accepted nudge is separately bounded and requires a fresh external spoken request.
9. Compare visually whether the bearing chosen by Gemini matches the displayed camera (make no identity claims), and do not assert its interpretation as correct solely from queued JPEG metadata.

## Debug

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
adb logcat -d -s CrewCar:D | grep -E '\\[TOOL\\]|\\[DRIVE_INTENT\\]|\\[AI\\]|\\[SAFETY\\]'
```

## Limits

No range sensor, depth, collision sensing or odometry. A live camera cannot establish stopping distance; 2s forward motion can hit an obstacle. Do not operate toward walls, pets, people or stairs. The result is a **supervised one-step bearing experiment**, not autonomous target following.
