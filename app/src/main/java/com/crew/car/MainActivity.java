package com.crew.car;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.view.SurfaceView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int MIC_PERMISSION_REQUEST = 101;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, EditText> packets = new LinkedHashMap<>();
    private CarLog log;
    private CarNetwork routes;
    private CarControl control;
    private CameraStream camera;
    private WltoysVideoStream wltoysVideo;
    private GeminiLive gemini;

    private EditText host, port, cameraUrl, key, prompt;
    private Spinner transport, encoding, profile, cameraAdapter;
    private TextView netStatus, cameraStatus, liveStatus, transcript, logs;
    private ImageView preview;
    private SurfaceView hevcPreview;
    private CheckBox arm;
    private volatile long lastCameraFrame;
    private volatile int lastFrameWidth;
    private volatile int lastFrameHeight;
    private volatile int currentCameraAdapter;
    private boolean changingConfig;
    private Runnable repeatedMotion;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        log = new CarLog();
        routes = new CarNetwork(this, log);
        control = new CarControl(routes, log);
        camera = new CameraStream(routes, log);
        wltoysVideo = new WltoysVideoStream(routes, log);
        gemini = new GeminiLive(log);
        render();
        log.setListener(s -> runOnUiThread(() -> { if (logs != null) logs.setText(s); }));
        log.i("APP", "Crew Car v0.1; hardware protocol NOT verified");
        routes.diagnostics();
    }

    private int dp(float v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    private TextView label(LinearLayout parent, String text, int size) {
        TextView item = new TextView(this);
        item.setText(text);
        item.setTextColor(Color.rgb(35, 45, 56));
        item.setTextSize(size);
        item.setPadding(0, dp(6), 0, dp(5));
        parent.addView(item);
        return item;
    }

    private EditText field(LinearLayout parent, String hint, String value, boolean number) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setHint(hint);
        e.setText(value);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        parent.addView(e, new LinearLayout.LayoutParams(-1, dp(46)));
        return e;
    }

    private Button button(String title, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private void row(LinearLayout parent, View... children) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        for (View v : children) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            r.addView(v, lp);
        }
        parent.addView(r);
    }

    private String saved(String name, String fallback) {
        return getPreferences(MODE_PRIVATE).getString(name, fallback);
    }

    private void render() {
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(15), dp(16), dp(32));
        root.setBackgroundColor(Color.rgb(248, 250, 252));
        scroller.addView(root);
        setContentView(scroller);

        TextView heading = label(root, "Crew Car  |  FPV × Gemini Live", 23);
        heading.setTypeface(null, Typeface.BOLD);
        label(root, "先連原廠小車 Wi-Fi；控制指令與攝影機位址需先實機確認。", 13);
        netStatus = label(root, "網路待診斷", 13);
        row(root, button("開 Wi-Fi 設定", v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS))),
                button("網路診斷", v -> {
                    routes.diagnostics();
                    netStatus.setText("Car Wi-Fi=" + (routes.wifi() != null)
                            + "  Gemini internet=" + (routes.internet(routes.wifi()) != null));
                }));

        label(root, "1. FPV 攝影機（WLtoys UDP H.265 / HTTP）", 17).setTypeface(null, Typeface.BOLD);
        cameraAdapter = new Spinner(this);
        cameraAdapter.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"WLtoys 6405 UDP H.265（候選協定）", "HTTP JPEG/MJPEG（自訂）"}));
        cameraAdapter.setSelection(getPreferences(MODE_PRIVATE).getInt("camera_adapter", 0));
        currentCameraAdapter = cameraAdapter.getSelectedItemPosition();
        root.addView(cameraAdapter);
        cameraUrl = field(root, "HTTP 模式才需填影像 URL",
                saved("camera_url", ""), false);
        cameraStatus = label(root, "鏡頭未連線", 13);
        preview = new ImageView(this);
        preview.setBackgroundColor(Color.rgb(25, 33, 43));
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setMinimumHeight(dp(210));
        root.addView(preview, new LinearLayout.LayoutParams(-1, dp(220)));
        hevcPreview = new SurfaceView(this);
        hevcPreview.setBackgroundColor(Color.rgb(25, 33, 43));
        root.addView(hevcPreview, new LinearLayout.LayoutParams(-1, dp(220)));
        preview.setVisibility(cameraAdapter.getSelectedItemPosition() == 0 ? View.GONE : View.VISIBLE);
        hevcPreview.setVisibility(cameraAdapter.getSelectedItemPosition() == 0 ? View.VISIBLE : View.GONE);
        cameraAdapter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                camera.stop();
                wltoysVideo.stop();
                currentCameraAdapter = position;
                lastCameraFrame = 0;
                lastFrameWidth = 0;
                lastFrameHeight = 0;
                control.disarm();
                if (arm != null) uncheck();
                preview.setVisibility(position == 0 ? View.GONE : View.VISIBLE);
                hevcPreview.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        row(root, button("連線攝影機", v -> startCamera()),
                button("停止影像", v -> {
                    camera.stop();
                    wltoysVideo.stop();
                    lastCameraFrame = 0;
                    cameraStatus.setText("影像停止");
                    control.disarm();
                    arm.setChecked(false);
                }));

        label(root, "2. 小車控制（封包需從原廠 App 實測）", 17).setTypeface(null, Typeface.BOLD);
        profile = new Spinner(this);
        profile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"自訂封包（預設上鎖）", "WLtoys 6405/6401 公開候選協定（實車確認後使用）"}));
        profile.setSelection(getPreferences(MODE_PRIVATE).getInt("protocol_profile", 0));
        root.addView(profile);
        label(root, "6405 模式：UDP 23458、16-byte 控制封包、每 100ms 心跳。不同車型不可直接假設相容。", 12);
        profile.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                control.disarm();
                if (arm != null) uncheck();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        host = field(root, "控制端 IP", saved("host", "172.16.11.1"), false);
        port = field(root, "自訂模式控制 Port（6405 模式固定 23458）", saved("port", ""), true);
        transport = new Spinner(this);
        transport.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"UDP", "TCP"}));
        transport.setSelection(getPreferences(MODE_PRIVATE).getInt("transport", 0));
        encoding = new Spinner(this);
        encoding.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"HEX bytes", "UTF-8 text"}));
        encoding.setSelection(getPreferences(MODE_PRIVATE).getInt("encoding", 0));
        row(root, transport, encoding);
        for (String d : new String[]{"forward","backward","left","right","stop"}) {
            packets.put(d, field(root, d + " 封包（空白 = 禁止解鎖）", saved("packet_" + d, ""), false));
        }
        row(root, button("套用連線設定", v -> applyConfig()), button("立即停止", v -> {
            control.emergencyStop();
            log.i("UI", "emergency stop tapped");
        }));

        arm = new CheckBox(this);
        arm.setText("解鎖手動控制／AI 短距離指令（先清空周圍，確認車輪懸空測試）");
        arm.setTextSize(13);
        root.addView(arm);
        arm.setOnCheckedChangeListener((view, checked) -> {
            if (changingConfig) return;
            if (checked) {
                if (!applyConfig()) { uncheck(); return; }
                String issue = control.arm();
                if (issue != null) {
                    log.e("SAFETY", "arm denied: " + issue);
                    uncheck();
                }
            } else control.disarm();
        });
        label(root, "按住移動，放開即 STOP。斷訊 / 逾時也會嘗試送出 STOP；未收到車輛 ACK。", 12);

        row(root, button("急停 STOP", v -> control.emergencyStop()),
                held("▲ 前進", "forward"),
                button("重新診斷", v -> routes.diagnostics()));
        row(root, held("◀ 左轉", "left"),
                held("▼ 後退", "backward"),
                held("▶ 右轉", "right"));

        label(root, "3. Gemini 3.8 Live（手機麥克風 + 車頭畫面）", 17).setTypeface(null, Typeface.BOLD);
        label(root, "使用自己的 API Key；只放在記憶體，不寫入設定與 Log。連小車 Wi-Fi 時請保持行動數據。", 12);
        key = field(root, "Gemini API Key（每次連線重新輸入）", "", false);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        liveStatus = label(root, "Gemini 尚未連線", 13);
        row(root, button("連線 Live", v -> startGemini()),
                button("斷線 Live", v -> {
                    gemini.disconnect();
                    control.emergencyStop();
                    liveStatus.setText("Gemini 已手動斷線");
                }));
        row(root, button("開啟麥克風", v -> startMic()), button("關閉麥克風", v -> gemini.stopMic()));
        prompt = field(root, "測試文字，例如：請描述鏡頭畫面（不要求移動）", "", false);
        root.addView(button("送出文字給 Gemini", v -> {
            if (!gemini.sendText(prompt.getText().toString())) log.e("LIVE", "請先連線 Gemini");
            else log.i("LIVE", "text turn sent (content redacted)");
        }), new LinearLayout.LayoutParams(-1, dp(50)));
        label(root, "Gemini 可使用：drive_car、stop_car、lock_car、get_car_status、get_camera_status、get_network_status。查車況不需要解鎖。", 12);
        root.addView(button("測試 AI 查詢工具（不移動）", v -> {
            if (!gemini.sendText("請先呼叫 get_car_status、get_camera_status、get_network_status，然後用繁體中文告訴我連線與安全狀態，不要移動小車。")) {
                log.e("TOOL", "Live disconnected; cannot request tool smoke test");
            } else log.i("TOOL", "read-only smoke test requested");
        }), new LinearLayout.LayoutParams(-1, dp(50)));
        transcript = label(root, "對話顯示區（僅保留最新訊息）", 13);
        transcript.setMinHeight(dp(50));

        label(root, "4. Debug（adb logcat -s CrewCar:D）", 17).setTypeface(null, Typeface.BOLD);
        row(root, button("匯出 Debug 文字", v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, log.snapshot());
            startActivity(Intent.createChooser(send, "分享 Crew Car Debug"));
        }), button("重新檢查網路", v -> routes.diagnostics()));
        logs = label(root, "", 11);
        logs.setTextIsSelectable(true);
        netStatus.setText("Car Wi-Fi=" + (routes.wifi() != null) +
                "  Gemini internet=" + (routes.internet(routes.wifi()) != null));
    }

    private void uncheck() {
        changingConfig = true;
        arm.setChecked(false);
        changingConfig = false;
        control.disarm();
    }

    private boolean applyConfig() {
        try {
            CarControl.Config cfg = new CarControl.Config();
            cfg.host = host.getText().toString().trim();
            cfg.wltoys = profile.getSelectedItemPosition() == 1;
            cfg.port = cfg.wltoys ? WltoysProtocol.CONTROL_PORT :
                    Integer.parseInt(port.getText().toString().trim());
            cfg.tcp = !cfg.wltoys && transport.getSelectedItemPosition() == 1;
            cfg.hex = encoding.getSelectedItemPosition() == 0;
            for (Map.Entry<String, EditText> e : packets.entrySet()) {
                String value = e.getValue().getText().toString().trim();
                cfg.payloads.put(e.getKey(), value);
                getPreferences(MODE_PRIVATE).edit().putString("packet_" + e.getKey(), value).apply();
            }
            getPreferences(MODE_PRIVATE).edit()
                    .putString("host", cfg.host).putString("port", String.valueOf(cfg.port))
                    .putString("camera_url", cameraUrl.getText().toString().trim())
                    .putInt("transport", transport.getSelectedItemPosition())
                    .putInt("encoding", encoding.getSelectedItemPosition())
                    .putInt("protocol_profile", profile.getSelectedItemPosition())
                    .putInt("camera_adapter", cameraAdapter.getSelectedItemPosition())
                    .apply();
            control.configure(cfg);
            log.i("UI", "control config applied, safety lock ON");
            return true;
        } catch (Exception e) {
            log.e("UI", "config invalid: " + e.getMessage());
            return false;
        }
    }

    private void startCamera() {
        camera.stop();
        wltoysVideo.stop();
        String url = cameraUrl.getText().toString().trim();
        getPreferences(MODE_PRIVATE).edit().putString("camera_url", url)
                .putInt("camera_adapter", cameraAdapter.getSelectedItemPosition()).apply();
        lastCameraFrame = 0;
        lastFrameWidth = 0;
        lastFrameHeight = 0;
        currentCameraAdapter = cameraAdapter.getSelectedItemPosition();
        cameraStatus.setText("影像連線中…");
        if (cameraAdapter.getSelectedItemPosition() == 0) {
            String destination = host.getText().toString().trim();
            wltoysVideo.start(destination, hevcPreview, frame -> {
                lastFrameWidth = frame.getWidth();
                lastFrameHeight = frame.getHeight();
                lastCameraFrame = SystemClock.elapsedRealtime();
                gemini.frame(frame);
                if (!isFinishing()) cameraStatus.setText("H.265 解碼影格 " +
                        frame.getWidth() + "×" + frame.getHeight());
            });
        } else {
            camera.start(url, frame -> {
                lastFrameWidth = frame.getWidth();
                lastFrameHeight = frame.getHeight();
                lastCameraFrame = SystemClock.elapsedRealtime();
                gemini.frame(frame);
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        preview.setImageBitmap(frame);
                        cameraStatus.setText("JPEG 影格 " + frame.getWidth() + "×" +
                                frame.getHeight());
                    }
                });
            });
        }
    }

    private Button held(String title, String direction) {
        Button b = button(title, v -> {});
        b.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                view.getParent().requestDisallowInterceptTouchEvent(true);
                if (!control.armed()) { log.e("SAFETY", "manual denied: locked"); return true; }
                control.setManual(true);
                repeatedMotion = new Runnable() {
                    @Override public void run() {
                        if (!control.manual() || !control.armed()) return;
                        control.drive(direction, 480, false);
                        main.postDelayed(this, 180);
                    }
                };
                main.post(repeatedMotion);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                control.setManual(false);
                if (repeatedMotion != null) main.removeCallbacks(repeatedMotion);
                control.emergencyStop();
                return true;
            }
            return true;
        });
        return b;
    }

    private void startGemini() {
        String apiKey = key.getText().toString().trim();
        key.setText(""); // avoid leaving the secret in the view hierarchy
        gemini.connect(apiKey, routes.internet(routes.wifi()), new GeminiLive.Listener() {
            @Override public void onStatus(String status) {
                if (status.contains("失敗") || status.contains("中斷")) control.emergencyStop();
                runOnUiThread(() -> liveStatus.setText(status));
                log.i("LIVE", "state updated"); // no credentials
            }
            @Override public void onStop() {
                control.emergencyStop();
                log.i("SAFETY", "AI stop_car requested (hardware acknowledgement unavailable)");
            }
            @Override public void onLock() {
                control.disarm();
                runOnUiThread(() -> { if (arm != null) uncheck(); });
                log.i("SAFETY", "AI lock_car requested; manual re-arm required");
            }
            @Override public JSONObject onCarStatus() {
                return toolJson(
                        "safety_armed", control.armed(),
                        "manual_override", control.manual(),
                        "active_command", control.activeCommand(),
                        "pulse_remaining_ms", control.remainingMs(),
                        "candidate_wltoys_protocol", control.candidateProtocol(),
                        "last_tx_direction", control.lastTxCommand(),
                        "last_tx_status", control.lastTxStatus(),
                        "last_tx_age_ms", control.lastTxAgeMs(),
                        "hardware_sensor", "camera_only",
                        "distance_sensor_available", false,
                        "odometry_available", false,
                        "battery_telemetry_available", false,
                        "motor_position_known", false,
                        "device_ack_supported", false,
                        "camera_fresh", cameraAgeMs() >= 0 && cameraAgeMs() <= 2500);
            }
            @Override public JSONObject onCameraStatus() {
                long age = cameraAgeMs();
                long liveAge = gemini.liveVideoAgeMs();
                return toolJson(
                        "sensor_type", "camera_only",
                        "adapter", currentCameraAdapter == 0 ? "WLtoys 6405 UDP HEVC candidate" : "HTTP JPEG/MJPEG",
                        "frames_received", lastCameraFrame != 0,
                        "last_frame_age_ms", age,
                        "fresh", age >= 0 && age <= 2500,
                        "width", lastFrameWidth,
                        "height", lastFrameHeight,
                        "live_video_frame_queue_age_ms", liveAge,
                        "live_video_fresh", liveAge >= 0 && liveAge <= 2500,
                        "live_video_frames_queued", gemini.liveVideoFramesQueued(),
                        "scene_recognition_available_here", false,
                        "distance_sensor_available", false,
                        "collision_sensor_available", false,
                        "note", "Camera only: JPEG queued to Live != model received/understood it. No measured depth or obstacle clearance.");
            }
            @Override public JSONObject onNetworkStatus() {
                android.net.Network car = routes.wifi();
                return toolJson(
                        "wifi_transport_present", car != null,
                        "wifi_has_validated_internet", routes.isValidatedInternet(car),
                        "usable_gemini_internet_available", routes.internet(car) != null,
                        "internet_separate_from_wifi", routes.internet(car) != null &&
                                !routes.internet(car).equals(car),
                        "wifi_confirmed_as_car", false,
                        "gemini_live_connected", gemini.ready(),
                        "note", "Wi-Fi presence does not prove the connected SSID belongs to the car; no claim about packet delivery");
            }
            @Override public String onDrive(String direction, int duration) {
                if ("stop".equalsIgnoreCase(direction)) {
                    control.emergencyStop();
                    return "stopped";
                }
                if (!control.armed()) return "blocked: manual safety lock";
                if (SystemClock.elapsedRealtime() - lastCameraFrame > 2500 || lastCameraFrame == 0)
                    return "blocked: camera stale/unavailable";
                if (routes.wifi() == null) return "blocked: car Wi-Fi disconnected";
                long liveAge = gemini.liveVideoAgeMs();
                if (liveAge < 0 || liveAge > 2500)
                    return "blocked: recent camera JPEG not queued to Gemini Live";
                return control.drive(direction, duration, true);
            }
            @Override public void onTranscript(String text) {
                runOnUiThread(() -> transcript.setText(text));
                log.i("LIVE", "transcript received (content redacted)");
            }
        });
    }

    private long cameraAgeMs() {
        long last = lastCameraFrame;
        return last == 0 ? -1 : Math.max(0, SystemClock.elapsedRealtime() - last);
    }

    /** The tool schema is structured, stable and avoids exposing any credentials. */
    private static JSONObject toolJson(Object... pairs) {
        JSONObject out = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2)
                out.put((String) pairs[i], pairs[i + 1]);
        } catch (Exception ignored) { }
        return out;
    }

    private void startMic() {
        if (!gemini.ready()) { log.e("MIC", "connect Gemini first"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_REQUEST);
            return;
        }
        gemini.startMic();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MIC_PERMISSION_REQUEST && results.length > 0 &&
                results[0] == PackageManager.PERMISSION_GRANTED) gemini.startMic();
        else if (requestCode == MIC_PERMISSION_REQUEST) log.e("MIC", "record audio permission denied");
    }

    @Override protected void onPause() {
        super.onPause();
        if (control != null) control.disarm();
        if (arm != null) uncheck();
        if (gemini != null) gemini.stopMic();
        if (camera != null) camera.stop();
        if (wltoysVideo != null) wltoysVideo.stop();
        lastCameraFrame = 0;
        if (repeatedMotion != null) main.removeCallbacks(repeatedMotion);
    }

    @Override protected void onDestroy() {
        if (camera != null) camera.stop();
        if (wltoysVideo != null) wltoysVideo.stop();
        if (gemini != null) gemini.disconnect();
        if (control != null) control.shutdown();
        super.onDestroy();
    }
}
