# Crew Car v0.1.3 — driver console and camera QA

## Build (local Android SDK / JDK 17 toolchain)

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.crew.car/.MainActivity
adb logcat -v time -s CrewCar:D AndroidRuntime:E
```

No claim is made here that an APK was built or the physical car was validated.

## P0 driving behavior

1. Launch to **駕駛模式**. Camera pane stays at top; red STOP and a single eight-direction pad are fixed at the bottom. Settings/Log are hidden until **設定／診斷** is tapped.
2. While locked, drag the pad: should log a blocked command and remain stationary.
3. Put wheels in the air, confirm OEM STOP, then arm using the fixed checkbox. Drag pad to each edge and each diagonal; verify the packet bytes via TX Log (not physical ACK).
4. Hold an axis for 3 seconds. Expect one motor state every ~50ms via a persistent socket; no watchdog STOP until release. Releasing anywhere, gesture cancellation, activity backgrounding or Wi-Fi loss must neutralize/lock as applicable.
5. Tap the fixed red STOP while pressing the pad. The system must issue a STOP and **disarm**; additional touches must be blocked until deliberate re-arm.
6. Switch to **設定／診斷** and back while the camera is running. The camera SurfaceView must not be removed/recreated by tab switching. STOP remains visible.
7. Bring Android settings to the foreground: onPause must disarm and stop camera; returning from Wi-Fi settings auto-resumes camera if desired.
8. Confirm UDP TX keeps the same source port while the Wi-Fi Network and endpoint stay the same. Change Wi-Fi / endpoint and verify old socket is closed and a new socket is bound.

## P0 camera behavior

- Start with the car Wi-Fi disconnected: expect **等待小車 Wi-Fi 連線**. If connected to validated normal Wi-Fi, camera should wait instead of blindly sending to 172.16.11.1.
- Connect the camera Wi-Fi, return to the App. On Surface creation and the foreground 2.5s camera monitor, attempt H.265 camera stream once ready.
- If manually pressing **停止影像**, automatic resume is disabled until **重新連接攝影機**.
- Check diagnostic categories printed approximately every 5s:
  - `UDP 1234 尚無封包`: confirm correct car SSID, camera START port 23459 and receive port 1234.
  - `已收 ... 個 UDP 封包，尚未組成完整影格`: inspect packet/framing values.
  - `已組成 ... 幀，解碼器尚未輸出影像`: investigate HEVC VPS/SPS/PPS, access-unit/keyframe handling and MediaCodec.
  - `解碼輸出 ...` but blank: examine Surface and PixelCopy.
- The P0 changes repair start/restart timing, **not** an unknown codec or proprietary framing mismatch.
- Keep camera and Gemini Live isolated to their required Wi-Fi / validated cellular network path. No sensor other than camera is assumed.

## Known limitations

- Actual camera protocol and frame quality still need physical-car capture.
- Manual arm can be used before video is working; **Gemini AI motion still requires fresh camera frames and a recent Live video queue entry**.
- Touch control uses binary ±1 steering and throttle rather than calibrated analog speed; tuning hardware range comes after verified frames and motor tests.
- STOP packets lack a hardware acknowledgment; radio loss can prevent delivery. Test with physical power shutoff in reach.
