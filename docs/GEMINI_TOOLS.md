# Crew Car — Gemini 3.8 Live tool contract

## Declared tools

| Function | Arguments | What it actually does |
| --- | --- | --- |
| `get_car_status` | none | Read lock state, manual override, queued/current command, remaining pulse, attempted TX state/age and camera freshness |
| `get_camera_status` | none | Last received frame age/resolution and camera adapter, NOT computer vision or obstacle sensors |
| `get_network_status` | none | Android Wi-Fi transport and validated Internet path for Gemini, without claiming the SSID is definitely the car |
| `drive_car` | `direction` = forward/backward/left/right, `duration_ms` = 100–650 | Queue **one bounded pulse** if explicitly armed, not in manual override, Wi-Fi present and frame fresher than 2.5 seconds |
| `stop_car` | none | Send STOP even while locked; cannot guarantee packet delivery after a Wi-Fi failure |
| `lock_car` | none | Disarm motor control and queue STOP; only a user can unlock using the Activity checkbox |

**Not implemented:** autonomous driving, distance commands, velocity control, battery/position telemetry, odometry, obstacle recognition, pan/tilt, headlight, beep, Wi-Fi switching or robot mapping. Do **not** advertise hypothetical hardware functions as available.

Each tool response contains a Gemini call ID when one is supplied, a machine-readable status, a human-readable message, and `retryable:false`. `get_*` functions return a structured `data` object. `drive_car` returns `device_acknowledged:false` and `motion_verified:false`; queued is NOT executed or completed. No car hardware acknowledgment has been confirmed.

## Runtime rules

- All tool declarations use `NON_BLOCKING` consistent with the planned Gemini 3.8 Live workflow. Never confuse `toolResponse` send success with hardware success.
- User must explicitly request movement; never call sequential motions to infer distance.
- A human must arm the motor checkbox. Gemini is deliberately **not** granted a function to unlock.
- Calls with invalid directions, missing/out-of-range duration or unrecognized names are rejected locally before touching the network.
- STOP or lock within the **same tool batch** prevents all moves in that batch, regardless of function order.
- Duplicate Gemini function-call IDs are cached (last 64) and never replay motion. Reconnect clears the cache; old-WebSocket messages are ignored.
- STOP increments a motion epoch so already-queued drive commands cannot start after the STOP. Camera freshness (>2.5s), Wi-Fi loss, UI pause and manual priority also restrict motion.
- Logcat tag: `CrewCar`, scope `TOOL`; logs include function name, ID suffix, result status, dedupe flag and execution time. Never write API Keys or private camera/audio content to logs.

## Read-only smoke test without car

1. Build and install APK; connect the phone to normal Wi-Fi or cellular data and ensure Gemini key/model works.
2. Keep the safety checkbox **unchecked**, select any adapter, and click **測試 AI 查詢工具（不移動）**.
3. Observe logs with `adb logcat -v time -s CrewCar:D AndroidRuntime:E`.
4. Expect tool calls such as `get_car_status`, `get_camera_status`, `get_network_status`. The model decides whether to call them; no tool invocation is guaranteed by prompt wording.
5. Expect `safety_armed:false`, `camera.fresh:false` without a camera, `wifi_confirmed_as_car:false`. Wi-Fi transport availability is not car identification.
6. Ask to move forward for 300 ms **while disarmed**; expect `blocked: manual safety lock` and no motor movement command.
7. Ask for 2000ms/unknown direction; expect policy-level `blocked` before the Activity drive callback.
8. Ask for `stop_car`, then `lock_car`; confirm toolResponse and no motion, even without a car.
9. Confirm no credentials or raw audio/camera data appear in diagnostic exports.

## Real hardware acceptance criteria

- Confirm exact model (WLtoys 6405/6401 or otherwise), control packets, STOP byte sequence, and camera stream protocol.
- With wheels OFF THE GROUND, run manual directional controls and confirm physical STOP.
- Only after manual verified movement, confirm Android Live video + Gemini over mobile data simultaneously.
- After a known camera frame, arm and run ONE model-requested 100–300ms pulse with physical emergency access; inspect `TOOL`, `TX`, frame age, and STOP logs.
- Retry with stale camera, manual override, STOP in the same batch, and Wi-Fi loss to verify blocks.
- The system currently has **no** independent obstacle sensor, odometry, or remote-kill guarantee if radio reception fails.

## Source and validation

API: [Gemini Live WebSocket reference](https://ai.google.dev/api/live) and [Live tool use](https://ai.google.dev/gemini-api/docs/live-api/tools). Source modules: `GeminiLive.java`, `GeminiCarTools.java`, `CarToolPolicy.java`, `MainActivity.java`, `CarControl.java`. Unit tests: `CarToolPolicyTest.java`.

The source may be compiled via `gradle :app:testDebugUnitTest :app:assembleDebug` once the Android SDK / Gradle toolchain is available. Do not claim device validation from a successful compile.
