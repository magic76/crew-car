# Crew Car — Natural Voice Driving (post-v0.1.26 safety update)

## Goal

When the human says 「往前進」, drive a meaningful bounded segment without asking Gemini to call the motor tool every few hundred milliseconds. Distinguish a small nudge from ordinary movement, and support a simple front-left / front-right move without long sustained circles.

## New user flow

1. WL Tech car Wi-Fi and Gemini Live connected. On the driving cockpit, **unlock the car once** using the existing lock control. No extra 導向 button, no 8-second approval prompt, no 20-second grant window or three-pulse budget.
2. Give one explicit voice command. Gemini receives **one advertised movement tool**, `drive_intent`, with an `intent` enum only. It never supplies arbitrary `duration_ms`.
3. Android maps the intent to a short local two-axis lease maintained by the WL motor sender. A diagonal stops **both steering and throttle together**; it never re-centers and continues straight. The user does not need to repeat a tool command.

| Spoken request | drive_intent | Local lease |
| --- | --- | --- |
| 往前一點 / 稍微前進 | forward_small | 500ms |
| 往前進 / 前進 | forward | 900ms |
| 倒車 / 往後退 | backward | 900ms |
| 後退一點 | backward_small | 500ms |
| 持續往前 / 一直往前 | forward_continuous | **REJECTED** (no verified obstacle feedback) |
| 往左前方 / 往右前方 | front_left / front_right | 600ms, steering AND throttle stop together |
| 往左前方一點 / 往右前方一點 | front_left_small / front_right_small | 500ms |
| 停！ | stop_car | immediate local STOP request; no vehicle ACK |

The former 15-second blind voice cruise is **disabled**, regardless of wording. The model must not silently replace it with repeated short movements. No auto-renewal, no indefinite driving and no extra 8/20-second permission UI. All reverse motions are short and user-explicit.

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
- Voice diagonals stop both axes by 500–600ms; steering re-center at 650ms is applicable only to the legacy internal steering-only path. The watchdog checks STOP before it can re-center, and the transmit loop prevents expired motor packets.
- Neither the remote car nor an obstacle sensor confirms that motion happened. STOP packets still travel over unreliable UDP. Do not use around other people, pets, stairs, traffic or obstacles; test with wheels raised before using in an open supervised private space. Keep physical power cutoff accessible.

## Build and validation

```bash
git pull origin main
gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -d -s CrewCar:D | grep -E '\\[TOOL\\]|\\[AI\\]|\\[DRIVE_INTENT\\]|\\[SAFETY\\]'
```

Test with raised wheels:
1. `drive_intent(forward)` starts a local 900ms throttle lease; no extra 20s/3-pulse/8s UI authorization required.
2. `forward_small` stops at approximately 500ms; `forward_continuous` must be rejected. No other tools are autonomously chained.
3. `front_left` / `front_right` send combined forward + steering for at most 600ms and then **STOP both axes**. The packet stream must contain no `axes:0:1` straight-throttle tail; `front_left_small` and `front_right_small` use 500ms.
4. Check STOP, lock, manual thumb takeover, loss of Gemini Live/Wi-Fi, app pause and profile/settings switching all invalidate the active lease. Test with the old FPV camera intentionally covered: manually stated forward must not fail *solely* because of camera freshness.
5. With `drive_intent` already active, a second movement call is rejected rather than resetting its duration.
6. Test tool batch with both `stop_car` and `drive_intent`; STOP must win.
7. Verify WL control packet wire format and original manual 180ms/500ms behavior remain unchanged.
8. Inspect logs for `TOOL name=drive_intent selectedIntent=...`, `DRIVE_INTENT durationMs=... stopBothAxes=...` and final `SAFETY STOP reason=...`. `queued` means only local acceptance, not motion verification or hardware ACK.

Android CI can validate JUnit and Debug APK compilation, **not** the RC car or its safe stopping distance. Avoid treating a successful build as verified real-world movement.
