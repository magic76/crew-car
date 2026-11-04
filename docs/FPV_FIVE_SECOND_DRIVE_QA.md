# Crew Car v0.1.31 — Five-second FPV-controlled driving

## Changed behavior

- Physical driving pad is always available on a connected WL car driving screen. A new manual touch attempts to arm the controller after checking Wi-Fi / endpoint and **preempts an AI run**. It no longer relies on a hidden checkbox being checked. A rebuilt HEVC TextureView requests STOP instead of silently revoking manual ownership.
- Normal 「前進」 and 「持續前進」 use the **same one 5-second** locally sustained AI window, rather than isolated 900ms/2s command pulses. `start_ai_drive_session(goal)` also lasts 5s. No automatic restart after expiry.
- During the SAME five-second window, Gemini may call `adjust_ai_drive_session(session_id, steering, speed, motion)` at any time. `steering` is left/right/center/keep (left/right expires to center in 900ms); `speed` is slow/normal/fast/keep gain (NOT km/h); `motion` is keep/forward/reverse/stop. `reverse` changes the throttle sign while keeping motor control ownership; `stop` requests immediate neutral.
- Local Android continues to send motor packets; it does **not** wait for a response from the car. The WL packet transport does not provide a device ACK. Tool replies therefore report Android dispatch and direct Gemini to infer apparent movement from **successive camera frames**, never from imagined acknowledgements.
- Periodic FPV tool prompts start around 700ms into a session, then no faster than 1.5s apart when new FPV is queued. Explicit doorway guidance: find the center of the opening between doorposts; steer toward its lateral offset relative to image center, then compare the next frame. A view of a wall is not automatically a reason to STOP; an imminent collision, explicit stop or camera/Live/Wi-Fi failure is.
- Gemini Live sends JPEG at max 1 frame/s, longest edge ≤448px and quality 60 (previously 512px, q68). VIDEO logs include encoded bytes, encode duration and WebSocket queue bytes. This **cannot guarantee** quicker model inference; it reduces encode/network payload.

## Required QA

1. Start with wheels lifted. Tap forward manually while AI disconnected: motor must arm and run. Release: both axes STOP. Confirm UI no longer falsely displays locked after AI automatic arm.
2. Start a 5-second AI session. Verify TX continuously sends `ai-session:0:1:50`; STOP occurs at or before five seconds even with late tool updates. Next session needs a new explicit user request.
3. During one session send `adjust_ai_drive_session` left then right then center; verify changes to the steering packet without neutral throttle gaps. After 900ms heading recenters if not replaced.
4. During a running window change `motion` to reverse, forward, keep, stop; verify throttle byte sign changes while respecting the same deadline; STOP always works even with stale FPV.
5. Start AI then tap manual joystick: manual input wins with its own up-to-date command; AI queued updates from the previous motor epoch cannot resume the car.
6. Rebuild the camera preview while parked: controller stays available for manual touch; active AI motor run requests STOP.
7. Inspect Gemini transcript: it should describe only uncertain FPV scene changes, never wait for or invent a car ACK.
8. Check VIDEO logs for actual JPEG bytes, encode duration and queue backlog. If images are too blurred to locate the doorposts, change size/quality upward after collecting actual traces.
9. Verify app background, physical STOP button, Live disconnect and Wi-Fi loss still request STOP. No claim of device-reported position/speed, depth sensing or physical obstacle avoidance.

## CI

Android unit tests and debug APK build. Hardware/vision feedback is **not** proven by CI. Conduct tests in a cleared private area and keep the physical STOP accessible.
