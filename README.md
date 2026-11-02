# Crew Car

Android app prototype for a **WL Tech FPV CAR** Wi-Fi FPV toy car with **Gemini 3.8 Live** audio, camera context, and bounded tool-based control.

**Status: v0.1.2 prototype.** The previous v0.1.1 app was compiled and installed on the phone. A read-only review of the installed OEM APK confirmed its control destination (`172.16.11.1:23458/UDP`), 16-byte frame layout, neutral values, and approximately 50 ms send cadence. Crew Car has not yet received a vehicle acknowledgment or confirmed physical response. The camera stream remains unverified. Manual control stays locked until the operator explicitly unlocks it.

## Features

- Phone UI: car Wi-Fi/network diagnostics, HTTP JPEG/MJPEG preview adapter, manual directional buttons with simultaneous two-axis/diagonal input and STOP on full release, configuration, local Logcat and shareable diagnostic text.
- Wi-Fi socket binding: car camera and control use the Wi-Fi network; Gemini Live uses a separate **validated cellular/other internet** network. It does not bind the entire Android process to the car's (typically offline) Wi-Fi.
- Gemini Live model ID: `gemini-3.8-live`; BYOK API Key entered per session only, never committed, saved to preferences, or logged. Raw WebSocket, 16 kHz PCM microphone, 24 kHz PCM speaker, camera frames compressed to <=1 fps, tool/function calling.
- AI `drive_car(direction, duration_ms)`: explicit human arming required, fresh camera frame required (within 2.5 seconds), max 650 ms pulse; STOP on release, movement timeout, errors, tool-call cancellation, and activity pause.
- Configurable Wi-Fi control adapter: UDP or TCP, payload bytes in HEX or UTF-8. Custom mode requires all five direction/STOP payloads; the built-in WL FPV profile uses the OEM-derived UDP destination and 16-byte frame.
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
3. The installed OEM APK statically identifies control at `172.16.11.1:23458/UDP`. Its neutral frame matches the built-in profile; no vehicle ACK is logged, so this does not prove the car received Crew Car packets.
4. For the built-in profile, keep the OEM-derived IP and UDP settings. For custom mode, enter the observed IP, port, transport, and all five direction/STOP payloads. Test only with the wheels clear and after confirming STOP behavior.
5. Enter the observed camera URL if it is HTTP JPEG/MJPEG. If the feed is proprietary H.264, RTP, or binary UDP, implement a separate camera adapter first; this HTTP adapter will show a clear error, not silently fake a feed.
6. Enter your own Live API key. Check the model is available to your account. Connect Gemini, enable microphone, and ask it to **describe the frame without moving** first.
7. Only after manual packets, STOP, and camera freshness are verified, unlock the car and test one tiny AI pulse.

**Important:** The FPV camera is low-frequency visual context for Gemini (<=1 fps), **not** collision prevention. The implementation has no autonomous obstacle avoidance, ranging, or guarantee a STOP packet reaches the hardware if Wi-Fi fails. Never run unattended; keep physical access to a power switch.

Handoff details: [docs/ADB_HANDOFF.md](docs/ADB_HANDOFF.md).

## Open-source WLtoys 6405 protocol candidate (2026-11-01)

Research reference: https://github.com/lcgamboa/WL-Tech-FPV-CAR-6405. Static inspection of the installed OEM APK independently confirms the 16-byte UDP control destination `172.16.11.1:23458`, the neutral frame values, and a roughly 50 ms control loop. Camera command UDP port `23459` and video receive UDP port `1234` are separate. The 6405 camera emits proprietary fragmented **HEVC/H.265**, not HTTP MJPEG; model 6401 reportedly uses `rtsp://172.16.11.1/live/ch00_1`. The profile matches the OEM control frame statically; physical vehicle receipt and response remain unverified.

The UI preselects the OEM-derived motor profile, but still requires an explicit manual unlock. It also includes a **candidate** 6405 UDP/H.265 video adapter using Android MediaCodec + PixelCopy; the camera status line reports UDP packet, assembled-frame, decoder-output, and dropped-frame counts to help diagnose a blank preview. Only arm with the car's wheels suspended and a reachable power switch. The 50ms neutral-control keepalive matches the OEM app log.

The camera stream and Gemini 3.8 Live network/API integration have not been verified on the owner's device. Follow `docs/ADB_HANDOFF.md` before declaring hardware success.

## Gemini Live car tools

Live declares six real Android-side functions: `drive_car`, `stop_car`, `lock_car`, `get_car_status`, `get_camera_status`, and `get_network_status`.

Status and connection tools report **app-level observations only**, not physical car telemetry. AI motor commands are validated (100–650 ms), blocked without manual arming / fresh image / car Wi-Fi, guarded against duplicate tool IDs and invalidated after E-STOP. A batch containing STOP or lock blocks any motor move in the same batch. Only the human can unlock the car through the UI.

