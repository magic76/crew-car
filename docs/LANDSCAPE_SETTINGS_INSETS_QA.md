# Crew Car v0.1.19 — Landscape Settings + Safe-Area QA

## Scope

This is a **layout-only** upgrade of the existing landscape driver application. Original settings widgets are **moved**, not recreated. Camera, Gemini Live, vehicle selection, UDP packets, safety interlocks, tuning values, 180ms manual renewal and 500ms deadman remain unchanged.

## Landscape Settings

The driving header's 設定 action always sends **STOP, locks/disarms**, then opens an overlay within the existing FPV cockpit. The video TextureView remains attached behind the overlay; switching categories does not disconnect or restart the camera.

A fixed **184dp left navigation rail** includes:
1. 車輛與連線 — car profile, Wi-Fi configuration and network diagnostics shortcut
2. 操控設定 — touch layout quick settings, steering/throttle tuning, presets and safe apply
3. AI 與攝影機 — FPV stream controls, Gemini API Key/mic/test tools and transcript
4. 進階與診斷 — host/IP/Port, custom control payloads, transport, TX statistics and Debug export

Only the **right panel scrolls**; left nav remains visible. All four panels are built from the original form controls and preserve their existing listeners, values and preference keys. Switching categories changes visibility only.

The bottom **red STOP** button remains visible in both driver and settings. While settings are open, the manual arming checkbox is hidden and replaced with **「設定模式 · 車輛已上鎖」**. Returning to driving does not automatically rearm. Control-layout popup still requires STOP/disarm before editing, and AI/video/connection features are unchanged.

## Android Status / Navigation / Cutout Insets

- Uses Android `Window#setDecorFitsSystemWindows(false)` on API 30+, and legacy layout flags on API 26–29, to handle both OS edge-to-edge modes consistently.
- The entire root screen applies `WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()` **left/top/right/bottom**. Pre-30 uses platform system-window inset values.
- Insets respond to rotation and system changes without an assumed fixed status-bar height. Header, side navigation, touch controls and the persistent bottom STOP must remain inside safe bounds.
- System status/navigation bars remain visible; they are not hidden or overwritten.

## Verification

Build on an Android SDK host:

```bash
git pull origin main
gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -v time -s CrewCar:D AndroidRuntime:E
```

1. **Status bar clearance:** On Samsung S25 Ultra, test BOTH landscape orientations, 3-button navigation, gesture navigation, cutout/camera-hole and display-size/font settings. The app header and its buttons must be completely below/outside system bars and side cutouts.
2. **Navigation:** Tap 設定. Motors STOP and lock. Only the left sidebar remains fixed. Select all 4 categories, scroll the right content to bottom, change categories and verify it resets to top. No blank screen, duplicate view or crash.
3. **Vehicle:** Switch WL/custom/KL vehicle profile through its original spinner. Changing profile must STOP, store per-vehicle config and keep KL motor controls blocked until verified. Confirm Wi-Fi actions still work.
4. **AI/camera:** Change camera mode safely; start and stop camera; verify screenshot/TextureView lifecycle is unchanged. Gemini Key remains encrypted in local store; Live connection and tool read-only smoke test work; navigating tabs must not force reconnect.
5. **Control:** UI button layout shortcut, profile/timing/amplitude tuners and Apply button retain existing semantics, with manual re-arm required. New settings tab must not modify motor settings on mere opening.
6. **Advanced:** IP/Port/transport/encoding and custom packet text fields must retain all values. Save, switch categories, reopen settings and confirm values. Test Debug export and TX diagnostic updates while panel is hidden and then shown.
7. **Safety footer:** The red STOP stays reachable from all four tabs even at small landscape sizes; no arming checkbox appears in settings. Switch back to driving; verify user must arm explicitly. Check STOP during an open dialog, Activity pause, network loss, and camera adapter switch.
8. **Manual regression:** With wheels raised, arm and hold right forward/reverse, steer left/right with the other thumb, release to STOP. Confirm periodic TX at the original tuning value and no changes to manual 180ms lease renewal / 500ms deadman.
9. **Large font / narrow landscape:** Check labels may ellipsize but category navigation, scroll area and persistent STOP remain usable, including keyboard opened on a field.

## Evidence and limitations

Source structure and pure-Java category/validation unit tests are authored; no emulator, SDK build or physical-car run is available in the assistant environment. GitHub CI may not be configured. Local agent must execute the Gradle build, tests, manual UX smoke, device rotation/cutout checks and STOP tests.

Nothing in this change proves physical car acknowledgment, speed or obstacle avoidance.
