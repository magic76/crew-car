# Crew Car v0.1.26 — Natural Voice Driving (No 8s/20s Approval Window)

## Goal

When the human says 「往前進」, drive a meaningful bounded segment without asking Gemini to call the motor tool every few hundred milliseconds. Distinguish a small nudge from ordinary movement, and support a simple front-left / front-right move without long sustained circles.

## New user flow

1. WL Tech car Wi-Fi and Gemini Live connected. On the driving cockpit, **unlock the car once** using the existing lock control. No extra 導向 button, no 8-second approval prompt, no 20-second grant window or three-pulse budget.
2. Give one explicit voice command. Gemini receives **one advertised movement tool**, `drive_intent`, with an `intent` enum only. It never supplies arbitrary `duration_ms`.
3. The Android app maps the intent to one **existing local two-axis drive lease** maintained by the configured WL motor sender; steering returns to center in at most 650ms. User does not need to repeat a tool command while the vehicle moves.

| Spoken request | drive_intent | Local lease |
| --- | --- | --- |
| 往前一點 / 稍微前進 | forward_small | 500ms |
| 往前進 / 前進 | forward | 2500ms |
| 持續往前 / 一直往前 | forward_continuous | 15000ms |
| 往左前方 / 往右前方 | front_left / front_right | 2000ms, steering recentered by 650ms |
| 往左前方一點 / 往右前方一點 | front_left_small / front_right_small | 500ms |
| 停！ | stop_car | immediate local STOP request; no vehicle ACK |

The 15s **per-command cruise expiry** is a motor fail-safe, **not an extra UI authorization or 20-second window**. The user must request cruise explicitly. No auto-renewal or endless follow-up tools.

## What was removed

- Removed the extra `導向` button and confirmation sheet from the Header.
- Deleted `TargetApproachPolicy` and its 20-second authorization, 3 short-pulse allowance, 750ms pulse budget, and associated tests.
- Removed the redundant `approach_target` Live tool and motor code, including the mode latch that prevented natural forward movement.
- `drive_car`, `set_drive_state` and `steer_car` handlers are retained internally for compatibility, but are **not advertised to new Gemini Live sessions** to avoid ambiguity. External tools are `drive_intent`, stop, lock and read-only status tools.

## Safety boundary retained

- App physical **unlock once** remains mandatory, and only the human can unlock.
- Manual driving still takes precedence and cancel AI; STOP and lock can interrupt any motion. Settings, app background, Gemini disconnect and car Wi-Fi loss also stop local motor output.
- `CarControl`'s existing **25ms watchdog** and fixed-delay motor transmission execute the whole lease; Gemini does not need to poll or issue successive tool calls.
- The voice-driving gate now uses foreground, driving screen, Gemini Live readiness and Wi-Fi availability, **not camera frame freshness**. Blurry FPV does not prevent a clear *human-provided* direction; it also does not establish that any path is safe.
- AI lateral steering recenter limit remains 650ms to prevent holding one side and circling.
- Neither the remote car nor an obstacle sensor confirms that motion happened. STOP packets still travel over unreliable UDP. Do not use around other people, pets, stairs, traffic or obstacles; test with wheels raised before using in an open supervised private space. Keep physical power cutoff accessible.

## Build and validation

```bash
git pull origin main
gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -d -s CrewCar:D | grep -E '\\[TOOL\\]|\\[AI\\]|\\[DRIVE_INTENT\\]|\\[SAFETY\\]'
```

Test with raised wheels:
1. `drive_intent(forward)` starts a local 2500ms throttle lease; no extra 20s/3-pulse/8s UI authorization required.
2. `forward_small` stops at approximately 500ms; `forward_continuous` at 15000ms (plus I/O latency); no other tools are autonomously chained.
3. `front_left` / `front_right` use combined forward + steering and recenter the steering by 650ms without letting throttle spin in circles.
4. Check STOP, lock, manual thumb takeover, loss of Gemini Live/Wi-Fi, app pause and profile/settings switching all invalidate the active lease. Test with the old FPV camera intentionally covered: manually stated forward must not fail *solely* because of camera freshness.
5. With `drive_intent` already active, a second movement call is rejected rather than resetting its duration.
6. Test tool batch with both `stop_car` and `drive_intent`; STOP must win.
7. Verify WL control packet wire format and original manual 180ms/500ms behavior remain unchanged.
8. Inspect debug logs and tool status. `queued` means only local acceptance, not motion verification or hardware ACK.

Android CI can validate JUnit and Debug APK compilation, **not** the RC car or its safe stopping distance. Avoid treating a successful build as verified real-world movement.
