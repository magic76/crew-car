# Crew Car v0.1.9 — Landscape two-thumb cockpit

## UI arrangement

The Android launcher Activity is fixed to **sensorLandscape** (both landscape orientations).
The driving screen uses a fixed and simple horizontal hierarchy:

- **Top compact status header:** Crew Car, reconnect camera, settings toggle, Wi-Fi/Live/FPV health. Status text is ellipsized instead of making the controls scroll.
- **Left 26% of driving surface:** steering ONLY (left / neutral / right). Lift left thumb → steering returns to center without cancelling an ongoing right-hand throttle command.
- **Center 48%:** unoccluded FPV camera view (TextureView or HTTP JPEG ImageView), kept attached when opening and closing the settings overlay.
- **Right 26%:** throttle ONLY (top forward / center neutral / bottom reverse). Lift right thumb → throttle goes to zero without cancelling a held steering command.
- **Fixed bottom:** human arming checkbox, large red STOP (always visible, also in settings), selected tuning summary.

The two outer controls are drawn by **one** Android View (DualStickDriveView), with a pure-Java DualStickInput tracking two independent pointer IDs. Each pointer remains assigned to its original side even if dragged across the center. The central video area is not used to generate motor commands. MotionEvent cancellation, View detachment and backgrounding neutralize controls. A two-thumb gesture produces one combined steering/throttle state, not independent racing transmissions.

The center of each control has a **26% normalized deadzone** to prevent small thumb movements from twitching the car. The UI continues to send discrete -1/0/+1 axis commands and honors the existing OEM amplitude tuning; it does **not** pretend to provide genuine analog motor control without confirming firmware support.

Opening **設定** invokes E-STOP and disarms before switching overlays. The camera TextureView is never removed from its FrameLayout, so the settings switch is not supposed to restart the camera. Returning to driving does **not** re-arm. Rotating the phone to the opposite landscape side causes the Activity lifecycle to pause and disarm, also intentionally fail-closed.

## Verification cases

1. On the Android SDK machine, build and run tests:

   ```bash
   gradle :app:testDebugUnitTest :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.crew.car/.MainActivity
   adb logcat -v time -s CrewCar:D AndroidRuntime:E
   ```

2. Launch the app in portrait: it should switch into landscape, regardless of the device's portrait rotation lock (subject to Android policy). Confirm both landscape directions work. Verify STOP and settings are visible without scrolling on a small landscape phone.
3. Before arming, touch left and right regions: the motor should remain locked.
4. Raise the wheels. Arm once, use left thumb alone to steer left/right, then right thumb alone to go forward/back.
5. Hold both thumbs (left + forward, right + reverse) and confirm combined directional control. Ensure one finger lifting affects only the corresponding axis, and both lifting produce neutral STOP.
6. Drag a left-thumb pointer across the video and right-side zones; it must remain **steering** and must never become the throttle pointer. Reverse the experiment for the right thumb.
7. System gesture cancel, app backgrounding, Wi-Fi loss, E-STOP, camera reconnect, or opening settings must STOP/disarm appropriately. After returning from settings or orientation change, the car requires a **fresh human arm**.
8. Open/close settings repeatedly: no camera relaunch should occur solely due to overlay toggle; camera keeps using the existing TextureView surface unless Android itself destroys it.
9. Compare response with existing v0.1.8 50ms baseline. Keep the selected control interval and amplitude unchanged for this UI test. No claims of physical car ACK, speed sensor or automatic collision prevention.

## Known limits

- Camera-only toy: no odometry, GPS, distance sensor, collision detection, or motor acknowledgements.
- The FPV video decoder can remain black if HEVC framing or decoder setup is incompatible. Landscape UI is not a video protocol fix.
- On phones with a narrow usable landscape height, system bars and the keyboard may reduce the FPV area. The bottom STOP must remain accessible even when settings are displayed.
- The left/right controls are *directional*, not pressure-sensitive analog controls. The motor sender's watchdog and STOP safety are unchanged.
