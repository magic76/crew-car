# Crew Car v0.1.27 — supervised vision alignment QA

## Exact scope

- Select one person **manually** on the FPV image; detector never selects or switches people itself. A **fresh human box-tap is required for every new session**, including retries after completion; the model cannot repeatedly restart its own alignment.
- Tap `對準人` or ask Gemini `看著那個人` (`align_selected_person`) after manual motor unlock.
- Controller may emit at most **3** low-gain **160ms forward+steering** pulses in a **6.5-second** explicit session. It sends **no forward-only, reverse, search/spin or auto-follow** commands.
- Both axes go neutral at each pulse expiry. A further correction requires a **new** captured image after **240ms** of stopped settling, and a smaller target-center error. Manual override or STOP cancels.
- The car has **no depth sensor, collision guarantee, motor ACK, odometry or safe stopping-distance measurement**. A steering car requires some forward motion to change its heading, so this experiment can creep toward the person.
- Legacy continuous visual-follow code remains non-public in the UI, separate from this alignment feature.

## Preconditions

Use a private open, obstacle-free test area. Start with wheels **off the ground** to verify motor packet timing. Keep physical power cutoff reachable. Never test facing a wall, stairs, person or pet. Connect WL Tech FPV camera, enable `辨識`, wait for local person boxes to update, tap the intended person's box. No auto-target selection. Unlock manually; the model cannot unlock.

## Verification sequence

1. With car locked, asking Gemini to align returns `blocked`; no pulse is sent. With no selected person, likewise `blocked`.
2. Select one box and start `對準人`. If target is already within ±0.065 normalized error of image center, session reports aligned without movement.
3. If target is left or right but inside ±0.22 normalized error, one pulse is queued to the corresponding direction; its 50% OEM-gain WL packet contains BOTH steering and forward throttle.
4. Observe motor sending for roughly 160ms (I/O scheduling adds latency), then neutral STOP. Inspect log reason `vision align pulse lease expired`.
5. Check that no second pulse is emitted from frames captured before the first STOP + 240ms. If the next frame shows less error, another pulse may be issued, up to three total.
6. If center error worsens >0.02, target switches/lost, person box is too large/too small, camera is stale, app backgrounds, car is disarmed, manual thumb moves, or Gemini says STOP, controller must cancel and send STOP immediately.
7. Remove one camera frame stream while waiting: no new pulses. If a queued motor job has not started when cancellation arrives, it must be invalidated by the motor epoch.
8. Validate there are no 15s or repeated Gemini low-level move calls. Gemini tool response only reports local session start; it does **not** verify actual movement, person identity or proximity.

Debug:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
adb logcat -d -s CrewCar:D | grep -E '\\[ALIGN\\]|\\[SAFETY\\]|\\[TOOL\\]|\\[VISION\\]'
```

## Known limits and next work

- Low power motor pulses may be too weak/short for some surfaces; tune only after physical motor validation, not by silently extending time.
- A person-center pixel error is NOT a metric yaw error or distance. On a steering chassis, center error could change as the person moves independently.
- A camera-only view cannot establish clearance from a wall; **do not enable autonomous approach**. Reliable depth/range sensing and measured braking are prerequisites for proximity control.
