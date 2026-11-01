# Crew Car — ADB / mobile-agent handoff

## Scope and limitations

Device: user's Android phone and own `WL Tech FPV CAR` Wi-Fi toy. The original Android app package ID is `com.lg.wltechfpvcar` according to Google Play. Public information confirms Wi-Fi + camera + remote control, **not** the precise URL/port/command bytes. Never insert generic ESP32/other drone commands as if verified for this car.

The repo's core code is in `app/src/main/java/com/crew/car/`. All application diagnostic events use logcat tag `CrewCar`.

## Initial setup

```bash
git clone https://github.com/magic76/crew-car.git
cd crew-car
git switch feat/android-fpv-gemini-live  # or main after merge
gradle :app:testDebugUnitTest :app:assembleDebug
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.crew.car/.MainActivity
adb logcat -c
adb logcat -v threadtime -s CrewCar:D AndroidRuntime:E
```

If Gradle or SDK 35 isn't installed, install the normal Android toolchain first. If build fails, capture **exact** Gradle error and fix before any hardware test. Do not report build success without running it.

## Device discovery (no speculative commands)

```bash
adb shell dumpsys connectivity
adb shell dumpsys wifi
adb shell ip route
adb shell pm path com.lg.wltechfpvcar
adb logcat -d -v time -s CrewCar:D AndroidRuntime:E > crew-car-adb.log
```

Expected on-phone log sequence: `APP`, `NET`, then `CAM HTTP`, `CAM frames` when image works; `CTRL config`, `SAFETY`, `TX` when manual movement works; `LIVE WS opened`, `LIVE setupComplete`, `TOOL drive_car` and `TOOL response` for AI.

Recommended measurement sequence:

1. Turn off the car, make sure wheels are suspended, turn it back on. Connect phone Wi-Fi to car SSID and confirm original app can preview/control it.
2. Determine correct gateway/device IP via connectivity/Wi-Fi. This does **not** prove the control port.
3. With the original application and the owner's permission, capture traffic on the owned Wi-Fi (Wireshark on a monitor-capable PC/AP, or an approved Android VPN packet capture). Separate camera stream vs control packets. Record: protocol (UDP/TCP), IP/port, source/destination, packet hex, trigger for forward/backward/left/right/release/stop, whether any handshake/heartbeat/CRC is required. Do not collect other people's data. Normal `adb logcat` by itself typically cannot reveal the original app's network payload.
4. Determine whether video is HTTP JPEG/MJPEG; if not, add a protocol-specific adapter instead of guessing URL paths.
5. Apply verified mapping, unlock only with wheels lifted, send one <500ms manual pulse. Confirm direction and an actual STOP. Repeat for all directions.
6. Camera should stay alive while phone's mobile data reaches Gemini. If Live reports no internet network, inspect `dumpsys connectivity` for cellular VALIDATED network. If needed, keep mobile data enabled when Wi-Fi reports no internet; don't use `bindProcessToNetwork`.
7. Connect Live, ask for a visual description. Verify `TOOL` only after explicit request to move and image is fresh.

## Debug checklist

| Symptom | Logs / next action |
|---|---|
| Can't connect camera | `NET carWiFi`, `CAM HTTP` or socket exception; verify URL & stream codec |
| App sends but car doesn't move | `TX hex=`; confirm **actual** port/protocol/handshake and Wi-Fi routing, not merely UI status |
| Releasing button does not stop car | Treat as safety-critical; power off car, validate STOP command and any required heartbeat |
| Gemini fails to connect | `LIVE WS failed http=<code>`; check model account access, cellular internet, key (never paste key into issues) |
| Gemini sees old view | `CAM frames` stopped or camera is >2.5s old; AI tools block until fresh |
| Gemini speaks but doesn't call tools | Test text: "請描述鏡頭"; then explicitly ask one safe pulse while armed; inspect `TOOL` |
| Android mic silence | Permission and `MIC capture`; check 16k recording and 24k speaker logs |
| Wi-Fi disappears | Android Wi-Fi setting: connect to device even if "No internet"; let cellular handle Gemini |

## Handoff acceptance gates

- [ ] Android APK builds and installs; cold launch does not crash.
- [ ] Original car app working; model, Wi-Fi SSID, IPs, ports, video format, captured control packet table documented **without secrets**.
- [ ] Camera images appear and timestamps continuously update.
- [ ] Each manual direction works while wheels suspended; release / STOP actually stop motors.
- [ ] Car Wi-Fi and cellular Gemini can operate simultaneously.
- [ ] Gemini mic, speaker, visual description, tool-response roundtrip work.
- [ ] Disarmed/stale-camera/invalid command blocks AI motor action.
- [ ] OnPause, emergency stop, watchdog, Wi-Fi loss and tool-call cancel tested. If STOP cannot reach the car after signal loss, hardware power switch is required.
- [ ] Record actual build logs and hardware testing results before declaring complete.

## Reports

Keep `crew-car-adb.log`, a short chronological test report, exact model/firmware, and non-sensitive packet hex in a new `docs/HARDWARE_VERIFIED.md`. Do not store API Keys, microphone recordings, private camera clips or capture files in Git.

## Newly discovered source protocol (2026-11-01)

Open source reverse-engineering reference: https://github.com/lcgamboa/WL-Tech-FPV-CAR-6405

- **6405 control**: UDP to `172.16.11.1:23458`, every ~100ms, 16-byte payload `CA 47 D5 00 00 00 00 00 66 <steer> <throttle> <trim> 00 00 <steer XOR throttle XOR trim> 99`.
- Neutral values: steer=0x80, throttle=0x80, trim=0x80; reference manual steering delta=45, throttle delta=30. The neutral packet is `CA47D500000000006680808000008099`.
- **6405 camera start**: UDP to port `23459`: `A88A200008000000010002000000D204`. Stop: `A88A210006000000010000000000`.
- **6405 video receive**: UDP port `1234`, fragmented H.265; 32-byte little-endian header includes `total_size`, `frame_id`, `frag_index`, `offset`, and `payload_size`. Experimental native Android MediaCodec receiver has been added. A successful frame is only reported when PixelCopy succeeds.
- **6401 camera**: documented RTSP `rtsp://172.16.11.1/live/ch00_1` (NOT the same camera format). No 6401 RTSP player has been implemented in Crew Car yet.

After an APK build, identify the vehicle model number/firmware **before using this profile**. Keep all movement disarmed until both physical manual STOP and fresh video are confirmed. If the vehicle does not use this firmware, fall back to manual packet capture; do not blindly transmit the discovered commands.

## Gemini Live Tool smoke test (possible without the car)

After building/installing, connect to normal Internet Wi-Fi or cellular, enter Gemini Key, connect Live, tap **測試 AI 查詢工具（不移動）**. Observe Logcat `TOOL name=get_car_status`, `get_camera_status`, `get_network_status` and `toolResponse count=...`. The model may choose which functions to call; verify that it does not invent unavailable data. Without a real car, camera freshness must be false and the safety lock must be on. Do NOT arm the car just to test read-only tools.

Then explicitly ask to move while locked and verify response status **blocked** with no motor TX. Test `stop_car` and `lock_car` without physically moving the car, understanding that STOP TX may fail without a configured car.

Once a compatible real car is available, re-run after verifying actual STOP, the camera, and the dual-network path. See `docs/GEMINI_TOOLS.md`.
