package com.crew.car;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Network;
import android.graphics.SurfaceTexture;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.provider.Settings;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.view.TextureView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.SeekBar;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int MIC_PERMISSION_REQUEST = 101;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable saveGeminiKeyLater = this::saveGeminiKeyLocally;
    private final Map<String, EditText> packets = new LinkedHashMap<>();
    private CarLog log;
    private CarNetwork routes;
    private CarControl control;
    private CameraStream camera;
    private WltoysVideoStream wltoysVideo;
    private GeminiLive gemini;
    private Button quickTalkButton, interruptButton;
    private TextView aiDriveStatus;
    private boolean autoStartMicOnReady;
    private final Runnable aiDriveTicker = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            if (aiDriveStatus != null) {
                if (control.aiHoldActive())
                    aiDriveStatus.setText("AI 保持中：油門 " +
                            (control.aiThrottle() > 0 ? "前進" :
                                    control.aiThrottle() < 0 ? "倒車" : "中立") +
                            "｜方向 " + (control.aiSteering() < 0 ? "左" :
                                    control.aiSteering() > 0 ? "右" : "回正") +
                            "｜剩餘 " + control.aiLeaseRemainingMs() + "ms（逾時停車）");
                else aiDriveStatus.setText("AI 油門保持：未啟用");
            }
            main.postDelayed(this, 250);
        }
    };
    private int liveConnectToken;

    private EditText host, port, cameraUrl, key, prompt;
    private Spinner transport, encoding, profile, cameraAdapter, tuningPreset;
    private SeekBar tuningPeriod, tuningSteer, tuningThrottle;
    private TextView tuningPreview, tuningActive, txDiagnostics;
    private TextView netStatus, cameraStatus, liveStatus, driverLiveStatus, transcript, logs;
    private ImageView preview;
    private TextureView hevcPreview;
    private CheckBox arm;
    private volatile long lastCameraFrame;
    private volatile int lastFrameWidth;
    private volatile int lastFrameHeight;
    private volatile int currentCameraAdapter;
    private boolean changingConfig;
    private ScrollView settingsScroller;
    private Button modeButton;
    private DualStickDriveView drivePad;
    private boolean drivingMode = true;
    private boolean cameraWanted = true;
    private boolean cameraManualRequested;
    private volatile boolean foreground;
    private boolean cameraRunning;
    private Network cameraNetwork;
    private long cameraSession;
    private int heldSteer, heldThrottle;
    private final Runnable manualPulse = new Runnable() {
        @Override public void run() {
            if (!foreground || !control.armed() || (heldSteer == 0 && heldThrottle == 0)) return;
            control.driveManualAxes(heldSteer, heldThrottle);
            main.postDelayed(this, 180);
        }
    };
    private final Runnable cameraMonitor = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            Network current = routes.wifi();
            if (cameraRunning && (current == null || !current.equals(cameraNetwork) ||
                    (currentCameraAdapter == 0 && !wltoysVideo.isRunning()) ||
                    (currentCameraAdapter == 1 && !camera.isRunning()))) {
                stopCameraReceiver();
            }
            if (arm != null && arm.isChecked() && !control.armed()) {
                log.i("SAFETY", "car is disarmed; syncing UI lock state");
                uncheck();
            }
            maybeStartCamera();
            updateNetworkStatus();
            updateTxStatus();
            main.postDelayed(this, 2500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        log = new CarLog();
        routes = new CarNetwork(this, log);
        control = new CarControl(routes, log);
        camera = new CameraStream(routes, log);
        wltoysVideo = new WltoysVideoStream(routes, log);
        gemini = new GeminiLive(log);
        control.setAiSafetyGate(() -> foreground && gemini.ready() &&
                routes.wifi() != null && cameraAgeMs() >= 0 &&
                cameraAgeMs() <= 2500 && gemini.liveVideoAgeMs() >= 0 &&
                gemini.liveVideoAgeMs() <= 2500);
        render();
        log.setListener(s -> runOnUiThread(() -> { if (logs != null) logs.setText(s); }));
        log.i("APP", "Crew Car v0.1.15; half-duplex Live and bounded AI drive hold; no vehicle ACK");
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
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(Color.rgb(248, 250, 252));
        setContentView(screen);
        LinearLayout banner = new LinearLayout(this);
        banner.setPadding(dp(8), dp(1), dp(8), dp(1));
        banner.setOrientation(LinearLayout.VERTICAL);
        screen.addView(banner);
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = new TextView(this);
        brand.setText("Crew Car · FPV");
        brand.setTextColor(Color.rgb(22, 32, 46));
        brand.setTextSize(17);
        brand.setTypeface(null, Typeface.BOLD);
        topRow.addView(brand, new LinearLayout.LayoutParams(0, dp(38), 1));
        quickTalkButton = button("AI 說話", v -> quickTalk());
        quickTalkButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(45, 155, 179)));
        quickTalkButton.setTextColor(Color.WHITE);
        topRow.addView(quickTalkButton, new LinearLayout.LayoutParams(dp(90), dp(38)));
        interruptButton = button("打斷 AI", v -> interruptAssistant());
        interruptButton.setEnabled(false);
        topRow.addView(interruptButton, new LinearLayout.LayoutParams(dp(88), dp(38)));
        Button retryCamera = button("重連鏡頭", v -> reconnectCamera());
        retryCamera.setTextSize(12);
        topRow.addView(retryCamera, new LinearLayout.LayoutParams(dp(84), dp(38)));
        modeButton = button("設定", v -> toggleMode());
        topRow.addView(modeButton, new LinearLayout.LayoutParams(dp(76), dp(38)));
        banner.addView(topRow);
        netStatus = label(banner, "檢查網路中…", 11);
        cameraStatus = label(banner, "攝影機未連線", 11);
        driverLiveStatus = label(banner, "Gemini Live：尚未連線", 11);
        aiDriveStatus = label(banner, "AI 油門保持：未啟用", 11);
        for (TextView status : new TextView[]{netStatus, cameraStatus, driverLiveStatus, aiDriveStatus}) {
            status.setSingleLine(true);
            status.setEllipsize(android.text.TextUtils.TruncateAt.END);
            status.setPadding(0, 0, 0, 0);
        }

        FrameLayout cockpit = new FrameLayout(this);
        cockpit.setBackgroundColor(Color.rgb(12, 20, 32));
        screen.addView(cockpit, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout cameraFrame = new FrameLayout(this);
        cameraFrame.setBackgroundColor(Color.rgb(22, 32, 46));
        int videoWidth = Math.round(getResources().getDisplayMetrics().widthPixels *
                (1f - 2f * DualStickInput.SIDE_FRACTION));
        cockpit.addView(cameraFrame,
                new FrameLayout.LayoutParams(videoWidth, -1, Gravity.CENTER));

        preview = new ImageView(this);
        preview.setBackgroundColor(Color.rgb(22, 32, 46));
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cameraFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        hevcPreview = new TextureView(this);
        cameraFrame.addView(hevcPreview, new FrameLayout.LayoutParams(-1, -1));
        hevcPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
                fitCameraAspect(w, h);
                log.i("CAM", "TextureView available; camera can start");
                maybeStartCamera();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) {
                fitCameraAspect(w, h);
                log.i("CAM", "TextureView size=" + w + "x" + h);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                log.i("CAM", "TextureView destroyed; suspending camera");
                if (currentCameraAdapter == 0) {
                    stopCameraReceiver();
                    if (control != null) control.disarm();
                    if (arm != null) uncheck();
                }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });

        settingsScroller = new ScrollView(this);
        settingsScroller.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(15), dp(16), dp(32));
        root.setBackgroundColor(Color.rgb(248, 250, 252));
        settingsScroller.addView(root);
        settingsScroller.setVisibility(View.GONE);
        // This single view owns BOTH touch pointers; no two-button multi-touch conflicts.
        drivePad = new DualStickDriveView(this, (steer, throttle) -> {
            heldSteer = steer;
            heldThrottle = throttle;
            main.removeCallbacks(manualPulse);
            if (steer == 0 && throttle == 0) {
                control.setManual(false);
                control.emergencyStop();
                return;
            }
            if (!control.armed()) {
                drivePad.cancelInput();
                log.e("SAFETY", "dual-stick input blocked: locked");
                return;
            }
            control.setManual(true);
            String result = control.driveManualAxes(steer, throttle);
            if (result.startsWith("blocked")) log.e("SAFETY", result);
            else main.postDelayed(manualPulse, 180);
        });
        cockpit.addView(drivePad, new FrameLayout.LayoutParams(-1, -1));
        // Settings are an overlay; the TextureView is not detached or restarted.
        cockpit.addView(settingsScroller, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout driveConsole = new LinearLayout(this);
        driveConsole.setOrientation(LinearLayout.HORIZONTAL);
        driveConsole.setGravity(Gravity.CENTER_VERTICAL);
        driveConsole.setPadding(dp(8), dp(2), dp(8), dp(2));
        driveConsole.setBackgroundColor(Color.rgb(232, 239, 246));
        screen.addView(driveConsole, new LinearLayout.LayoutParams(-1, dp(62)));
        TextView heading = label(root, "進階設定與除錯", 17);
        heading.setTypeface(null, Typeface.BOLD);
        label(root, "橫向駕駛：左側左右轉向；右側分別按住前進或倒車，放開即停止。可雙指同時操作；進入設定時會先停車上鎖。", 13);
        row(root, button("開 Wi-Fi 設定", v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS))),
                button("網路診斷", v -> {
                    routes.diagnostics();
                    updateNetworkStatus();
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
        label(root, "6405 使用 UDP H.265；若黑畫面，查看收包／組幀／解碼數及 TextureView 像素亮度。", 12);
        preview.setVisibility(cameraAdapter.getSelectedItemPosition() == 0 ? View.GONE : View.VISIBLE);
        hevcPreview.setVisibility(cameraAdapter.getSelectedItemPosition() == 0 ? View.VISIBLE : View.GONE);
        cameraAdapter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                stopCameraReceiver();
                currentCameraAdapter = position;
                lastCameraFrame = 0;
                lastFrameWidth = 0;
                lastFrameHeight = 0;
                control.disarm();
                if (arm != null) uncheck();
                preview.setVisibility(position == 0 ? View.GONE : View.VISIBLE);
                hevcPreview.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
                maybeStartCamera();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        row(root, button("重新連接攝影機", v -> reconnectCamera()),
                button("停止影像", v -> {
                    cameraWanted = false;
                    cameraManualRequested = false;
                    stopCameraReceiver();
                    cameraStatus.setText("影像已手動停止");
                    emergencyStopAndLock();
                }));

        label(root, "2. 小車控制（原廠 App log 部分觀察）", 17).setTypeface(null, Typeface.BOLD);
        label(root, "原廠 APK 對照到 UDP 172.16.11.1:23458、16-byte 控制框架及約 50ms 週期；中立封包與原廠初始化值相符，尚無車端 ACK。", 12);
        profile = new Spinner(this);
        profile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"自訂封包（空白時禁止解鎖）", "WL FPV 原廠 16-byte profile（UDP 23458）"}));
        profile.setSelection(getPreferences(MODE_PRIVATE).getInt("protocol_profile", 1));
        root.addView(profile);
        label(root, "已預選原廠對照 profile；手動控制需勾選解鎖。可拖曳盤斜向移動。", 12);
        profile.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                control.disarm();
                if (arm != null) uncheck();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        label(root, "操控調校｜預設與實際週期", 17)
                .setTypeface(null, Typeface.BOLD);
        label(root, "週期越短，重複封包越密；原廠基準為 50ms。30／20／10ms 是頻率實驗值，測試時請架空車輪。", 12);
        tuningPreset = new Spinner(this);
        tuningPreset.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"柔和｜50ms・轉向65%・油門60%",
                        "原廠基準｜50ms・全部100%",
                        "省封包｜70ms・全部75%",
                        "靈敏實驗｜40ms・全部100%",
                        "頻率實驗｜30ms（保持幅度）",
                        "頻率實驗｜20ms（保持幅度）",
                        "頻率實驗｜10ms（保持幅度）"}));
        tuningPreset.setSelection(getPreferences(MODE_PRIVATE).getInt("tuning_preset", 1));
        root.addView(tuningPreset);
        int savedPeriod = getPreferences(MODE_PRIVATE).getInt("tuning_interval", 50);
        int savedSteer = getPreferences(MODE_PRIVATE).getInt("tuning_steer", 100);
        int savedThrottle = getPreferences(MODE_PRIVATE).getInt("tuning_throttle", 100);
        tuningPeriod = tuningBar(root, "控制傳送週期（10–100ms，每格10ms）",
                (CarTuning.MAX_INTERVAL_MS - CarTuning.MIN_INTERVAL_MS) /
                        CarTuning.INTERVAL_STEP_MS,
                (savedPeriod - CarTuning.MIN_INTERVAL_MS) / CarTuning.INTERVAL_STEP_MS);
        tuningSteer = tuningBar(root, "轉向幅度（50–100%，每格5%）",
                10, (savedSteer - 50) / 5);
        tuningThrottle = tuningBar(root, "油門幅度（50–100%，每格5%）",
                10, (savedThrottle - 50) / 5);
        tuningPreview = label(root, "", 13);
        tuningPreset.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private boolean first = true;
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                 View view, int index, long id) {
                if (first) { first = false; updateTuningPreview(); return; }
                CarTuning t = CarTuning.preset(index);
                tuningPeriod.setProgress((t.intervalMs - CarTuning.MIN_INTERVAL_MS) /
                        CarTuning.INTERVAL_STEP_MS);
                if (index <= 3) {
                    tuningSteer.setProgress((t.steeringPercent - 50) / 5);
                    tuningThrottle.setProgress((t.throttlePercent - 50) / 5);
                }
                updateTuningPreview();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        updateTuningPreview();
        row(root, button("套用調校（停車上鎖）", v -> {
                    emergencyStopAndLock();
                    if (applyConfig()) {
                        updateTuningPreview();
                        log.i("TUNE", "user applied " + control.tuning() +
                                " (requires manual re-arm)");
                    }
                }),
                button("恢復原廠基準", v -> {
                    tuningPreset.setSelection(1);
                    tuningPeriod.setProgress((CarTuning.OEM.intervalMs - CarTuning.MIN_INTERVAL_MS) /
                            CarTuning.INTERVAL_STEP_MS);
                    tuningSteer.setProgress(10);
                    tuningThrottle.setProgress(10);
                    updateTuningPreview();
                }));
        txDiagnostics = label(root, "本機封包診斷：尚未測試", 12);
        label(root, "本機傳送耗時／間隔不是小車回應時間，也不能用來推算實際車速。", 12);
        host = field(root, "控制端 IP", saved("host", "172.16.11.1"), false);
        port = field(root, "自訂模式控制 Port（原廠 profile 固定 23458）", saved("port", "23458"), true);
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
        row(root, button("套用連線設定", v -> {
                    emergencyStopAndLock();
                    applyConfig();
                }), button("立即停止", v -> emergencyStopAndLock()));

        arm = new CheckBox(this);
        arm.setText("解鎖駕駛");
        arm.setTextSize(13);
        driveConsole.addView(arm, new LinearLayout.LayoutParams(dp(126), -1));
        arm.setOnCheckedChangeListener((view, checked) -> {
            if (changingConfig) return;
            if (checked) {
                if (!applyConfig()) { uncheck(); return; }
                String issue = control.arm();
                if (issue != null) {
                    log.e("SAFETY", "arm denied: " + issue);
                    uncheck();
                } else {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    updateTuningPreview();
                }
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                releaseManualInputs();
                control.disarm();
            }
        });
        label(root, "按住移動；所有方向鍵放開才送 STOP。斷訊 / 逾時也會嘗試送 STOP；未收到車輛 ACK。", 12);

        Button stopButton = button("■ 急停 STOP · 上鎖", v -> emergencyStopAndLock());
        stopButton.setTextColor(Color.WHITE);
        stopButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(177, 36, 42)));
        stopButton.setTextSize(16);
        driveConsole.addView(stopButton, new LinearLayout.LayoutParams(0, dp(54), 1.4f));
        tuningActive = label(driveConsole, "50ms｜轉向100%｜油門100%", 11);
        tuningActive.setSingleLine(false);
        tuningActive.setGravity(Gravity.CENTER_VERTICAL);
        tuningActive.setPadding(dp(6), 0, 0, 0);
        tuningActive.setLayoutParams(new LinearLayout.LayoutParams(dp(190), -1));

        label(root, "3. Gemini 3.8 Live（手機麥克風 + 車頭畫面）", 17).setTypeface(null, Typeface.BOLD);
        label(root, "使用自己的 API Key；輸入後會加密保存於本機，不寫入 Log。連小車 Wi-Fi 時請保持行動數據。", 12);
        key = field(root, "Gemini API Key（自動加密保存）", LocalSecretStore.readGeminiKey(this), false);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                main.removeCallbacks(saveGeminiKeyLater);
                main.postDelayed(saveGeminiKeyLater, 500);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        liveStatus = label(root, "Gemini 尚未連線", 13);
        row(root, button("連線 Live", v -> startGemini()),
                button("斷線 Live", v -> {
                    liveConnectToken++;
                    routes.releaseCellularForLive();
                    autoStartMicOnReady = false;
                    gemini.disconnect();
                    control.emergencyStop();
                    updateTalkButtons();
                    liveStatus.setText("Gemini 已手動斷線");
                    if (driverLiveStatus != null) driverLiveStatus.setText("Gemini Live：已手動斷線");
                }));
        row(root, button("開啟麥克風", v -> startMic()), button("關閉麥克風", v -> gemini.stopMic()));
        prompt = field(root, "測試文字，例如：請描述鏡頭畫面（不要求移動）", "", false);
        root.addView(button("送出文字給 Gemini", v -> {
            if (!gemini.sendText(prompt.getText().toString())) log.e("LIVE", "請先連線 Gemini");
            else log.i("LIVE", "text turn sent (content redacted)");
        }), new LinearLayout.LayoutParams(-1, dp(50)));
        label(root, "Gemini 可使用：drive_car、set_drive_state（最長保持5秒）、stop_car、lock_car、get_car_status、get_camera_status、get_network_status。左側快捷可直接啟動對話。", 12);
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
        updateNetworkStatus();
        updateTxStatus();
        updateMode();
    }

    private SeekBar tuningBar(LinearLayout root, String title, int max, int initial) {
        label(root, title, 12);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(Math.max(0, Math.min(max, initial)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar v, int progress, boolean fromUser) {
                updateTuningPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar v) {}
            @Override public void onStopTrackingTouch(SeekBar v) {}
        });
        root.addView(bar);
        return bar;
    }

    private CarTuning selectedTuning() {
        return new CarTuning(CarTuning.MIN_INTERVAL_MS +
                tuningPeriod.getProgress() * CarTuning.INTERVAL_STEP_MS,
                50 + tuningSteer.getProgress() * 5,
                50 + tuningThrottle.getProgress() * 5);
    }

    private void updateTuningPreview() {
        if (tuningPreview == null || tuningPeriod == null ||
                tuningSteer == null || tuningThrottle == null) return;
        CarTuning t = selectedTuning();
        CarTuning active = control.tuning();
        boolean applied = t.intervalMs == active.intervalMs &&
                t.steeringPercent == active.steeringPercent &&
                t.throttlePercent == active.throttlePercent;
        tuningPreview.setText((applied ? "目前已套用：" : "尚未套用：") + t.intervalMs +
                "ms（約 " + (1000 / t.intervalMs) + "次／秒）｜轉向" +
                t.steeringPercent + "%｜油門" + t.throttlePercent + "%" +
                (applied ? "" : "。請按「套用調校」；會先停車並上鎖。"));
    }

    private void updateTxStatus() {
        if (txDiagnostics != null) txDiagnostics.setText("本機封包診斷：" + control.diagnosticSummary());
        if (tuningActive != null) {
            CarTuning active = control.tuning();
            tuningActive.setText(active.intervalMs + "ms｜轉向" +
                    active.steeringPercent + "%｜油門" + active.throttlePercent +
                    "%\n（本機設定）");
        }
    }

    /** Keep the 1280x720 H.265 decoder surface in 16:9 without stretching it. */
    private void fitCameraAspect(int width, int height) {
        if (hevcPreview == null || width <= 0 || height <= 0) return;
        float desired = 16f / 9f;
        float actual = width / (float) height;
        Matrix matrix = new Matrix();
        if (actual > desired) {
            matrix.setScale(desired / actual, 1f, width / 2f, height / 2f);
        } else {
            matrix.setScale(1f, actual / desired, width / 2f, height / 2f);
        }
        hevcPreview.setTransform(matrix);
    }

    private void toggleMode() {
        // Opening settings must never leave a motor command running behind the overlay.
        if (drivingMode) emergencyStopAndLock();
        drivingMode = !drivingMode;
        updateMode();
    }

    private void updateMode() {
        if (settingsScroller == null || drivePad == null || modeButton == null) return;
        drivePad.setVisibility(drivingMode ? View.VISIBLE : View.GONE);
        settingsScroller.setVisibility(drivingMode ? View.GONE : View.VISIBLE);
        modeButton.setText(drivingMode ? "設定" : "返回駕駛");
    }

    private void updateNetworkStatus() {
        if (netStatus != null) {
            Network wifi = routes.wifi();
            netStatus.setText("小車 Wi-Fi：" + (wifi == null ? "未連線" : "有連線") +
                    "  |  Gemini 網路：" +
                    (routes.internet(wifi) != null ? "可用" :
                            routes.cellularRequestActive() ? "準備行動數據中" : "不可用") +
                    "  |  車輛回執：未支援");
        }
    }

    private void uncheck() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        changingConfig = true;
        arm.setChecked(false);
        changingConfig = false;
        releaseManualInputs();
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
            cfg.tuning = selectedTuning();
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
                    .putInt("tuning_interval", cfg.tuning.intervalMs)
                    .putInt("tuning_steer", cfg.tuning.steeringPercent)
                    .putInt("tuning_throttle", cfg.tuning.throttlePercent)
                    .putInt("tuning_preset", tuningPreset.getSelectedItemPosition())
                    .apply();
            control.configure(cfg);
            updateTxStatus();
            updateTuningPreview();
            log.i("UI", "control config applied, safety lock ON; tuning=" + cfg.tuning);
            return true;
        } catch (Exception e) {
            log.e("UI", "config invalid: " + e.getMessage());
            return false;
        }
    }

    /** Auto-connect on offline car Wi-Fi; manual reconnect can override the check. */
    private void maybeStartCamera() {
        if (!foreground || !cameraWanted || cameraRunning || cameraAdapter == null) return;
        Network wifi = routes.wifi();
        if (wifi == null) {
            cameraStatus.setText("等待小車 Wi-Fi 連線…");
            return;
        }
        if (!cameraManualRequested && routes.isValidatedInternet(wifi)) {
            cameraStatus.setText("等待小車離線 Wi-Fi；一般網路不自動連攝影機");
            return;
        }
        if (cameraAdapter.getSelectedItemPosition() == 0 &&
                !hevcPreview.isAvailable()) {
            cameraStatus.setText("等待 FPV 畫面 TextureView 就緒…");
            return;
        }
        cameraRunning = true;
        cameraNetwork = wifi;
        startCamera(++cameraSession);
    }

    /** One-tap camera recovery from the driving screen; never leave motors armed. */
    private void reconnectCamera() {
        emergencyStopAndLock();
        cameraWanted = true;
        cameraManualRequested = true;
        stopCameraReceiver();
        maybeStartCamera();
        log.i("CAM", "user-requested camera reconnect; motor disarmed");
    }

    private void stopCameraReceiver() {
        ++cameraSession;
        cameraRunning = false;
        cameraNetwork = null;
        if (camera != null) camera.stop();
        if (wltoysVideo != null) wltoysVideo.stop();
        lastCameraFrame = 0;
        lastFrameWidth = 0;
        lastFrameHeight = 0;
    }

    private void startCamera(long token) {
        String url = cameraUrl.getText().toString().trim();
        getPreferences(MODE_PRIVATE).edit().putString("camera_url", url)
                .putInt("camera_adapter", cameraAdapter.getSelectedItemPosition()).apply();
        currentCameraAdapter = cameraAdapter.getSelectedItemPosition();
        cameraStatus.setText("攝影機連線中…");
        if (currentCameraAdapter == 0) {
            String destination = host.getText().toString().trim();
            wltoysVideo.start(destination, hevcPreview, frame -> {
                if (!foreground || token != cameraSession) return;
                lastFrameWidth = frame.getWidth();
                lastFrameHeight = frame.getHeight();
                lastCameraFrame = SystemClock.elapsedRealtime();
                gemini.frame(frame);
                cameraStatus.setText("HEVC " + frame.getWidth() + "×" + frame.getHeight() +
                        "｜TextureView 畫面；像素亮度見 Debug");
            }, status -> {
                if (foreground && token == cameraSession && cameraStatus != null)
                    cameraStatus.setText(status);
            });
        } else {
            camera.start(url, frame -> {
                if (!foreground || token != cameraSession) return;
                lastFrameWidth = frame.getWidth();
                lastFrameHeight = frame.getHeight();
                lastCameraFrame = SystemClock.elapsedRealtime();
                gemini.frame(frame);
                runOnUiThread(() -> {
                    if (foreground && token == cameraSession && !isFinishing()) {
                        preview.setImageBitmap(frame);
                        cameraStatus.setText("JPEG " + frame.getWidth() + "×" + frame.getHeight());
                    }
                });
            });
        }
    }

    private void releaseManualInputs() {
        heldSteer = 0;
        heldThrottle = 0;
        main.removeCallbacks(manualPulse);
        if (drivePad != null) drivePad.cancelInput();
        if (control != null) control.setManual(false);
    }

    private void emergencyStopAndLock() {
        releaseManualInputs();
        control.disarm();
        control.emergencyStop();
        if (arm != null && arm.isChecked()) {
            changingConfig = true;
            arm.setChecked(false);
            changingConfig = false;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        log.i("SAFETY", "USER E-STOP: disarmed, manual re-arm required");
    }

    private void quickTalk() {
        if (gemini.assistantSpeaking()) {
            log.i("MIC", "AI is speaking; use interrupt button");
            return;
        }
        if (gemini.ready()) {
            if (gemini.micRunning()) {
                autoStartMicOnReady = false;
                gemini.stopMic();
                updateTalkButtons();
            } else {
                autoStartMicOnReady = false;
                startMic();
                updateTalkButtons();
            }
            return;
        }
        autoStartMicOnReady = true;
        startGemini();
        updateTalkButtons();
    }

    private void interruptAssistant() {
        control.emergencyStop(); // user interrupt also terminates an active AI cruise lease
        gemini.interruptSpeech();
        updateTalkButtons();
    }

    private void updateTalkButtons() {
        if (quickTalkButton == null) return;
        boolean speaking = gemini.assistantSpeaking();
        quickTalkButton.setText(speaking ? "AI 說話中" :
                !gemini.ready() ? "連接 AI" :
                        gemini.micRunning() ? "關麥克風" : "AI 說話");
        interruptButton.setEnabled(speaking);
        interruptButton.setVisibility(View.VISIBLE);
    }

    private void startGemini() {
        String apiKey = key.getText().toString().trim();
        main.removeCallbacks(saveGeminiKeyLater);
        saveGeminiKeyLocally();
        if (apiKey.isEmpty()) {
            autoStartMicOnReady = false;
            liveStatus.setText("請先輸入 Gemini API Key");
            if (driverLiveStatus != null) driverLiveStatus.setText("Gemini Live：未輸入 Key");
            return;
        }
        final int token = ++liveConnectToken;
        Network car = routes.wifi();
        Network initial = routes.internet(car);
        // Normal Internet Wi-Fi and already-active cellular (without car Wi-Fi)
        // do not need a dedicated cellular request for a read-only Live session.
        if (initial != null && (car == null || routes.isValidatedInternet(car))) {
            // Validated ordinary Wi-Fi does not need to reserve the mobile radio.
            if (car != null && routes.isValidatedInternet(car))
                routes.releaseCellularForLive();
            connectGeminiOnNetwork(apiKey, initial);
            return;
        }
        liveStatus.setText("正在準備行動數據（小車 Wi-Fi 保持連線）…");
        if (driverLiveStatus != null)
            driverLiveStatus.setText("Gemini Live：準備 4G／5G…");
        log.i("LIVE", "requesting a separate cellular network for offline car Wi-Fi");
        routes.requestCellularForLive(network -> {
            if (token != liveConnectToken || !foreground || isFinishing()) return;
            log.i("LIVE", "validated cellular route selected for Gemini");
            updateNetworkStatus();
            connectGeminiOnNetwork(apiKey, network);
        }, () -> {
            if (token != liveConnectToken || isFinishing()) return;
            liveStatus.setText("行動網路無法啟用：請確認行動數據／SIM／訊號，再重試");
            if (driverLiveStatus != null)
                driverLiveStatus.setText("Gemini Live：行動網路不可用");
            log.e("LIVE", "cellular not available; car Wi-Fi preserved");
            updateNetworkStatus();
        });
    }

    private void connectGeminiOnNetwork(String apiKey, Network route) {
        gemini.connect(apiKey, route, new GeminiLive.Listener() {
            @Override public void onStatus(String status) {
                if (status.contains("失敗") || status.contains("中斷")) {
                    control.emergencyStop();
                    main.post(routes::releaseCellularForLive);
                }
                runOnUiThread(() -> {
                    liveStatus.setText(status);
                    if (driverLiveStatus != null)
                        driverLiveStatus.setText("Gemini Live：" + status);
                    if (status.contains("失敗") || status.contains("中斷"))
                        autoStartMicOnReady = false;
                    if (status.contains("已連線") && autoStartMicOnReady) {
                        autoStartMicOnReady = false;
                        startMic();
                    }
                    updateTalkButtons();
                });
                log.i("LIVE", "state updated"); // no credentials
            }
            @Override public void onSpeechState(boolean speaking) {
                runOnUiThread(() -> updateTalkButtons());
            }
            @Override public String onDriveState(String steering, String throttle, int leaseMs) {
                if (!control.armed()) return "blocked: manual safety lock";
                if (cameraAgeMs() < 0 || cameraAgeMs() > 2500 ||
                        !gemini.ready() || gemini.liveVideoAgeMs() < 0 ||
                        gemini.liveVideoAgeMs() > 2500 || routes.wifi() == null)
                    return "blocked: camera/Live/Wi-Fi stale";
                return control.holdAiAxes(steering, throttle, leaseMs);
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
                        "ai_hold_active", control.aiHoldActive(),
                        "ai_steering", control.aiSteering(),
                        "ai_throttle", control.aiThrottle(),
                        "ai_lease_remaining_ms", control.aiLeaseRemainingMs(),
                        "candidate_wltoys_protocol", control.candidateProtocol(),
                        "last_tx_direction", control.lastTxCommand(),
                        "last_tx_status", control.lastTxStatus(),
                        "last_tx_age_ms", control.lastTxAgeMs(),
                        "configured_tx_interval_ms", control.tuning().intervalMs,
                        "configured_steering_percent", control.tuning().steeringPercent,
                        "configured_throttle_percent", control.tuning().throttlePercent,
                        "local_tx_count", control.txPackets(),
                        "local_tx_failures", control.txFailures(),
                        "local_last_tx_gap_ms", control.lastTxGapMs(),
                        "local_max_tx_gap_ms", control.maxTxGapMs(),
                        "local_last_send_duration_ms", control.lastSendDurationMs(),
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
                        "cellular_request_active", routes.cellularRequestActive(),
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

    private void saveGeminiKeyLocally() {
        if (key != null && !LocalSecretStore.saveGeminiKey(this, key.getText().toString()))
            log.e("LIVE", "Gemini API Key could not be encrypted and stored locally");
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
        if (gemini.assistantSpeaking()) {
            log.i("MIC", "assistant speaking; microphone resumes automatically");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_REQUEST);
            return;
        }
        gemini.startMic();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == MIC_PERMISSION_REQUEST && results.length > 0 &&
                results[0] == PackageManager.PERMISSION_GRANTED) {
            gemini.startMic();
            updateTalkButtons();
        } else if (requestCode == MIC_PERMISSION_REQUEST) {
            log.e("MIC", "record audio permission denied");
            autoStartMicOnReady = false;
            updateTalkButtons();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(saveGeminiKeyLater);
        saveGeminiKeyLocally();
        foreground = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        main.removeCallbacks(cameraMonitor);
        main.removeCallbacks(aiDriveTicker);
        autoStartMicOnReady = false;
        releaseManualInputs();
        if (control != null) control.disarm();
        if (arm != null) uncheck();
        if (gemini != null) gemini.stopMic();
        if (!gemini.ready() && routes.cellularRequestActive()) {
            liveConnectToken++;
            routes.releaseCellularForLive();
        }
        stopCameraReceiver();
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        updateNetworkStatus();
        main.removeCallbacks(cameraMonitor);
        main.post(cameraMonitor);
        main.removeCallbacks(aiDriveTicker);
        main.post(aiDriveTicker);
        updateTalkButtons();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(cameraMonitor);
        main.removeCallbacks(aiDriveTicker);
        main.removeCallbacks(saveGeminiKeyLater);
        stopCameraReceiver();
        liveConnectToken++;
        routes.releaseCellularForLive();
        if (gemini != null) gemini.disconnect();
        if (control != null) control.shutdown();
        super.onDestroy();
    }
}
