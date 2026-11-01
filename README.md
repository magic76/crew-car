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

## Open-source WLtoys 6405 protocol candidate (2026-11-01)

Research reference: https://github.com/lcgamboa/WL-Tech-FPV-CAR-6405 — the contributor independently captured the original app and decoded a **16-byte UDP control protocol** addressed to `172.16.11.1:23458`. Camera command UDP port `23459` and video receive UDP port `1234` are separately documented. The 6405 camera emits proprietary fragmented **HEVC/H.265**, not HTTP MJPEG; model 6401 reportedly uses `rtsp://172.16.11.1/live/ch00_1`. Protocol support is **experimental until the owner's physical model and firmware are confirmed**.

The UI now includes an **explicit, non-default** WLtoys 6405/6401 motor profile and a **candidate** 6405 UDP/H.265 video adapter using Android MediaCodec + PixelCopy. Do not infer vehicle compatibility from the app name alone; only arm with the car's wheels suspended and a reachable power switch. The 100ms periodic neutral-control keepalive mirrors the reference implementation.

The camera implementation and Gemini 3.8 Live network/API integration have not been compiled or tested on the owner's device. Follow `docs/ADB_HANDOFF.md` before declaring hardware success.

## Gemini Live car tools

Live declares six real Android-side functions: `drive_car`, `stop_car`, `lock_car`, `get_car_status`, `get_camera_status`, and `get_network_status`.

Status and connection tools report **app-level observations only**, not physical car telemetry. AI motor commands are validated (100–650 ms), blocked without manual arming / fresh image / car Wi-Fi, guarded against duplicate tool IDs and invalidated after E-STOP. A batch containing STOP or lock blocks any motor move in the same batch. Only the human can unlock the car through the UI.

A read-only tool smoke-test button has been added. Without the car, connect Live over cellular or normal validated Wi-Fi, and test status/camera/network tools while locked. See [docs/GEMINI_TOOLS.md](docs/GEMINI_TOOLS.md).

## Hardware clarification: camera-only sensor

The physical car has **only a camera**, besides Wi-Fi control of its motors. No range sensor, collision sensor, GPS, odometry, battery telemetry, or motion acknowledgment is available. `get_car_status` is solely Android local state; `get_camera_status` is solely camera transport health. The actual images are streamed separately through Gemini Live. The app now tracks whether camera JPEGs were queued to the active Live WebSocket and will reject AI motor pulses when no recent frame was queued. Queued does **not** prove Gemini decoded/understood the frame.
