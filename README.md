# Crew Car

Android app prototype for a **WL Tech FPV CAR** Wi-Fi FPV toy car with **Gemini 3.8 Live** audio, camera context, and bounded tool-based control.

**Status: source implementation / hardware-integration handoff. The app has NOT yet been compiled on a machine with Android SDK or tested on the actual car.** This repo originally contained no files. The proprietary WL Tech control commands and camera URL have **not** been verified. Consequently all command fields and the camera URL start blank: the app cannot move the car until an operator supplies verified values and explicitly unlocks control.

## Features

- Phone UI: car Wi-Fi/network diagnostics, HTTP JPEG/MJPEG preview adapter, manual directional buttons with press-and-hold stop, configuration, local Logcat and shareable diagnostic text.
- Wi-Fi socket binding: car camera and control use the Wi-Fi network; Gemini Live uses a separate **validated cellular/other internet** network. It does not bind the entire Android process to the car's (typically offline) Wi-Fi.
- Gemini Live model ID: `gemini-3.8-live`; BYOK API Key entered per session only, never committed, saved to preferences, or logged. Raw WebSocket, 16 kHz PCM microphone, 24 kHz PCM speaker, camera frames compressed to <=1 fps, tool/function calling.
- AI `drive_car(direction, duration_ms)`: explicit human arming required, fresh camera frame required (within 2.5 seconds), max 650 ms pulse; STOP on release, movement timeout, errors, tool-call cancellation, and activity pause.
- Configurable Wi-Fi control adapter: UDP or TCP, payload bytes in HEX or UTF-8; five **verified** forward/backward/left/right/stop packets required before arming.
- **TX != success**: no verified ACK / telemetry from this toy, so logs and Gemini explicitly report a queued/unacknowledged packet rather than successful physical movement.

## Build

Requires Android SDK 35, JDK 17, Gradle 8.9. (No Gradle wrapper JAR is committed.)

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.crew.car/.MainActivity
adb logcat -c
adb logcat -v time -s CrewCar:D AndroidRuntime:E
```

There is a **manual-only** GitHub Actions workflow (`Android APK (manual only)`) for assembling a downloadable debug APK without automatically consuming Actions minutes on every push.

## First real-device trial

1. Put the toy car on a stand **with wheels clear of the ground**.
2. Connect the phone to the car's Wi-Fi in Android settings; turn on mobile data for Gemini. Run **Network diagnostics**. Do not assume that a gateway IP is necessarily the camera/control endpoint.
3. Open the original `WL Tech FPV CAR` app and confirm live camera + original controls work. Identify the actual camera URL/stream format and control packets using authorized observations or a packet capture on your own device/network.
4. Enter actual control IP, port, transport, HEX/TEXT direction/stop packets. Run a stopped-wheel, short manual test **only after confirming STOP byte sequence**.
5. Enter the observed camera URL if it is HTTP JPEG/MJPEG. If the feed is proprietary H.264, RTP, or binary UDP, implement a separate camera adapter first; this HTTP adapter will show a clear error, not silently fake a feed.
6. Enter your own Live API key. Check the model is available to your account. Connect Gemini, enable microphone, and ask it to **describe the frame without moving** first.
7. Only after manual packets, STOP, and camera freshness are verified, unlock the car and test one tiny AI pulse.

**Important:** The FPV camera is low-frequency visual context for Gemini (<=1 fps), **not** collision prevention. The implementation has no autonomous obstacle avoidance, ranging, or guarantee a STOP packet reaches the hardware if Wi-Fi fails. Never run unattended; keep physical access to a power switch.

Handoff details: [docs/ADB_HANDOFF.md](docs/ADB_HANDOFF.md).