A read-only tool smoke-test button has been added. Without the car, connect Live over cellular or normal validated Wi-Fi, and test status/camera/network tools while locked. See [docs/GEMINI_TOOLS.md](docs/GEMINI_TOOLS.md).

## Hardware clarification: camera-only sensor

The physical car has **only a camera**, besides Wi-Fi control of its motors. No range sensor, collision sensor, GPS, odometry, battery telemetry, or motion acknowledgment is available. `get_car_status` is solely Android local state; `get_camera_status` is solely camera transport health. The actual images are streamed separately through Gemini Live. The app now tracks whether camera JPEGs were queued to the active Live WebSocket and will reject AI motor pulses when no recent frame was queued. Queued does **not** prove Gemini decoded/understood the frame.

## 0.1.3: fixed FPV driving console and camera resume

- Camera SurfaceView stays visible above the diagnostics pane and the touch driving pad. The `設定／診斷` button toggles advanced controls/logs without tearing down the Surface.
- A single eight-direction drag pad avoids relying on two simultaneous Button touch targets. Press/drag updates combined steering and throttle, release/cancel sends STOP, a 180ms refresh preserves the existing 500ms watchdog. Manual control still requires explicit human arming.
- The large red `緊急停止` is fixed outside the scrollable pane. E-STOP disarms the car; resuming requires human re-arm.
- Camera start waits for the H.265 SurfaceHolder lifecycle; the Activity tries to resume after returning from Wi-Fi settings and after offline car Wi-Fi appears. Auto-start deliberately avoids regular validated Internet Wi-Fi; manual reconnect overrides it. Manual camera STOP suppresses automatic retries until reconnect is explicitly requested.
- CarControl still transmits WL FPV packets at the OEM-derived 50ms cadence, but now reuses a Wi-Fi-bound UDP control socket rather than opening a fresh socket per packet. A Wi-Fi/endpoint change recreates the socket.
- Diagnostic data remains observational, not vehicle ACK or obstacle detection. No camera image protocol is claimed verified just because the UI resumes reliably.

See [docs/DRIVER_CONSOLE_QA.md](docs/DRIVER_CONSOLE_QA.md) for acceptance cases and follow-up camera diagnosis.

## v0.1.4: low-resource car tuning

**進階設定／診斷 → 操控調校** now has four presets plus limited 40–100ms TX cadence, 50–100% steering and 50–100% throttle amplitude sliders. Default remains the observed OEM 50ms, 100% / 100%. Applying settings stops/disarms the car and requires manual re-arm.

Periodic command pacing is fixed-delay to avoid catch-up bursts on slow devices. Identical manual commands only extend the local watchdog deadline; the periodic sender is the single source of repeated WL packets. TX logging is throttled to avoid heavy UI snapshots. Live local send gap, max gap, send cost, total TX and failures are shown without pretending they measure car acknowledgement or true motion latency. The AI tool status can also inspect the selected tuning and local TX counters.

[Read the control tuning A/B test guide](docs/CONTROL_TUNING.md).

## v0.1.5: branded launcher icon and driver polish

The user-approved Crew Car FPV buggy artwork is installed as an optimized WebP launcher icon under `app/src/main/res/mipmap-nodpi/ic_launcher.webp`. Both `android:icon` and `android:roundIcon` now reference it in `AndroidManifest.xml`. The selected artwork's full-resolution original remains available in the conversation; the app packages a compressed 160×160 launcher rendition to keep APK overhead small.

Driver HUD improvements:
- **重連鏡頭** is one tap away even with the settings panel hidden. It first stops/disarms motors, then requests a fresh camera session.
- The driver header now shows Gemini Live connection state without entering advanced settings.
- The phone screen stays awake **only while successfully armed**, and the flag clears on STOP/lock, backgrounding, AI lock, or detected Wi-Fi/TX disarm.
- The tuning section now labels whether the slider values are *already active* or *pending apply*. Applying connection settings through the settings button also stops/disarms before changing configuration.

**Hardware verification required:** check Android icon appearance on Samsung Launcher (including its round mask), app build and Wi-Fi camera reconnect, motor STOP, and screen-wake behavior. Do not interpret the on-device local TX stats as physical-car ACK.

## v0.1.9 — landscape two-thumb cockpit

Crew Car now launches in landscape orientation. The camera is centered with a steering-only left thumb region and a forward/reverse throttle-only right thumb region. Two fingers can operate simultaneously through one multi-touch tracker; lifting one finger centers **only its axis**. Central FPV stays unobstructed. Human arming and the large red STOP remain fixed across driving and settings, and settings opens with motors disarmed. The settings overlay does not remove the TextureView.

This changes **UI input ergonomics only**. It does not imply camera protocol compatibility, hardware motor acknowledgement, analog throttle, autonomous obstacle avoidance, or more accurate speed sensing.

