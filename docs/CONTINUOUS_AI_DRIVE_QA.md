# Crew Car v0.1.34 — Continuous AI driving acceptance plan

## Behavior

- An explicit human instruction such as 「前進」、「持續前進」、「朝門口開」starts **one Android-owned AI Drive Session of at most 30 seconds**, not repeated five-second hops.
- A local motor ticker continually sends the last commanded axes while the same session is valid. Gemini watches the FPV stream (at most one cloud JPEG per second) and receives scheduled, non-overlapping visual-review requests (first at about 0.7 seconds, thereafter at least 2.5 seconds apart).
- On **every** review, Gemini should invoke `adjust_ai_drive_session` using the exact session ID and chosen `steering` left/right/center/keep, `speed` slow/normal/fast/keep, and `motion` keep/forward/reverse/stop. Even if no change is necessary, `keep/keep/keep` renews *control feedback freshness* but **never extends the absolute session deadline**.
- A left/right correction lasts only 900ms before the steering automatically recenters; forward or reverse throttle continues during steering correction. A new correction can change heading before the 900ms limit.
- Android STOPs if **8 seconds pass without a valid Gemini control update**, at the **30-second absolute deadline**, after manual takeover or user STOP, when FPV data is stale, when Live / app state becomes unavailable, or when the car Wi-Fi vanishes. The model cannot restart another session after expiry without a new explicit user command.
- The ordinary 「前進一點」 stays a 500ms movement. Other one-shot motions are unchanged.
- Gain levels remain 70/85/100% OEM control-byte amplitude (new sessions start at 85%, capped by user tuning), not measured ground speed. No GPS, motor ACK, obstacle detection, distance sensor or autonomous navigation proof.

## Unit/CI validation

- `AiDriveSessionPolicyTest`: verifies session **does not stop at 5s** when it has recent updates; remains live through 29.999s; stops at 30s even if feedback is fresh; stops at 8s on missing feedback. 
- `DriveReviewGateTest`: simulates 12 sequential reviews over one 30s session ID and verifies no overlapping cloud review.
- `CarStopStatusTest`: distinguishes 30s normal expiry and an 8s missing-model-update timeout.
- Android unit tests + debug APK build in CI.

## Hardware validation (NOT proven by unit tests)

Test with the toy car's wheels raised first, then on an obstacle-free private floor with a person holding the accessible physical STOP / power disconnect:

1. Speak 「朝門口開」 and confirm the motor stream runs through the 5-second mark with the same `ai_drive_session_id`, if Gemini keeps updating.
2. In a single run, watch AI send left then right then center, verifying that steering recenter happens without restarting the motor stream or opening a new session.
3. Force `motion=reverse` during an active session and verify reverse motor bytes, then `motion=stop`; no new `start_ai_drive_session` call should be necessary.
4. Stop Gemini responses: the local 8s feedback watchdog must send neutral STOP, even if fresh JPEG frames keep arriving.
5. Lose FPV, Gemini Live, Wi-Fi, app foreground, or tap manual steering / E-STOP; each must override the session with a STOP.
6. Let continuous feedback remain fresh for the entire session: neutral STOP must occur at 30s, no automatic restart.
7. Verify `[AI_REVIEW]`, `[AI_SESSION]`, `[SAFETY]` and `[TX]` logs. `queued` means Android submitted a motor command, **not** a hardware ACK or confirmed movement.

See previous v0.1.33 stability diagnostics for JPEG and network details.
