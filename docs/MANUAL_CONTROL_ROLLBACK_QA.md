# Crew Car v0.1.17 — manual remote-control rollback

## Why

Physical user reports that the experimental v0.1.16 steering/throttle change broke manual driving. We are **not** changing Gemini tools or autonomous driving in this patch.

## Exact code restoration

The rollback takes these fragments from commit `b56d70f4` (the v0.1.14 manual baseline):
- `DualStickDriveView.java` copied **byte-for-byte**, including the right forward/reverse hold buttons and original multi-pointer handling. Finger drifts outside button boundaries **release** the throttle, as before.
- `MainActivity.java`: original `manualPulse` runnable every **180ms** and original `DualStickDriveView.onAxes` callback. AI status/buttons and unrelated UI remain unchanged.
- `CarControl.java`: original `driveManualAxes` method with **500ms** deadman deadline, preserving the existing serial, periodic WL TX loop.
- Delete obsolete experimental `ThrottleHoldInput` and its test. Bump app to 0.1.17 (build 18).

No `GeminiLive`, `GeminiCarTools`, `CarToolPolicy`, camera/video, KL FPV, protocol, network transport, or tuning files are changed.

## Local acceptance (real car required)

```sh
git pull origin main
gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -v time -s CrewCar:D AndroidRuntime:E
```

1. Verify **WL Tech** vehicle profile is selected; KL FPV intentionally remains unverified and cannot arm. Confirm car Wi-Fi and correct control endpoint first.
2. Raise the wheels before manual testing; keep the hardware power switch reachable. Check that the on-screen arm checkbox is deliberately checked.
3. Hold **▲ 前進** for 5+ seconds **inside the button**. TX packet count should increase (at the selected UDP cadence), the wheels should continue driving, and there should be no spurious watchdog STOP.
4. Release forward: STOP promptly. Repeat for **▼ 倒車**.
5. Keep the right accelerator pressed and use the left steering pad for left/right turns; release the left steering pointer and confirm right throttle remains held.
6. Slightly slide a right-hand finger **outside** the forward/reverse button. This **cancels** the hold as in v0.1.14. Move back in with the old finger: it should remain stopped until a new physical press.
7. Android touch cancellation, backgrounding, Wi-Fi loss, settings opening, manual lock and red emergency STOP must stop movement. Re-arming must require deliberate user action.
8. Do **not** use AI tool commands for the initial manual regression acceptance. We will address AI separately after the manual control baseline is confirmed.

Report build result, car profile selected, whether forward/backward motor TX counters increase, and `[SAFETY] STOP` timestamps. A successful socket send is **not** physical car ACK.