[Two-thumb cockpit test checklist](docs/LANDSCAPE_COCKPIT_QA.md).

## v0.1.10 — simultaneous offline car Wi-Fi + cellular Gemini Live

On Android, being connected to a toy car's Wi-Fi without Internet does not itself prevent using cellular for cloud AI. However, Android may not expose a usable cellular `Network` until the app explicitly requests it.

Crew Car now requests a separate Android **cellular Internet Network**, waits until `NET_CAPABILITY_VALIDATED` is reported, and only then opens Gemini Live using that Network's SocketFactory and DNS. Car motor packets and FPV camera traffic remain bound to Wi-Fi; the entire app process is never rebound.

Requires `CHANGE_NETWORK_STATE` (normal Android permission) and working mobile data. Gemini's network request is cancelled after a bounded timeout or when Live disconnects. The UI reports when it is preparing mobile data. No Termux server or root is required.

[Samsung S25 / dual-network QA and ADB diagnosis](docs/DUAL_NETWORK_QA.md).

## v0.1.15: AI conversation shortcut + bounded accelerator hold

In landscape driving mode, **AI 說話** directly connects Gemini Live using the locally encrypted Key and enables the microphone (with permission), without opening advanced settings. During Gemini speech, the microphone recording and uploads pause; when speech and its playback buffer finish, mic capture resumes. The visible **打斷 AI** action explicitly flushes output and interrupts the reply, simultaneously STOPping any AI hold.

The new `set_drive_state` Gemini tool holds steering and throttle for 500–5000 ms in the existing car UDP heartbeat, with independent `keep` fields. The local `get_car_status` and driver HUD expose AI pedal and lease countdown. Any stale camera / Live video, manual priority, lock, Wi-Fi loss, app pause or lease expiry sends neutral STOP (safety gate failure also locks). **Fixed motor command is not measured cruise-control speed:** this car has a camera but no speed/odometry/obstacle sensors.

See [AI voice + pedal lease acceptance](docs/AI_TALK_DRIVE_QA.md).

## v0.1.16: actual held gas for manual control and AI cruise

- **Manual (v0.1.16 history, superseded in v0.1.17):** the experimental pointer latch and 120ms / 1.6s hold mechanism was rolled back due to physical control regression. Use the v0.1.17 manual-control behavior below.
- **AI:** `set_drive_state` can keep pedal + steering for a default 15s / max 30s without repeated Gemini tool calls. A new `steer_car(left/right/center, hold_ms)` changes only steering for a brief 100–2000ms interval and returns to center, leaving gas held **without braking or extending its deadline**. A legacy pulse tool cannot interrupt an active AI cruise. E-STOP, manual priority, camera/Live stale and Wi-Fi loss fail closed.
- Fixed pedal is NOT measured or guaranteed speed; this camera-only toy does not have a collision/depth/odometry sensor. Only use on clear private ground with direct supervision and physical STOP in reach.

Updated [tool contracts](docs/GEMINI_TOOLS.md) and [regression QA](docs/AI_TALK_DRIVE_QA.md).

## v0.1.17: restore tested v0.1.14 manual driving mechanism

The hand-operated left/right steering and right-hand forward/reverse buttons were rolled back **exactly to the v0.1.14 touch implementation**. An active accelerator touch stops when the original pointer lifts/cancels **or leaves its selected forward/reverse button area** (you must press the button again). The UI sends a manual lease renewal every **180ms**, and the internal **500ms deadman timeout** restores the previous motor control behavior. The existing car-specific UDP transmitter/tuning still sends packets at the configured period while a manual command is active. User must deliberately arm the car, STOP is unchanged, and motor ACK remains unavailable.

This rollback targets **manual control only**: Gemini Live, AI command tools, Wi-Fi/4G split, camera, KL FPV scaffolding, and other features remain on their current branch code and are intentionally **not** fixed or redesigned yet. Follow [manual rollback QA](docs/MANUAL_CONTROL_ROLLBACK_QA.md).

## v0.1.18 — ergonomic landscape driving cockpit (UI-only)

The driving cockpit now dedicates **60%** of available width to the central FPV feed by default (side controls each **20%**, configurable 18–26%). Four status rows are condensed into one 28dp instrument strip; camera and Gemini continue using the original stream and model interfaces.

