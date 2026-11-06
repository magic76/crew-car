# Crew Car — UDP 1234 camera return path through a phone hotspot

## What changed

- The WL 6405 H.265 video receiver binds to local UDP **1234** as before.
- Camera START (UDP destination `172.16.11.1:23459`) now **sends from that same Wi-Fi-bound UDP 1234 socket**, instead of a second ephemeral-port socket.
- Camera STOP uses the same socket, before closing it; explicit stop sends once and shutdown suppresses duplicates.
- The existing motor control path, decoder, SSID pinning, Gemini data route, and fail-closed drive logic are unchanged.
- Logs include START source port and **first received camera packet source IP/port**.
- If no packets arrive, camera status points out possible hotspot NAT filtering.

This is a **best-effort UDP NAT mapping experiment**, not a universal hole-punch or a confirmed fix. An Android hotspot may remap port 1234, filter traffic from a different remote port (video may originate from a port other than camera commands), or block unsolicited return video. In those cases, a transparent Wi-Fi bridge or an explicit video relay on the intermediary remains necessary.

## Field QA (wheels off the ground, manual STOP available)

1. **Baseline:** connect the controlling phone straight to the car Wi-Fi. Start camera. Confirm a picture and `HEVC first video UDP from=...` and packet counts >0. Verify manual STOP.
2. **Hotspot:** intermediary phone connects to car Wi-Fi and shares its Wi-Fi to the controlling phone. Confirm Crew Car motor control still works. Confirm the controlling phone is using the hotspot network (not the car's original SSID).
3. In the controlling phone App, select the hotspot SSID if using dedicated SSID mode, or explicitly cancel the old direct-car SSID pin before using generic Wi-Fi. Start camera.
4. Check `HEVC camera START ... srcPort=1234 rxPort=1234` and camera packet count. If `first video UDP` appears, verify reconstructed frames and smoothness. If count stays zero, the hotspot may be blocking/remapping the stream; **do not treat it as a decoder failure**.
5. Tap **重連鏡頭** multiple times, turn camera off, leave the hotspot range, and reconnect. Check no socket bind conflict (`Address already in use`), stale callbacks or endless camera restarts. Motor must never auto-arm.
6. If there are packets but no image, examine `frames / decoded / dropped` and HEVC decode errors separately from network transport.
7. Turn the toy car off while any movement is enabled; verify app disarms and STOP is shown. **A UDP STOP has no hardware acknowledgement.** Keep the physical power switch accessible.
8. Confirm cellular Gemini still works independently, without routing cloud traffic to the car's offline Wi-Fi.

## Limitations

The same-socket change is scoped to the WL 6405 HEVC adapter. It does not install software on the intermediary phone, open ports on Android's hotspot, or implement generic inbound port forwarding. Testing can establish whether this particular phone/firmware NAT happens to support it; absence of packets requires inspecting source/destination addresses with a packet capture or building an explicit relay.
