# Crew Car v0.1.21 — Supervised Person Follow + Full-Bleed FPV

## What changed

### Full-width FPV behind controls
- The camera fills the **whole cockpit region** (between the top status bar and bottom STOP console).
- Left steering and right forward/reverse controls are **50% alpha** overlays while idle; labels remain opaque for contrast. Press feedback is brighter.
- Camera is **center cropped**, never aspect-stretched. Cropping means some source-image edges may not be visible. This is intentional for a full-bleed screen rather than a letterboxed feed.
- Both HEVC TextureView and HTTP JPEG ImageView are cropped. The person overlay transforms MJPEG detection coordinates to align with `CENTER_CROP`; rendered HEVC screenshots follow their actual TextureView coordinate space.
- Central FPV taps pass through the drive-pad layer and can select a person. A second center pointer while controlling an edge can also select. Left/right manual touch semantics, 180ms UI pulse, 500ms deadman, UDP packet format and original tuning **remain unchanged**.

### Visual following — experimental, never unattended
1. Keep the **WL Tech profile**, car Wi-Fi and camera active. Enable **辨識** and select exactly one person by tapping the green box.
2. Deliberately **解鎖駕駛** (human arming). Then tap **跟隨** and confirm the warning dialog.
3. A single affirmative tap grants **at most 8 seconds** of local following. The app uses `VisionFollowPlanner` on device: if the selected person's bounding box is centered, sufficiently small and high-confidence, it commands **forward**; with a small left/right offset it can steer while continuing forward. At apparent close range, low confidence, overly distant or far off-center, it commands STOP. **Never reverses or automatically searches for a lost person.**
4. Motor outputs are capped to **50% of the original steering and throttle packet deltas**, regardless of manual tuning. This is an open-loop packet amplitude, **NOT measured speed**.
5. Every **fresh** detection can renew at most a **350ms motor lease** through the existing periodic sender. The local motor watchdog checks the vision/camera/Wi-Fi/foreground/mode gate; a stale image, target loss, 350ms missed update, or the 8-second absolute limit stops the motor. The hard 8-second timeout also **disarms**. Camera reconnection, settings, STOP/lock, app backgrounding and manual control all cancel the follow mode. If the user touches a manual control while follow is active, physical/manual control takes priority and follow ends.
6. No Gemini motor Tool Calls are involved. Gemini can still converse/describe the FPV image while local Vision is responsible for steering updates. The existing model tool contract is not changed.

## Important hardware limitation

This vehicle has **only a monocular camera, no measured distance, speed, odometry, collision sensor or vehicle ACK**. Person bounding-box height is NOT a reliable distance measure: crouching, turning, occlusion and perspective can all break it. This experimental follower **cannot safely navigate obstacles or reliably maintain a separation distance**, even though it stops when a person appears large.

**Start with wheels raised**. After passing stationary wheel tests, attempt only on open, flat private ground with no bystanders, a nearby power switch and direct supervision. Do not use on streets, near children/animals/traffic, ledges or stairs. An emergency STOP on the phone relies on the same UDP connection, so have the hardware power switch within reach.

## Testing checklist

Build with an Android SDK/online Gradle host:

```sh
git pull origin main
gradle :app:prepareCrewVisionModel :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -v time -s CrewCar:D AndroidRuntime:E
```

- Full-bleed visual: both cameras cover the cockpit, under 50% translucent left/right pads; test all side-width settings, landscape orientations, status-bar safe insets and control visibility. Check edges are **cropped**, not stretched; test alignment of person boxes with cropped MJPEG and HEVC sources.
- Touch: tap a person in the middle of the cockpit and verify target highlights. Hold either control side then tap with another finger in the center. Release the manual throttle and verify STOP; center taps must never emit motor TX.
- Safety preconditions: enable Vision without unlocking and tap Follow; it MUST refuse. Try custom/KL profile, stale/no person, disconnected Wi-Fi, model not ready, AI or manual drive already in progress; all must refuse without motor movement. Follow must not automatically start simply because a person is detected.
- Bounded movement: raise wheels. Select centered target in the conservative size band; physically arm and explicitly confirm **跟隨**. Verify TX `vision:0:1` with reduced amplitude at the normal configured packet period; move target slightly left/right and verify `vision:-1:1` / `vision:1:1` without releasing forward. Check no reverse is ever sent. Use camera/target conditions outside the allowed band to assert STOP.
- Fail-closed: remove selected person; cover camera, drop stream frames, background app, disconnect Wi-Fi, open Settings, press STOP, touch steering, or let 8 seconds elapse. STOP must occur promptly; 8-second limit must disarm and require manual rearm. No auto-select when other people remain. Confirm no stale queued motor write after STOP.
- Manual regression: switch OFF Vision, hold forward and reverse, steer simultaneously using both thumbs, release to stop. Check old 180ms/500ms manual and original OEM packet tuning still work.
- Make sure app stays responsive at 5 FPS with MediaPipe inference and HEVC codec, and verify user sees a message if the model fails to load.

## Verification performed here

Source-based integration and safety checks plus pure-Java `VisionFollowPlannerTest` were added; **there is no Android SDK/Gradle compile, physical car test or quantified vision FPS validation in this environment.** Review the local device report before trusting any physical follow function.