- **Left thumb:** large left/right steering buttons by default; optional legacy virtual joystick with adjustable deadzone. The output is still the same discrete -1/0/1 steering axis.
- **Right thumb:** larger forward/reverse hold buttons, consistent with the manual pointer-up/pointer-leaves-button neutral behavior in v0.1.17. A second finger can steer without releasing throttle.
- **Control feedback:** local accepted-input highlighting is shown only when the car is armed; haptic feedback on meaningful button/axis changes is optional. UI highlighting does not imply car acknowledgement.
- **操控 quick settings:** steering mode, paired side width, forward/reverse vertical gap and position, joystick deadzone, and haptics; stored in SharedPreferences, with restore defaults. Opening and applying layout changes first STOPs/disarms and never re-arms on the user's behalf.
- **Compatibility:** `CarControl.java`, the 180ms manual keepalive, the 500ms watchdog, OEM motor packets, Wi-Fi/Gemini and the separate AI tools are **unchanged**. Full advanced Settings redesign is intentionally deferred until the driver UI is accepted.

See [v0.1.18 driving UX QA](docs/DRIVER_UX_V018_QA.md) for detailed dual-thumb, stop, geometry and rotation acceptance checks.

## v0.1.19 — landscape settings categories + Android system-bar clearance

The full diagnostics/settings screen is now a landscape **two-column layout**, with a fixed left rail (車輛與連線 / 操控設定 / AI 與攝影機 / 進階與診斷), and a single independently scrolling content panel on the right. All former fields are moved directly into category groups, preserving their existing values and listeners. A persistent red STOP and a locked safety status remain at the bottom. Opening Settings still stops/disarms and never re-arms automatically; the settings view overlays the existing attached camera surface.

The driving header is now inset from system status/nav bars, edge cutouts and the device's camera hole via Android `WindowInsets`, so both landscape rotations respect real safe areas (including Android 15 edge-to-edge). No hardcoded status-bar spacer is used.

**Unchanged:** car motor and packet code, 180ms manual loop, 500ms deadman, steering/throttle controls, AI tools, camera stream, vehicle profiles and BYOK secret storage.

[Landscape settings / Samsung safe-area QA](docs/LANDSCAPE_SETTINGS_INSETS_QA.md).

## v0.1.20 — Crew Vision: offline person boxes + explicit target selection

The landscape driving header has a new **辨識** toggle. When enabled, an optional on-device MediaPipe EfficientDet-Lite0 (COCO person) task draws bounding boxes in the center FPV image. Tap a person box to select it; nearby detections can be visually matched across frames. The lock is deliberately **fail-closed**: after lost target / stale frames, the app displays "目標遺失" and will **never auto-select a different person**. Tap a visible box again to select.

- **WL HEVC:** additional up-to-5-FPS 320-wide local TextureView sampling; existing 1-FPS Gemini Live JPEG feed stays unchanged.
- **MJPEG:** forwards decoded camera frames into an async, bounded CPU inference queue (busy frames dropped); bitmap remains owned by the existing preview.
- **Offline:** the official model is fetched by Gradle at initial APK build time and bundled as an asset. Vision does not need an Internet/Termux server when the car is in use. Android build needs Internet once to download the official model and MediaPipe dependency.
- **Safety:** this is **display-only** computer vision. No motor tool or UDP command is issued by detection, target selection or lost-target processing. Camera reconnect/app pause turns recognition off and clears the selection. `get_camera_status` exposes read-only Vision status.
- **Provenance / validation:** [Crew Vision Stage 1 QA](docs/CREW_VISION_STAGE1_QA.md). User must verify model download, Android SDK build, performance on Samsung and no motor/control regression before depending on it.

**Not included yet:** object following, autonomous steering, distance estimation, obstacle avoidance, real-person identity matching, or any vision-to-motor control.

## v0.1.21 — full-screen FPV and opt-in supervised person following

The FPV picture now **fills the driving cockpit** edge to edge. Left and right driving buttons are painted at **50% transparency** over the FPV; control labels remain readable. Both HEVC and JPEG preserve image aspect ratio by center-cropping extra edges instead of stretching people. The center video remains tappable even through the full-screen driving-pad layer.

After enabling **辨識**, tap a person to select them. To try supervised following, physically arm the WL car and press **跟隨**; read and accept the caution dialog. The phone then performs **local** vision-to-axes decisions for a **maximum eight-second run**, issuing at most a 350ms movement lease per fresh person detection, and capped to **50% OEM motor packet amplitudes**. It stops on apparent proximity, target drifting too far sideways, person loss, stale video, Wi-Fi loss, manual takeover, settings or emergency STOP. The 8s absolute timer disarms and requires fresh manual arming.

**This is experimental, not autonomous safe navigation.** A monocular camera cannot reliably measure distance or see every obstacle; there is no obstacle avoidance, motor speed feedback or packet ACK. Test with wheels raised, then only on empty private ground under direct supervision, with physical power disconnect accessible. Avoid children, pets, traffic, stairs or uncontrolled public settings. Gemini motor tools are not used by the local follow loop.

Manual RC button hold semantics and its 180ms renewal / 500ms watchdog remain unchanged. See [full-bleed Vision Follow QA](docs/VISION_FOLLOW_FULLBLEED_QA.md).
