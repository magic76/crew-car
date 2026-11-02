# Crew Vision v0.1.20 — Offline Person Detection / Tap-to-Track (Stage 1)

## Objective and strict safety boundary

Add optional **on-device visual perception** to the current Crew Car Android app. It can:
- Detect COCO `person` objects in FPV and show bounding boxes and confidence.
- Lock onto one **explicitly tapped bounding box** in the central FPV region, associate nearby boxes across successive frames by overlap and center distance, and draw an orange selected box.
- Mark the selected target as **LOST** if it disappears, occludes, or frames become stale. **Never automatically select another person**; the user must tap again.
- Show a read-only status to Gemini through existing `get_camera_status` results (person count, selected/lost), with `vision_can_control_motors=false`.

**It never drives the car.** No `CarControl`, UDP motor protocol, Gemini steering/motion tools, watchdog or tuning changes. Do not use this display-only module as an obstacle detector or a face/identity identifier.

## Implementation

- Engine: `CrewVision.java` + MediaPipe Tasks Vision `0.10.32`, CPU backend, official COCO EfficientDet-Lite0 **int8**, 320x320 model. A dedicated serial executor performs inference, dropping frames if busy; time-gated to approximately **5 FPS**. It is a best-effort target, not benchmarked actual performance.
- The model is downloaded at **Android build time**, from versioned Google-hosted `efficientdet_lite0/int8/1/efficientdet_lite0.tflite`, checked for size and the TFL3 FlatBuffer identifier, and bundled into the APK. The initial Gradle build requires Internet; the running app and car Wi-Fi do **not** download the model. The generated build directory is not tracked in Git.
- **WL HEVC:** while the camera is active and the cockpit visible, a separate main-thread sampler pulls 320-wide rendered `TextureView` bitmaps every 200ms (only if worker available). Existing 1 FPS `GeminiLive.frame()` and 1 FPS legacy HEVC snapshot remain unchanged.
- **HTTP JPEG/MJPEG:** existing decoded frame callback forwards a **borrowed** bitmap to the Vision executor, with the same drop-if-busy policy. Frame ownership and the existing preview are unchanged.
- **Overlay:** `VisionOverlay.java` is a transparent canvas above the central FPV only; it draws boxes and accepts taps within visible person boxes. For MJPEG FIT_CENTER, letterbox margins are accounted for. For HEVC, the `TextureView.getBitmap()` input and on-screen overlay share frame coordinates.
- **Tracker:** `PersonTracker.java` is pure Java. It uses normalized rectangles, IoU >= 0.25 and normalized center distance <= 0.20, without face identification or guaranteed multi-person re-identification. One miss or a stale sample marks the target lost and freezes automatic reacquisition. User must tap again.
- **Opt-in:** user taps **辨識** in the landscape driving header to enable/disable. No background inference. Pausing app or stopping/reconnecting the camera disables Vision, clears all person boxes, and prevents late callbacks from showing stale detections.

## Build / source licenses

The model and MediaPipe library are sourced from the official Google AI Edge ecosystem. Model URL and MediaPipe API guide:
- https://developers.google.com/edge/mediapipe/solutions/vision/object_detector
- https://developers.google.com/edge/mediapipe/solutions/vision/object_detector/android
- https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite

The model is fetched by Gradle **only when absent** or invalid; a physical app uses a bundled asset. The source is HTTPS/version-path pinned but a published file digest has **not** been independently verified. Do not treat a TFL3 header check as a cryptographic integrity guarantee.

## Acceptance on a local machine with Android SDK/JDK 17

```sh
git pull origin main
gradle :app:prepareCrewVisionModel :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -v time -s CrewCar:D AndroidRuntime:E
```

1. **Offline model:** run Gradle once with Internet; inspect `app/build/generated/crewvision-assets/efficientdet_lite0.tflite` (nonzero, several MB). Rebuild offline after cache/model exists. Install APK, connect to offline car Wi-Fi, and enable Vision **without mobile data**.
2. **Safety:** wheels elevated, ensure car is locked; toggle **辨識** and show people to FPV. Neither recognition nor tapping should arm or issue UDP motor commands. Compare `CarControl.java` file SHA with pre-change main.
3. **Visual:** HEVC preview is still smooth, Gemini Live still receives its original ~1 FPS frame feed; Vision boxes appear on real person in frame. Measure actual inference ms in Logs. Target **5 FPS**, but if CPU takes longer it must safely drop frames, not stack calls or block motor sender.
4. **Selection:** place two people in frame, tap one box, follow with small person movement and verify orange box follows. Remove selected person: label **目標遺失 · 請重新選擇**, with no automatic switch to remaining person. Tap again to explicitly select.
5. **Geometry:** test central FPV camera resizing (side panel 18–26%), both landscape orientations, and MJPEG aspect-fit letterboxing. Tap regions must align with visible detections, and touches in left/right driving pads must still work unchanged.
6. **Connection:** disable FPV, reconnect or switch vehicle: Vision becomes OFF and target clears; late queued inference cannot resurrect stale boxes. Return to the app from background; Vision must be OFF until reenabled.
7. **Error:** simulate missing model on the build host (the Gradle task should download or fail closed), and test unsupported MediaPipe device behavior: user receives a visible error, Vision turns off, motor behavior remains unchanged.
8. **Telemetry:** `get_camera_status` reports `local_person_detector_enabled`, `people_detected`, `selected_target`, `selected_target_lost`, and `vision_can_control_motors=false`. This does not give Gemini a new vision-based movement tool.
9. **Manual regression:** after disabling Vision, hold forward/reverse with the right thumb, steer with the left, test STOP and app background. Original **180ms manual keepalive / 500ms deadman** unchanged.

## Next phase (NOT implemented here)

Stage 2 would be *steering-only visual servoing* with explicit human opt-in, per-frame local control, freshness check and immediate STOP on lost target. Stage 3 would be supervised slow-follow, only after distance/obstacle safeguards are available. This version has **no automatic throttle, obstacle avoidance or identity verification**.

**Validation status:** pure-Java tracker JUnit tests and integration static checks are authored. Without a local Android SDK or physical car, actual APK build, model download, inference FPS and motors are not verified here.
