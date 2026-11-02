# KL FPV handoff — capture and implement

## State
User wants one Crew Car app for WL Tech FPV CAR + KL FPV (OEM package com.yls.klfpv), selecting one Wi-Fi toy at a time. This PR only adds vehicle selection, independent settings, and a fail-closed KL integration point; KL driving and video are NOT implemented or verified. WL protocol remains unchanged.

## Local agent capture on the owner's hardware
1. Raise all wheels, keep the physical power switch accessible, verify the OEM KL FPV app drives the actual car and previews video.
2. Record car model/label, firmware if visible, OEM app version, Wi-Fi SSID prefix (redacted), Android version, car IP and observed ports.
3. Capture original app traffic via PCAPdroid or another permitted packet-capture method (VPN capture may not see all LAN packets).
4. Separate traces: idle 5s, forward 1s, release/stop 2s, reverse 1s, left, right, forward+left, forward+right, camera start/stop/reconnect, Wi-Fi loss.
5. For each action record transport, destination, sample hex, cadence, handshake, heartbeat, checksum/sequence behavior and actual physical response. TX does NOT prove a vehicle ACK.
6. Identify video transport, init packets, codec, frame boundaries, fragmentation and timestamps. Do not assume WL H.265 or RTSP.
7. Save sanitized evidence to docs/KL_FPV_PROTOCOL.md; never commit raw PCAP, APK, credentials, keys, private images or other sensitive content.

Evidence record format:
model / app version / SSID prefix / network
idle / forward / release_stop / backward / left / right / forward_left / forward_right: sample hex, timing, count
handshake / heartbeat / sequence / checksum
video transport / codec / initialization / framing
unknowns and confirmed physical test results

## Code entry points
- CarProfile.java: stable indices, per-vehicle preference keys, safe KL blank defaults.
- MainActivity.java: selection, stop/disarm, camera disabled for KL, separate settings, Gemini status.
- CarControl.java: KL arming and ALL KL TX fail closed; existing WL/AI hold path remains intact.
- KlFpvProtocol.java: intentionally unimplemented encoder. Implement only after evidence and tests.
- Add KL camera adapter separately; do not reuse WL 6405 stream on assumptions.

## Gate to enable KL
- Capture OEM mapping, handshake, periodic idle packet, genuine STOP.
- Test frame builder including combined axes, neutral, checksum, bad values.
- Verify 100-300ms manual pulses with lifted wheels, STOP on release, app pause, Wi-Fi loss, model switch, watchdog, E-STOP. Radio loss may prevent STOP receipt.
- Verify actual decoded frames and timestamp freshness.
- Verify Gemini over cellular and existing WL regressions.
- Build/test with gradle :app:testDebugUnitTest :app:assembleDebug and inspect ADB logs. This change has not been APK-built or hardware-tested.

Expected scaffold: select KL and see it in banner, blank IP/port, no KL movement nor camera packets; switch to WL and prior config returns.