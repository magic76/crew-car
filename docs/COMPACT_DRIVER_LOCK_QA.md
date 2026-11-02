# Crew Car v0.1.22 — Compact Lock + Floating STOP

## UX change

The 62dp permanent footer (「解鎖駕駛」checkbox + oversized red STOP + transmission tuning status) is removed, returning that height to the live FPV cockpit.

- The **header** now contains a small **🔒 已上鎖 / 🔓 已解鎖** button. Its color and accessibility label mirror the actual `CarControl.armed()` state. Existing `arm` CheckBox is kept internally as a detached state holder so its original `applyConfig() → CarControl.arm()` validation and safety callbacks remain intact.
- **Unlock** now requires tapping the header lock and explicitly confirming an alert; Cancel does nothing. The app **never auto-arms** after restart, reconnect, settings or backgrounding.
- Tapping the **unlocked** header immediately stops/disarms; settings still STOPs on entry. While settings is open, header lock is disabled and the fixed left sidebar shows **🔒 設定已上鎖**.
- **■ 急停** is a permanently visible red floating button at the **bottom-right of the cockpit**, above both the manual control overlay and the settings overlay. Its touch-down immediately sends the STOP/disarm workflow; touch-up invokes its accessibility click. It remains visible during settings.
- A hard 12dp vertical gap separates the reverse throttle touch region from the emergency-stop hitbox. This separation uses `DriveStopGeometry` shared by **drawing and hit testing**, not just a decorative overlay. A second finger on STOP can interrupt an active manual throttle via independent pointer dispatch.
- The tuning readout moved into the Controls tab, preserving the original `tuningActive` binding without consuming live-video space. The right settings scroller has additional bottom padding so its last fields can scroll above the floating STOP.
- Android window insets from v0.1.19 remain in place, so Header does not overlap the status bar or camera cutout.

## No control redesign

All motor, camera and AI/following protocols and safety checks are unchanged. Manual controls retain original 180ms keepalive / 500ms watchdog and pointer-release semantics. Person following keeps its explicit user authorization and 8-second cap; app background, lock, Wi-Fi loss, camera failure, or E-STOP still cancel motion. The red STOP is accessible both when driving and in Settings.

## Acceptance on an SDK-equipped Android machine

```bash
git pull origin main
gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -v time -s CrewCar:D AndroidRuntime:E
```

1. Start the app: header shows **🔒 已上鎖**, FPV fills the space previously occupied by the 62dp footer. No giant bottom Unlock, STOP or tuning bar remains.
2. Try forward/reverse while locked (with wheels **raised**). Neither motor may move. Tap the lock, cancel dialog: still locked. Confirm dialog: only then check all original profile/Wi-Fi/config arming conditions. Confirm text/color updates. Tap unlocked icon: STOP + lock.
3. Hold right forward or reverse with one finger; press the bottom-right red STOP **with a second finger**. The motor must STOP immediately **on touch-down**, before the second finger lifts. Release both fingers: no restart. Re-arm only manually through header.
4. Press the bottom-right zone while locked: it MUST NOT activate reverse. The visual reverse button must end at least 12dp above STOP hitbox, for offsets -24/0/+24, side widths 18/20/26%, both steering styles, and both landscape orientations. `DriveStopGeometryTest` covers reserved vertical geometry.
5. Open Settings during any manual movement, AI hold or visual follow: app STOPs/disarms. The header lock is disabled, the left sidebar reads **設定已上鎖**, and the floating STOP remains clickable. Right-side settings content can scroll above STOP. Returning to driving does NOT re-arm.
6. Verify status bar / cutout Insets with the Samsung Galaxy landscape orientations and both gesture/3-button nav. Lock and Settings should not collide with system status bar or disappear at narrow widths.
7. Disconnect car Wi-Fi, pause/background app, stop the camera, or wait for AI/person-follow timeout: the header lock must eventually show locked. Tap emergency STOP at every state and verify no subsequent motion.
8. Manual regression: with wheels lifted, hold forward/reverse and steer left/right as before, release each finger; packets follow the existing motor interval. Confirm no change to motor duration logic and no accidental touch reassignment to STOP.
9. Accessibility: the Header and E-STOP labels must be descriptive in TalkBack; user can activate floating STOP by keyboard/accessibility click.
10. Test Gemini Live and Crew Vision person target selection after removing the footer: previous functions still work, and the permanent red STOP remains unobstructed.

## Limitations / evidence

Static source checks and `DriveStopGeometryTest` can verify structure and vertical no-overlap invariants. In the assistant environment, Android SDK, UI instrumentation, touch hardware and actual car are unavailable; therefore Android compilation, two-finger hit dispatch and physical STOP response require the local agent's validation.

The car has no hardware ACK; a successful Android UDP send is not proof the wheels stopped. Keep the physical power switch reachable.
