# Crew Car v0.1.25 — Goal-Direction Nudges (no continuous circling)

## Problem this solves

When the camera is unclear, trying to identify a person continuously and hold a follow loop is unreliable. Gemini previously had `drive_car(left/right)` (in-place steering pulses) and `set_drive_state` (15s default, 30s maximum sustained drive), so it could produce long arcs or spinning instead of approaching a clearly stated direction.

**This release is not autonomous target recognition, target tracking or navigation.** It introduces a short combined forward+steering command that a human can direct verbally (or Gemini can choose only if direction is genuinely visible). No physical distance, odometry, angle or obstacle detection is claimed.

## User flow

1. On **WL Tech** profile connect the car Wi-Fi, connect Gemini Live, unlock the manual car safety lock. Begin with wheels suspended. Car must be stationary and the app foreground.
2. Tap the landscape header **導向**. Read and confirm the safety warning. This *authorizes* up to **three** goal-bearing nudges in a **20-second** window. **No motor command is sent by enabling it.**
3. Say a **clear front bearing**, e.g. 「往左前方前進一點」、「往正前方稍微前進」、「往右前方移動一下」. With poor image, Gemini must not infer direction; ask you to specify it.
4. Gemini may call **`approach_target(bearing, duration_ms)`** *once per explicit movement instruction* with `bearing=front_left | ahead | front_right`, `duration_ms=150..600`. A single WL UDP frame contains throttle=forward and steer=left/center/right simultaneously at **50% OEM packet gains**. That is not 50% measured vehicle speed.
5. The existing 50ms motor sender keeps the combined command until its short deadline. The 25ms watchdog then commands neutral STOP. Minimum 750ms between the *starts* of accepted nudges; only 3 pulses per authorization. The direction mode remains latched once the budget expires, blocking old long-cruise tools until a human renews the direction grant or stops/disarms; the model cannot silently fall back to a 15-second cruise.
6. The header displays **導向 ON** while permission or a short pulse is active. Tap to cancel and stop/lock. An explicit STOP, network loss, backgrounding, manual takeover, profile/settings changes, or a failed safety gate cancels remaining authorization. Expiry cannot reactivate the motor.
7. Existing Vision's continuous person-follow code is preserved but its prominent **跟隨** entry is replaced by the safer **導向** entry while camera stability is being evaluated.

## Controls and safety

- `TargetApproachPolicy`: pure-Java allowed bearings/durations, 3-per-20s limit, >=750ms pulse spacing.
- `MotorAuthorityPolicy`: new nudge is part of GEMINI motor ownership (mutually exclusive with manual, Vision follow and AI cruise).
- `CarControl`: additional approach permission + one-shot pulse state, local epoch fencing, main I/O worker, low-output `approach:<steer>:1` mapping to existing `WltoysProtocol.control(steer, 1, new CarTuning(interval, 50, 50))`. A dedicated `approachSafetyGate` requires foreground, driving view, Gemini ready, WL profile and present Wi-Fi; unlike the visual follow gate it **does not depend on unreliable people boxes**.
- Queued commands over 250ms old are discarded. No automatic reverse, in-place search/turning, infinite hold, extra sensor claims or auto-chaining.
- The legacy continuous `set_drive_state` remains available only when the human explicitly requests cruise; *even then*, any `steering=left/right` automatically returns to center after **650ms**, while throttle remains leased. This prevents steering being left permanently turned.
- Existing human manual hold, 180ms input renewal/500ms deadman, E-STOP priority and limited neutral resend remain.
- Gemini tool replies declare `target_reached=false`, `distance_measured=false`, `obstacle_clearance_verified=false`, `device_acknowledged=false`, `motion_verified=false`.

## Local acceptance

```bash
git pull origin main
gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -v time -s CrewCar:D AndroidRuntime:E
```

Use a **raised-wheel test first**, and an open, empty private test area only after verifying all STOP paths. A physical power cutoff must remain accessible.

1. Verify Android CI green and `TargetApproachPolicyTest`, `CarToolPolicyTest` and `WltoysProtocolTest` pass.
2. Locked / no Wi-Fi / no Gemini / custom profile / app background: button cannot grant assist. Confirm pressing 導向 never moves the motor until a valid tool call.
3. Before pressing 導向, `approach_target` is blocked. After 20s it is blocked. After the third accepted pulse it is blocked until reauthorized.
4. Confirm forward+left, straight forward, forward+right all send simultaneous signed steering and *positive throttle* axes. Never reverse or turn in place. Look for `approach:-1:1`, `approach:0:1`, `approach:1:1` in TX logs.
5. Move expires within duration + 25ms watch period **plus device I/O delay**; repeated neutral sends follow existing STOP mechanism. Device movement/ACK are unverified.
6. Call `set_drive_state(steering=left, throttle=forward, lease_ms=15000)` on a raised car: steering centers after ~650ms instead of making a 15s circle. **The throttle still continues until the specified lease or STOP**.
7. Call old `set_drive_state` or `drive_car` while 導向 grant is active: they must reject rather than start a long hold.
8. During nudge, press STOP, press manual thumb, switch Settings, background app, disconnect Wi-Fi, or interrupt Gemini. All must invalidate pending aim commands and reach the STOP process. Hardware braking remains unverified.
9. Ask Gemini to approach an unclear target **without direction**: model should ask for left/ahead/right and must NOT guess. With an explicit human bearing, it can issue a single short pulse without needing successful person detection.
10. Confirm the old person detection overlay still runs for display only; its continuous follow mode is not launched by the Header now.

## Remaining limitations

The vehicle has one monocular camera, **no reliable range sensing, obstacle avoidance, wheel odometry, physical speed telemetry or hardware ACK**. Sending three bounded pulses does not prove that any object was reached or that the path is clear. Avoid people, pets, public roads, stairs and moving traffic. Do not use unattended.

Tests in GitHub Actions prove build/JUnit only; they cannot prove car reaction, wireless STOP receipt or safe physical distance.
