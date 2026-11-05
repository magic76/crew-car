# Crew Car v0.1.35 — Dedicated Car Wi-Fi (Android)

## First-time setup

1. Turn on the FPV toy car and mobile data. On the driving screen, tap **車 Wi-Fi** status, or open **設定 → 車輛與連線 → 固定／重連車子 Wi-Fi**.
2. Enter the car's **exact SSID** as shown in Android Wi-Fi settings. For an open RC car AP, **leave the password empty**. If using WPA2, enter its 8–63 character passphrase. The App **does not persist the passphrase**.
3. Grant Android's **Nearby Wi-Fi Devices** runtime permission (Android 13+) or **Location** permission (Android 10–12) when asked. Approve the OS Wi-Fi connection selector. Android controls whether a connection is possible; the App cannot bypass consent.
4. Check the driving HUD displays **車 Wi-Fi 已固定**. The App then binds both the RC car motor UDP socket and the FPV UDP/HTTP camera route to this **selected Android Network**, not the default internet network. Gemini Live remains on **validated cellular data**.
5. Do not assume auto-arming or auto-driving. After any interruption, use the physical manual controls or a new explicit Gemini command.

## Behavior and safety

- After SSID pinning (including restoring saved SSID after restarting the App), `CarNetwork.wifi()` is **null until Android delivers the matching network**; it cannot silently substitute another nearby Wi-Fi hotspot. The SSID preference alone does not imply a live connection. On app relaunch press **重連** to request Android again. Network permission approval is ultimately controlled by the OS.
- The App retains its active `NetworkCallback` while in use and requests a **best-effort Wi-Fi low-latency lock** while foreground with an active selected network. This is *not* a permanent radio lock: Android may still drop the link, the device may have weak range or low battery, and the device may not support concurrent Wi-Fi.
- On `onLost`, the pinned `Network` immediately becomes unavailable. The App clears the motor socket, **STOPs and disarms**, closes the camera stream and waits for the user to manually resume after recovery. Reacquiring the Network never resumes throttle automatically.
- `onUnavailable` and a 30-second OS connection request timeout display actionable status, while still **failing closed** rather than falling back to another AP.
- Tapping **取消車輛專用模式** explicitly removes the pin, stops and disarms the car, and restores the old general Wi-Fi discovery mode (without SSID verification).
- Android < 10 cannot use WifiNetworkSpecifier; App instead opens the Wi-Fi settings page for manual connection. Other unspecified APs are not automatically verified as the car.
- Android 13+ permission `NEARBY_WIFI_DEVICES` is for connecting to the known car SSID, not deriving location. No device location is collected by this feature.
- No passwords are saved to SharedPreferences or Log. Only the user's chosen car SSID is remembered.

## Field QA (hardware / Android required)

1. Clean install: tap the network status, enter **exact car SSID**, approve Android's picker, check **車 Wi-Fi 已固定**. Camera UDP begins to receive frames; motor does not move until commanded.
2. With offline car Wi-Fi + valid 4G/5G, start Gemini Live and verify status `internet_separate_from_wifi=true`, `wifi_confirmed_as_car=true`.
3. Turn the car off / leave range. Confirm `[CAR_WIFI]` **onLost**, STOP + manual disarm, camera stopped, red network HUD. Even if another home Wi-Fi is present, the App does **not** select it for the car.
4. Re-power the car and tap the status to reconnect if the OS has not re-established it. Camera may resume, but motors **must not** resume without human input.
5. Enter a wrong SSID or WPA2 passphrase; verify OS fails or times out with no fallback.
6. Exit and reopen App. Saved SSID remains, but the dedicated car network is **not treated as connected** until a new Android network is delivered. Password (if any) must be entered again.
7. Test Samsung Wi-Fi/mobile-data switching while Live runs. A Gemini websocket failure must remain distinct from car Wi-Fi disappearance.
8. Verify manual STOP during an AI drive still preempts immediately; low-latency WifiLock releases onPause and onDestroy.

This cannot prevent OS-level Wi-Fi failure, weak RF signal, interference or power drop. CI validates source, unit tests and APK only. It does not prove the Android OS picker or car hardware is compatible until on-device validation.
