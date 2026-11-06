# Camera hotspot field check: command reply is not FPV video

Context: on an Android phone Wi-Fi sharing relay, the user can control the WL Tech car, but received only one UDP datagram after each camera START:

```
[HEVC] camera START sent target=172.16.11.1:23459 srcPort=1234
[HEVC] first video UDP from=172.16.11.1:23459 toLocalPort=1234
[HEVC] rx packets=1 frames=0 decoded=0
[HEVC] camera STOP during shutdown failed NetworkOnMainThreadException
```

The UDP source **23459** is the camera command port. A single response from this port after START is *consistent with* a command acknowledgement, **not evidence of an ongoing video stream**.

## Diagnostic changes

- Report command replies and WL-format video fragments separately.
- Report invalid/non-video datagrams separately, so `rx packets=1` no longer implies there is FPV data.
- For the first 3 datagrams, log source IP/port and datagram byte count, no video payload.
- Route camera STOP through the receive worker; avoid network sends on the Android UI thread. The bounded fallback runs on its own worker too.
- No motor, Gemini, camera decoder or Wi-Fi pinning changes.

## Physical QA (camera only, wheels raised, vehicle disarmed)

1. Connect control phone directly to the car AP. Request camera and verify `video > 0`, `frames > 0`, and decoded picture.
2. Connect an intermediary Android hotspot that is itself on the car Wi-Fi, and control phone to the intermediary. Keep motor locked, then request camera.
3. Read `rx packets`, `video`, `cameraReplies`, `invalid`, and source-port+bytes lines.
4. If `cameraReplies=1, video=0` repeats, camera command response passed NAT, **but video was not shown to cross**. This is a network/return-route problem, not an H.265 decoder issue.
5. If `video > 0` and `frames=0`, check drops and fragment completeness; if `frames > 0, decoded=0`, inspect MediaCodec.
6. Reconnect and stop repeatedly. Verify no `NetworkOnMainThreadException`, no bind conflicts, and physical STOP/lock remain effective.

## Next architecture if NAT cannot forward FPV

- Use a true L2 transparent wireless repeater/bridge verified with the specific video protocol; or
- Put an explicit raw UDP 1234 relay on the intermediary Android phone (its Wi-Fi-sharing stack alone does not grant general inbound UDP forwarding).

No source-code-only change on the controlling phone can guarantee an unsolicited UDP stream will cross the Android hotspot NAT.
