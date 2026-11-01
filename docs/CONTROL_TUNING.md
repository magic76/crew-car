# Crew Car v0.1.4 — Control tuning and low-resource vehicle QA

## What is actually tunable

The manufacturer's APK was observed transmitting its 16-byte UDP motor frame roughly every **50ms**. This is an observation, **not** a proven optimum. A higher frame rate can overload a low-powered toy car and a slower one can cause an internal car-side failsafe to time out.

| UI preset | Control TX spacing | Steering amplitude | Throttle amplitude | When to try |
| --- | --- | --- | --- | --- |
| 柔和 | 50ms | 65% OEM delta | 60% OEM delta | First test in a small space; reduce abrupt turns |
| 原廠基準 (default) | 50ms | 100% | 100% | Baseline comparison with the stock app |
| 省封包 | 70ms | 75% | 75% | Test reduced motor traffic when phone or car struggles |
| 靈敏實驗 | 40ms | 100% | 100% | Try only after stable baseline; NOT guaranteed lower latency |

An advanced slider allows **40–100ms in 10ms steps** and **50–100%** of original steering/throttle deltas in 5% steps. The app cannot exceed previously observed OEM maximum command amplitude. Neutral STOP is always 0x80 for steering and throttle, irrespective of gains. Changing or applying any settings must **STOP, disarm, and require deliberate human re-arming**.

The timer uses `scheduleWithFixedDelay`, not a catch-up fixed-rate queue: when the device is stalled, skipped packets are not burst-sent afterwards. UI pad input is refreshed every 180ms solely to extend the 500ms watchdog; repeated identical WL-style commands are no longer separately enqueued. Changes in steering/throttle are queued immediately. The dedicated safety watchdog stays on **25ms checks** and AI pulses remain capped at **650ms**.

For custom (non-WL) profiles, manual refreshes still transmit their original commands rather than relying on WL heartbeat semantics.

## How to compare settings

1. Put wheels off the ground, with physical power cutoff immediately available. Fully charge the car and phone, then keep their separation and radio environment similar throughout the comparison. Test one parameter at a time.
2. Select **原廠基準** (50ms, 100%, 100%). Tap **套用調校（停車上鎖）**, manually re-arm and perform repeated short forward/turn/release motions. Make notes: perceived response, jerkiness, unintended extra motion, STOP reliability and pauses. This is observational, not measured vehicle latency.
3. Try **柔和** next. If forward motion becomes intermittent, increase throttle from 60% in 5% steps; some toy motors have a startup threshold.
4. If original motion stalls or feels erratic, try **省封包** at 70ms (or manual 60/80ms), one change at a time. If STOP seems less reliable, revert to 50ms immediately.
5. Only compare 40ms if baseline is stable; faster periodic sends don't guarantee faster movement.
6. Re-test neutral command, diagonals, manual STOP, E-STOP, Wi-Fi loss, screen off/background and re-arming after each setting. Don't allow unattended driving based on camera images alone.
7. If camera isn't streaming, manual control can be tested carefully; AI driving remains locked until recent camera frames have entered the Gemini Live WebSocket queue.

## What the diagnostics measure

The **本機封包診斷** label shows `設定週期`, `本機傳送`, `失敗`, `上次間隔`, `最長間隔`, `最後送出耗時`. These measure only calls completing on Android / the local UDP socket. **They are not round-trip time, radio latency, physical-car acknowledgement, battery telemetry, camera FPS, or vehicle motion feedback.**

A measured interval larger than the configured period is possible and expected due to OS scheduling and time spent sending packets. The scheduler intentionally does not catch up by bursting old packets. Avoid reading a high maximum gap as proof of a particular firmware failure without comparing actual car motion and radio signal.

Detailed successful motor TX logs are rate-limited (~one every 2.5s or on direction change), rather than rebuilding the on-screen 180-line diagnostic log 20+ times each second.

## Test commands

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.crew.car/.MainActivity
adb logcat -v time -s CrewCar:D AndroidRuntime:E
```

Validation acceptance criteria:
- `WltoysProtocol.control(0,0,CarTuning.preset(i))` always returns the original exact neutral bytes.
- OEM settings produce byte-identical directional packets; custom gains cannot exceed OEM amplitude.
- All preset values and manual slider limits are enforced at the Java data model.
- Changing a setting cannot silently change an armed car. Applying settings always disarms, sends STOP and requires re-arm.
- A 3-second held movement continues transmitting at the selected cadence, without an extra UI-generated stream of identical packets.
- Manual custom-profile repeats remain compatible; AI timing bounds and stale camera blocks remain unchanged.
- Once the hardware camera stream works, compare FPV image smoothness **separately** from motor TX frequency, because HEVC decode/reassembly/render path is an independent bottleneck.

Source of OEM cadence: installed WL Tech FPV CAR APK inspection documented in repository v0.1.2. Public compatible protocol: https://github.com/lcgamboa/WL-Tech-FPV-CAR-6405 .
