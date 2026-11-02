# Crew Car v0.1.18 — Landscape Driving UI Acceptance

## Goal

Change the **driving UI only**. Previous v0.1.17 hand-control motor behavior is a hard compatibility constraint: same `CarControl.java` blob, same 180ms UI lease renewal, same 500ms fail-closed deadline, same UDP payloads and tuning. The Gemini tools, network, vehicle profiles, and camera decoder stay unchanged.

## Driver cockpit

- Compact 38dp header: app/vehicle, AI talk, interrupt AI, camera reconnect, **操控** shortcut, general Settings.
- Status information moved from four full rows to one readable 28dp horizontal strip; detailed text remains available in settings / logs. Wi-Fi indicator colors communicate only Android route presence, not hardware ACK.
- FPV visible width is 60% by default, bounded to 48–64% by side width (18–26% each). Width is measured from the real cockpit container so screen insets and rotation do not distort the layout.
- Left side defaults to **two large steering buttons**. **Virtual joystick** is available as an alternative; its deadzone is independently adjustable, but the same -1/0/1 axis values are sent to the existing motor control callback.
- Right side has near-full-width **hold-to-drive forward/reverse** regions. Larger hit areas do not change the v0.1.17 pointer semantics: move out of the original button = neutral, lift = neutral, Android CANCEL = neutral.
- Visible pressed highlights are gated on the app being armed; optional `HapticFeedbackConstants.VIRTUAL_KEY` is emitted on valid axis transitions while armed. This reflects **app input**, not a confirmed vehicle response.

## Quick UI settings

Tap **操控** on the driver header; this always E-STOPs and disarms before opening. The popup provides:
- Left steering mode: big left/right buttons or old joystick.
- Symmetric side width: 18–26% (default 20%).
- Forward/reverse button center separation: 8–30dp (default 10dp).
- Forward/reverse vertical offset: -24–24dp (default 0dp).
- Joystick dead zone: 15–35% (default 26%).
- Optional haptic feedback (on by default).
- Defaults reset.

The settings persist in Activity SharedPreferences, not vehicle-specific tuning. They don't touch packet interval, amplitudes, live AI control, key storage, or camera protocol. Applying a new layout E-STOPs/disarms and resets all pointer IDs. Opening the regular Settings overlay also stops/disarms, as before.

## Test protocol (wheels raised, hardware power switch nearby)

On an Android SDK + JDK 17 build host:

```sh
git pull origin main
gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -v time -s CrewCar:D AndroidRuntime:E
```

1. Use WL Tech car profile, correct car Wi-Fi and control endpoint. Verify motor is LOCKED before human arming; touching either side never starts driving.
2. In the default **button mode**, tap and hold left then right steering, observe visible highlighting and local steering commands. Lift and ensure return-to-center; touch center between buttons and expect no steering. Use second finger for forward/back.
3. Hold right forward for >=5 sec inside its newly larger visible button while changing steering with left thumb. Confirm repeated UDP TX count and no spurious watchdog STOP. Lift forward to STOP immediately. Repeat reverse.
4. Drag the right finger out of the pressed button -> hold must release, consistent with v0.1.17. Re-entering without a new DOWN must not resume.
5. Change Steering to Joystick through **操控**, re-arm and verify original center/left/right behavior, configured deadzone, and independent throttle.
6. Change width to 18% and 26%, and adjust gap/vertical offset. The **visual buttons and touch hitboxes must stay aligned** (check edges, top, bottom, and zone divider). Camera must resize responsively; no FPS or decoder reset caused by settings when safe.
7. Haptic off/on: verify tactile response only for a nonzero axis change while the car is armed. Verify locked UI cannot present a false active press highlight.
8. Make settings changes while armed; entering **操控** must send STOP/disarm *before* controls can change. Cancel/Apply/Restore defaults must not re-arm.
9. Verify Wi-Fi, FPV, AI and local control state remain accessible on one compact status row; top controls and bottom STOP remain visible at 640x360dp and at both landscape rotations. Portrait is intentionally not supported.
10. Force Android ACTION_CANCEL, background the app, disconnect car Wi-Fi, and press STOP while holding both axes. All must stop and disarm as expected.

**Note:** No Android SDK / emulator / physical car were available to execute these tests in ChatGPT's environment. Unit tests can be built by the local agent. `TX sent` is not hardware ACK.

## Follow-up

The full diagnostics/configuration Settings page has **not** been redesigned in this PR; only the driver screen and a compact quick-control popup have changed. Discuss organizing Settings into clear vehicle, camera, Gemini, control tuning, and diagnostics sections after the driving UI is confirmed.
