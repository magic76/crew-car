# Crew Car v0.1.10 — Wi-Fi car + 4G/5G Gemini Live

## Architecture

The car acts as an **offline Wi-Fi access point**, typically at 172.16.11.1. The phone may remain associated to this Wi-Fi while Android chooses a different default Internet route.

Crew Car now explicitly requests a cellular network from Android when the car Wi-Fi is not Internet-validated. This fixes the case where \`getAllNetworks()\` did **not** expose a cellular network because the system had not activated that radio while Wi-Fi was connected.

| Traffic | Selected route | Transport |
| --- | --- | --- |
| Car motor TX | \`CarNetwork.wifi()\`, Wi-Fi Network.bindSocket | UDP 172.16.11.1:23458 |
| Car camera RX / control | \`CarNetwork.wifi()\` | UDP video:1234, command:23459 |
| Gemini Live WebSocket + mic/video/tool results | validated \`TRANSPORT_CELLULAR\` Network | TLS WebSocket; OkHttp \`network.getSocketFactory()\` + network-specific DNS |
| Read-only Gemini smoke test away from car | validated ordinary Internet Wi-Fi or mobile data | TLS WebSocket |

No \`bindProcessToNetwork()\`: that would route **both** the camera and cloud traffic together and could break one side.

## Android implementation

- Declare \`android.permission.CHANGE_NETWORK_STATE\` and keep \`INTERNET\` / \`ACCESS_NETWORK_STATE\`.
- Request \`TRANSPORT_CELLULAR\` + \`NET_CAPABILITY_INTERNET\` with \`ConnectivityManager.requestNetwork(NetworkRequest, NetworkCallback, mainHandler)\`.
- Do **not** request mutable \`NET_CAPABILITY_VALIDATED\` in the NetworkRequest. Wait until \`onCapabilitiesChanged\` reports \`VALIDATED\`; then pass that specific Network into the Gemini Live socket and DNS.
- Show "正在準備行動數據" during the request, and fail with a useful message after 18 seconds without a validated cellular network.
- Keep this cellular request alive while the Live session is active; unregister on Live disconnect/failure/Activity destroy. No redundant requests are allowed.
- Never log the Gemini Key or the complete URL containing it. Key stays transient in memory.

Official Android documentation: https://developer.android.com/reference/android/net/ConnectivityManager

## Samsung Galaxy S25 Ultra test

1. Turn **mobile data ON**, with an active SIM/eSIM and carrier coverage. Confirm it works when Wi-Fi is off.
2. Settings → Connections → Data usage → **Allowed networks for apps**. Crew Car should be allowed to use **both** Wi-Fi and mobile data. Do **not** mark the whole Crew Car app "mobile data only", as the physical car control depends on local Wi-Fi.
3. Connect to the car's Wi-Fi. If Android warns "No Internet", **keep the Wi-Fi connection**.
4. Open Crew Car → settings → Gemini API Key → **連線 Live**. The app should show "正在準備行動數據（小車 Wi-Fi 保持連線）", then "Gemini WebSocket 連線中", then "Gemini Live 已連線" (assuming credentials/model availability).
5. With wheels raised and manual stop verified, confirm the car remains controllable during a live Gemini audio session. Verify camera and Gemini video are not silently blocked.
6. Inspect \`adb logcat -v time -s CrewCar:D AndroidRuntime:E\`. Expected scopes: \`[NET] requested CELLULAR + INTERNET\`, \`[NET] validated CELLULAR route ready\`, \`[LIVE] validated cellular route selected\`. Do **not** share API keys or raw microphone/video bytes.
7. Tap **斷線 Live**, repeat connection, and confirm old callbacks are unregistered. Open/close settings and background/resume without leaks or re-arming.
8. When mobile data is disabled or carrier signal is absent, verify a bounded 18-second failure and continued manual car control over Wi-Fi.
9. Test Gemini using ordinary Internet Wi-Fi without the car: it must not require a cellular request.

**Important:** Samsung Intelligent Wi-Fi "Switch to mobile data" is a system-wide fallback feature; it might disconnect the car Wi-Fi. This app intentionally requests a separate cellular route rather than asking Android to switch away from the car Wi-Fi.

## If cellular still cannot connect

Check data plan/SIM, Data Saver, battery restrictions, Wi-Fi "keep connected" choice, VPN/firewall settings, and carrier reachability. Android still controls the cellular radio and may refuse simultaneous routes on some device/carrier configurations. The app cannot override all system/OEM policies.

A Termux server on the **same phone** does not create Internet connectivity. A second phone/hotspot with a dedicated bridge/router is an alternative if Android or carrier restrictions prevent simultaneous paths, but it adds hardware and routing complexity. For Gemini cloud Live, either the current phone or the external gateway must have a working Internet uplink.

## Safety

No motor ACK/odometry/obstacle sensor exists. The camera is the only vehicle sensor. Keep physical STOP and manual lock; never conflate cellular availability, WebSocket setup, queued motion, or TX status with verified movement. The new cellular-routing logic must not change the physical car motor packet format or timing.
