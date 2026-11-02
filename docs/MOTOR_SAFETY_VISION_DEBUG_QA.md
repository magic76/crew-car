# Crew Car v0.1.23 — Motor authority and Vision Debug

## Safety review changes

- Central **MotorAuthorityPolicy** enumerates LOCKED, IDLE, MANUAL, GEMINI, VISION and enforces exclusive automated ownership. Existing arm button/manual control and on-device 8s Vision session are preserved.
- All queued AI/Vision commands re-check the epoch and motor owner **at execution time**, so a previously queued command cannot regain ownership after manual takeover, disarm or STOP. This does not provide physical motor feedback.
- `emergencyStop` invalidates queued commands and clears the requested direction synchronously; the STOP send still executes on the existing serial motor I/O executor. For a supported WL UDP stop payload, two **best-effort** extra neutral frames are scheduled after 45 and 110ms on the **same persistent UDP socket**, guarded by the STOP epoch, neutral state and a 300ms maximum. They are cancelled by a newer motion state/STOP epoch.
- Motor telemetry includes last stop reason, client request→first local send latency, attempts at extra neutral frames, active owner, and timestamps. Local send time is **not** a car ACK. The serial sender can still be delayed by an in-flight blocking send; external hardware stop remains important.
- AI motor tool schemas were intentionally not expanded; Gemini's existing 15–30s control remains subject to active human arm and existing camera/Live freshness gate. A dedicated per-voice-turn motor intent authorization would be a follow-up, not something silently invented in this patch.

## Vision timing and in-cockpit debug

Enable **FPV 顯示 Vision 診斷 HUD** under 進階與診斷. It is OFF by default, remains local and writes no image data.

HUD reports:
- Actual recent completed-detection rate (smoothed FPS), model inference milliseconds, Android bitmap **capture→UI callback** latency and result age.
- People count, selected-person confidence, horizontal normalized offset, motor owner and latest stop reason.
- Local STOP request→send time. None of these values is sensor exposure time, end-to-end motion latency, distance or a vehicle ACK.

HEVC local Vision sampling and the follow gate now use TextureView's `onSurfaceTextureUpdated` timestamp rather than the unrelated 1fps Gemini JPEG snapshot age. A fresh render callback still cannot prove exposure freshness; frame decoding/network buffering can add latency. Gemini's original frame cadence remains unchanged.

The read-only `get_camera_status` distinguishes **vision_follow_capable**, **vision_follow_active** and current motor ownership; it no longer claims Vision is incapable of motor control when the explicitly authorized local follower is moving.

## Test and release gating

```bash
git pull origin main
gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -v time -s CrewCar:D AndroidRuntime:E
```

GitHub Actions Android build has been added because the repository has no Gradle wrapper. Review CI results before installing.

1. Test pure-Java `MotorAuthorityPolicyTest`, `VisionDebugMetricsTest`, `SafetySourceContractTest`, plus all old tests. Confirm no source ownership can authorize simultaneous AI and Vision, and manual preempts AI.
2. Wheels raised: verify normal manual hold→release behavior, 180ms update/500ms timeout, STOP button while both fingers are active, STOP while AI and Vision are running, and STOP on Android pause/disconnect.
3. Simulate high UDP latency or destination failure. Confirm local STOP delay telemetry reflects queued delays and never mislabels queued/sent as hardware acknowledged. Inspect neutral burst cadence and verify it does not re-activate old movement.
4. Enqueue Gemini command, then tap manual/STOP before dispatch. Check epoch/ownership cancels the previously queued motion.
5. Enable Vision + HUD and visually verify changing-person count, FPS, model latency, frame age, target confidence/offset, selected-target loss STOP reason, 8-second expiry and new ownership status.
6. Inspect TextureView timestamps during rotation/camera reconnect. Stale decode should disable Vision follow, even if UI remains visible. Ensure the HUD is non-interactive and does not cover the independent red STOP.
7. Check old Gemini Live voice/stop/lock/read-only tools and that `get_car_status`/camera status no longer contradict actual local Vision capabilities.
8. Verify no camera bitmap is written to persistent debug storage. Ensure the visual HUD is off after first install and can be toggled via settings.
9. Final hardware test in a clear private space with an accessible physical power switch. **Do not consider the 50% command power, 8s limit or box-height heuristic safe distance/obstacle controls.**

## Outstanding / not proven

No reliable car-side STOP acknowledgment or watchdog, no collision or ranging sensor, no precise camera exposure timestamp, no true vehicle speed feedback, no end-to-end motion latency, and no guaranteed response within 25ms when the I/O thread stalls.

These limitations remain blockers for unattended or near-people following. If Android CI fails or raised-wheel tests fail, do not treat this as a validated driving release.
