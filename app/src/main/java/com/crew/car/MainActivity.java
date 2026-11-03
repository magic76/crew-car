package com.crew.car;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Network;
import android.graphics.SurfaceTexture;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.graphics.Insets;
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
    private CrewVision crewVision;
    private VisionOverlay visionOverlay;
    private final VisionDebugMetrics visionMetrics = new VisionDebugMetrics();
    private TextView visionDebugHud;
    private boolean visionDebugShown;
    private volatile long lastRenderedHevcAt;
    private Button visionButton, lockButton;
    private volatile boolean visionEnabled;
    private long followLastLogAt;
    private Button quickTalkButton, interruptButton;
    private TextView aiDriveStatus;
    private boolean autoStartMicOnReady;
    private final Runnable aiDriveTicker = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            if (aiDriveStatus != null) {
                if (control.visionFollowActive())
                    aiDriveStatus.setText("跟隨人物中 · 最多 8 秒 · 可按急停");
                else if (control.aiHoldActive())
                    aiDriveStatus.setText("AI 保持中：油門 " +
                            (control.aiThrottle() > 0 ? "前進" :
                                    control.aiThrottle() < 0 ? "倒車" : "中立") +
                            "｜方向 " + (control.aiSteering() < 0 ? "左" :
                                    control.aiSteering() > 0 ? "右" : "回正") +
                            "｜剩餘 " + control.aiLeaseRemainingMs() + "ms（逾時停車）");
                else if (control.manual() && heldThrottle != 0)
                    aiDriveStatus.setText("手動油門持續送包：" +
                            (heldThrottle > 0 ? "前進" : "倒車") + "｜" +
                            control.tuning().intervalMs + "ms／包");
                else aiDriveStatus.setText("AI 巡航：未啟用｜手動按住前進／倒車持續送包");
            }
            updateVisionDebugHud();
            main.postDelayed(this, 250);
        }
    };
    private int liveConnectToken;

    private EditText host, port, cameraUrl, key, prompt;
    private Spinner transport, encoding, profile, cameraAdapter, tuningPreset;
    private SeekBar tuningPeriod, tuningSteer, tuningThrottle;
    private TextView tuningPreview, tuningActive, txDiagnostics;
    private TextView netStatus, cameraStatus, liveStatus, driverLiveStatus, transcript, logs;
    private TextView vehicleTitle;
    private CarProfile activeProfile = CarProfile.WL_TECH;
    private boolean switchingCarProfile;
    private ImageView preview;
    private TextureView hevcPreview;
    private CheckBox arm;
    private volatile long lastCameraFrame;
    private volatile int lastFrameWidth;
    private volatile int lastFrameHeight;
    private volatile int currentCameraAdapter;
    private boolean changingConfig;
    private ScrollView settingsScroller;
    private LinearLayout settingsShell;
    private LinearLayout[] settingsSections;
    private Button[] settingsNavButtons;
    private TextView settingsSafetyLabel;
    private int activeSettingsTab = 0;
    private Button modeButton;
    private DualStickDriveView drivePad;
    private FrameLayout cockpit, cameraFrame;
    private DriverLayoutConfig driverLayout = DriverLayoutConfig.defaults();
    private volatile boolean drivingMode = true;
    private boolean cameraWanted = true;
    private boolean cameraManualRequested;
    private volatile boolean foreground;
    private volatile boolean cameraRunning;
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
    // Samples HEVC TextureView locally at up to 5 FPS independently of Gemini's 1 FPS.
    // All inference executes on a dedicated worker; this sampler never touches motors.
    private final Runnable visionSampler = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            if (visionEnabled && drivingMode && cameraRunning &&
                    currentCameraAdapter == 0 && hevcPreview != null &&
                    hevcPreview.isAvailable() && hevcPreview.getWidth() > 0 &&
                    hevcPreview.getHeight() > 0 &&
                    visionCameraAgeMs() >= 0 && visionCameraAgeMs() <= 650 &&
                    crewVision.ready() && !crewVision.busy()) {
                try {
                    int width = 320;
                    int height = Math.max(1, Math.round(width *
                            hevcPreview.getHeight() / (float) hevcPreview.getWidth()));
                    android.graphics.Bitmap captured = hevcPreview.getBitmap(width, height);
                    if (captured != null) crewVision.submit(captured, true);
                } catch (Exception e) {
                    log.e("VISION", "capture unavailable " + e.getClass().getSimpleName());
                }
            }
            if (visionOverlay != null) visionOverlay.expire(SystemClock.elapsedRealtime());
            if (control != null && control.visionFollowActive() &&
                    (visionOverlay == null || visionOverlay.targetLost() ||
                    visionOverlay.selectedTarget() == null ||
                    SystemClock.elapsedRealtime() - visionOverlay.lastFrameAt() > 400)) {
                control.stopVisionFollow("person lost or inference stale");
                log.i("FOLLOW", "target or image stale; STOP");
            }
            main.postDelayed(this, CrewVision.TARGET_INTERVAL_MS);
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
            updateLockButton();
            maybeStartCamera();
            updateNetworkStatus();
            updateTxStatus();
            if (drivePad != null) drivePad.setControlsAvailable(control.armed());
            main.postDelayed(this, 2500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        activeProfile = CarProfile.visibleFromIndex(getPreferences(MODE_PRIVATE)
                .getInt("protocol_profile", 1));
        log = new CarLog();
        routes = new CarNetwork(this, log);
        control = new CarControl(routes, log);
        camera = new CameraStream(routes, log);
        wltoysVideo = new WltoysVideoStream(routes, log);
        gemini = new GeminiLive(log);
        crewVision = new CrewVision(this, new CrewVision.Listener() {
            @Override public void onPeople(java.util.List<PersonTracker.Box> people,
                                            long frameAt, long inferMs) {
                if (!visionEnabled || !foreground || !cameraRunning ||
                        visionOverlay == null) return;
                float aspect = lastFrameWidth > 0 && lastFrameHeight > 0 ?
                        lastFrameWidth / (float) lastFrameHeight : 16f / 9f;
                if (currentCameraAdapter == 0 && hevcPreview != null &&
                        hevcPreview.getHeight() > 0) {
                    aspect = hevcPreview.getWidth() / (float) hevcPreview.getHeight();
                }
                visionOverlay.update(people, frameAt, inferMs, aspect,
                        currentCameraAdapter == 1);
                visionMetrics.observe(frameAt, SystemClock.elapsedRealtime(), inferMs,
                        people.size(), visionOverlay.selectedTarget());
                if (control.visionFollowActive()) {
                    PersonTracker.Box target = visionOverlay.selectedTarget();
                    if (target == null || visionOverlay.targetLost()) {
                        control.stopVisionFollow("vision target lost");
                        log.i("FOLLOW", "selected person lost; STOP");
                    } else {
                        VisionFollowPlanner.Step step = VisionFollowPlanner.decide(target);
                        if (!step.moving()) {
                            // One visual risk condition ends the entire session.
                            // Never resume on its own when the person appears smaller again.
                            control.stopVisionFollow(step.reason);
                            log.i("FOLLOW", step.reason + " — session cancelled; STOP");
                            return;
                        }
                        String result = control.driveVisionAxes(
                                step.steer, step.throttle, frameAt);
                        if (SystemClock.elapsedRealtime() - followLastLogAt >= 1200) {
                            followLastLogAt = SystemClock.elapsedRealtime();
                            log.i("FOLLOW", step.reason + " result=" + result +
                                    " targetHeight=" + target.height());
                        }
                    }
                }
                if (people.size() > 0 && frameAt / 5000 != (frameAt - CrewVision.TARGET_INTERVAL_MS) / 5000)
                    log.i("VISION", "people=" + people.size() +
                            " inferMs=" + inferMs + " (display only)");
            }
            @Override public void onError(String message) {
                control.stopVisionFollow("vision detector failure");
                visionEnabled = false;
                if (visionOverlay != null) visionOverlay.setVisionEnabled(false);
                updateVisionButton();
                updateVisionDebugHud();
                android.widget.Toast.makeText(MainActivity.this, message,
                        android.widget.Toast.LENGTH_LONG).show();
                log.e("VISION", message);
            }
        });
        driverLayout = loadDriverLayout();
        control.setVisionSafetyGate(() -> foreground && drivingMode &&
                visionEnabled && crewVision.ready() &&
                visionOverlay != null && visionOverlay.selectedTarget() != null &&
                !visionOverlay.targetLost() && visionOverlay.lastFrameAt() > 0 &&
                SystemClock.elapsedRealtime() - visionOverlay.lastFrameAt() <= 400 &&
                cameraRunning && routes.wifi() != null &&
                visionCameraAgeMs() >= 0 && visionCameraAgeMs() <= 650);
        // Voice driving no longer depends on blurry/slow FPV frames.
        // Foreground + human arm + connected Live/Wi-Fi + on-device watchdog remain required.
        control.setAiSafetyGate(() -> foreground && drivingMode &&
                gemini.ready() && routes.wifi() != null);
        render();
        log.setListener(s -> runOnUiThread(() -> { if (logs != null) logs.setText(s); }));
        log.i("APP", "Crew Car v0.1.26; one-tool natural voice driving without direction authorization window; no vehicle ACK");
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

    /**
     * Android 15 edge-to-edge plus pre-30 landscape navigation/cutout handling.
     * Keep all driver buttons INSIDE the platform-provided safe viewport.
     */
    private void applySystemSafeInsets(View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bounds = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bounds.left;
                top = bounds.top;
                right = bounds.right;
                bottom = bounds.bottom;
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();
            }
            if (view.getPaddingLeft() != left || view.getPaddingTop() != top ||
                    view.getPaddingRight() != right || view.getPaddingBottom() != bottom)
                view.setPadding(left, top, right, bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    private void render() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(Color.rgb(248, 250, 252));
        setContentView(screen);
        applySystemSafeInsets(screen);
        LinearLayout banner = new LinearLayout(this);
        banner.setPadding(dp(8), dp(1), dp(8), dp(1));
        banner.setOrientation(LinearLayout.VERTICAL);
        screen.addView(banner);
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = new TextView(this);
        vehicleTitle = brand;
        brand.setText("Crew Car · " + activeProfile.label);
        brand.setTextColor(Color.rgb(22, 32, 46));
        brand.setTextSize(17);
        brand.setTypeface(null, Typeface.BOLD);
        brand.setSingleLine(true);
        brand.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
        visionButton = button("辨識", v -> toggleVision());
        visionButton.setTextSize(12);
        topRow.addView(visionButton, new LinearLayout.LayoutParams(dp(64), dp(38)));
        Button controlLayoutButton = button("操控", v -> openControlLayoutDialog());
        topRow.addView(controlLayoutButton, new LinearLayout.LayoutParams(dp(60), dp(38)));
        lockButton = button("🔒 已上鎖", v -> toggleDriveLock());
        lockButton.setTextSize(12);
        lockButton.setContentDescription("車輛已上鎖；點擊解鎖並確認");
        topRow.addView(lockButton, new LinearLayout.LayoutParams(dp(90), dp(38)));
        modeButton = button("設定", v -> toggleMode());
        topRow.addView(modeButton, new LinearLayout.LayoutParams(dp(76), dp(38)));
        banner.addView(topRow);
        LinearLayout statusStrip = new LinearLayout(this);
        statusStrip.setOrientation(LinearLayout.HORIZONTAL);
        statusStrip.setGravity(Gravity.CENTER_VERTICAL);
        statusStrip.setBackgroundColor(Color.rgb(233, 241, 246));
        banner.addView(statusStrip, new LinearLayout.LayoutParams(-1, dp(28)));
        netStatus = label(statusStrip, "Wi-Fi：檢查中", 12);
        cameraStatus = label(statusStrip, "FPV：未連線", 12);
        driverLiveStatus = label(statusStrip, "AI：未連線", 12);
        aiDriveStatus = label(statusStrip, "油門：停止", 12);
        TextView[] instruments = {netStatus, cameraStatus, driverLiveStatus, aiDriveStatus};
        float[] weights = {1f, 1.25f, 1.25f, 1.3f};
        for (int i = 0; i < instruments.length; i++) {
            TextView status = instruments[i];
            status.setSingleLine(true);
            status.setEllipsize(android.text.TextUtils.TruncateAt.END);
            status.setGravity(Gravity.CENTER_VERTICAL);
            status.setPadding(dp(5), 0, dp(3), 0);
            status.setLayoutParams(new LinearLayout.LayoutParams(0, dp(28), weights[i]));
        }

        cockpit = new FrameLayout(this);
        cockpit.setBackgroundColor(Color.rgb(12, 20, 32));
        screen.addView(cockpit, new LinearLayout.LayoutParams(-1, 0, 1));
        cameraFrame = new FrameLayout(this);
        cameraFrame.setBackgroundColor(Color.rgb(22, 32, 46));
        // True full-bleed FPV; translucent driving controls are layered above.
        cockpit.addView(cameraFrame,
                new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        visionDebugShown = getPreferences(MODE_PRIVATE)
                .getBoolean("vision_debug_hud", false);

        preview = new ImageView(this);
        preview.setBackgroundColor(Color.rgb(22, 32, 46));
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cameraFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        hevcPreview = new TextureView(this);
        cameraFrame.addView(hevcPreview, new FrameLayout.LayoutParams(-1, -1));
        visionOverlay = new VisionOverlay(this, selected -> {
            if (control.visionFollowActive()) control.stopVisionFollow();
            log.i("VISION", "person target selected manually; previous follow cancelled");
        });
        cameraFrame.addView(visionOverlay, new FrameLayout.LayoutParams(-1, -1));
        visionOverlay.setVisionEnabled(false);
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
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                // Render callback timestamp, NOT hardware exposure timestamp.
                lastRenderedHevcAt = SystemClock.elapsedRealtime();
            }
        });

        settingsScroller = new ScrollView(this);
        settingsScroller.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // The right-side settings scroller must be able to clear the
        // persistent floating STOP overlay at its bottom edge.
        root.setPadding(dp(16), dp(15), dp(16), dp(88));
        root.setBackgroundColor(Color.rgb(248, 250, 252));
        settingsScroller.addView(root);
        settingsShell = new LinearLayout(this);
        settingsShell.setOrientation(LinearLayout.HORIZONTAL);
        settingsShell.setBackgroundColor(Color.rgb(248, 250, 252));
        LinearLayout settingsNavigation = new LinearLayout(this);
        settingsNavigation.setOrientation(LinearLayout.VERTICAL);
        settingsNavigation.setPadding(dp(5), dp(8), dp(5), dp(5));
        settingsNavigation.setBackgroundColor(Color.rgb(228, 236, 244));
        settingsSafetyLabel = new TextView(this);
        settingsSafetyLabel.setText("🔒 設定已上鎖");
        settingsSafetyLabel.setTextSize(12);
        settingsSafetyLabel.setTextColor(Color.rgb(65, 83, 100));
        settingsSafetyLabel.setGravity(Gravity.CENTER_VERTICAL);
        settingsSafetyLabel.setPadding(dp(7), 0, 0, 0);
        settingsNavigation.addView(settingsSafetyLabel,
                new LinearLayout.LayoutParams(-1, dp(28)));
        settingsShell.addView(settingsNavigation,
                new LinearLayout.LayoutParams(dp(184), -1));
        settingsShell.addView(settingsScroller,
                new LinearLayout.LayoutParams(0, -1, 1));
        String[] categories = {"車輛與連線", "操控設定", "AI 與攝影機", "進階與診斷"};
        settingsNavButtons = new Button[categories.length];
        for (int i = 0; i < categories.length; i++) {
            final int index = i;
            Button nav = button(categories[i], v -> setSettingsTab(index));
            nav.setTextSize(13);
            nav.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            nav.setMinHeight(0);
            nav.setMinimumHeight(0);
            // Four categories fit short landscape viewports without scrolling the rail.
            settingsNavigation.addView(nav,
                    new LinearLayout.LayoutParams(-1, 0, 1f));
            settingsNavButtons[i] = nav;
        }
        settingsShell.setVisibility(View.GONE);
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
                drivePad.setControlsAvailable(false);
                log.e("SAFETY", "dual-stick input blocked: locked");
                return;
            }
            control.setManual(true);
            String result = control.driveManualAxes(steer, throttle);
            if (result.startsWith("blocked")) log.e("SAFETY", result);
            else main.postDelayed(manualPulse, 180);
        });
        drivePad.setLayoutConfig(driverLayout);
        drivePad.setCenterTapListener((x, y) -> {
            if (visionOverlay != null) visionOverlay.selectAt(x, y);
        });
        drivePad.setControlsAvailable(false);
        cockpit.addView(drivePad, new FrameLayout.LayoutParams(-1, -1));
        visionDebugHud = new TextView(this);
        visionDebugHud.setTextSize(11);
        visionDebugHud.setTextColor(Color.WHITE);
        visionDebugHud.setBackgroundColor(Color.argb(190, 13, 27, 39));
        visionDebugHud.setPadding(dp(8), dp(4), dp(8), dp(4));
        visionDebugHud.setClickable(false);
        visionDebugHud.setFocusable(false);
        FrameLayout.LayoutParams debugParams = new FrameLayout.LayoutParams(
                -2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        debugParams.topMargin = dp(33);
        cockpit.addView(visionDebugHud, debugParams);
        updateVisionDebugHud();
        // Settings are an overlay; the TextureView is not detached or restarted.
        cockpit.addView(settingsShell, new FrameLayout.LayoutParams(-1, -1));
        // STOP is the last/topmost cockpit child, ABOVE driving touch and settings.
        // Right bottom is reserved by DualStickDriveView; this hitbox never
        // doubles as a reverse-throttle action.
        Button stopButton = button("■ 急停", v -> emergencyStopAndLock());
        stopButton.setContentDescription("緊急停止並上鎖");
        stopButton.setTextColor(Color.WHITE);
        stopButton.setTextSize(15);
        stopButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(185, 34, 49)));
        stopButton.setElevation(dp(12));
        // Do not wait for ACTION_UP: press-down is an immediate E-STOP even
        // when the other thumb is still holding an active throttle pointer.
        stopButton.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    emergencyStopAndLock();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    view.performClick(); // accessibility; idempotent STOP
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return true;
            }
        });
        FrameLayout.LayoutParams stopParams = new FrameLayout.LayoutParams(
                dp(DriveStopGeometry.STOP_WIDTH_DP), dp(DriveStopGeometry.STOP_HEIGHT_DP),
                Gravity.END | Gravity.BOTTOM);
        stopParams.rightMargin = dp(DriveStopGeometry.STOP_MARGIN_DP);
        stopParams.bottomMargin = dp(DriveStopGeometry.STOP_MARGIN_DP);
        cockpit.addView(stopButton, stopParams);
        cockpit.setMotionEventSplittingEnabled(true);
        // Capture the original form sections; preserve every existing input callback.
        int[] settingsCuts = new int[8];
        TextView heading = label(root, "車輛與連線", 17);
        heading.setTypeface(null, Typeface.BOLD);
        label(root, "選擇車型並確認 Wi-Fi。進入設定時車輛會先停止並上鎖，返回駕駛後再解鎖。", 13);
        row(root, button("開 Wi-Fi 設定", v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS))),
                button("網路診斷", v -> {
                    routes.diagnostics();
                    updateNetworkStatus();
                }));

        settingsCuts[0] = root.getChildCount(); // vehicle intro / Wi-Fi actions
        label(root, "1. FPV 攝影機", 17).setTypeface(null, Typeface.BOLD);
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
                if (switchingCarProfile) return;
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

        settingsCuts[1] = root.getChildCount(); // camera / FPV
        label(root, "2. 車型選擇與小車控制", 17).setTypeface(null, Typeface.BOLD);
        label(root, "目前可使用 WL Tech FPV 或自訂封包。切換車型前請先停止車輛。", 12);
        profile = new Spinner(this);
        profile.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                CarProfile.visibleOptions()));
        profile.setSelection(activeProfile.spinnerIndex);
        root.addView(profile);
        label(root, "切換車型會先停車上鎖，各車獨立保存 IP／影像／調校。", 12);
        profile.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                switchVehicle(CarProfile.visibleFromIndex(position));
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        settingsCuts[2] = root.getChildCount(); // vehicle profile
        label(root, "觸控按鍵與操控方式", 17).setTypeface(null, Typeface.BOLD);
        label(root, "調整左右按鍵、搖桿、油門位置和震動；不修改馬達輸出。", 12);
        root.addView(button("調整駕駛按鍵布局", v -> openControlLayoutDialog()),
                new LinearLayout.LayoutParams(-1, dp(48)));
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
                if (switchingCarProfile) return;
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
        settingsCuts[3] = root.getChildCount(); // control tuning
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

        settingsCuts[4] = root.getChildCount(); // advanced protocol fields
        // Keep the pre-existing checked-state listener as the internal arming
        // contract. It is deliberately not attached to the cockpit UI.
        // The compact header lock button is its sole user-facing trigger.
        arm = new CheckBox(this);
        arm.setOnCheckedChangeListener((view, checked) -> {
            if (changingConfig) {
                updateLockButton();
                return;
            }
            if (checked) {
                if (!applyConfig()) { uncheck(); return; }
                String issue = control.arm();
                if (issue != null) {
                    log.e("SAFETY", "arm denied: " + issue);
                    uncheck();
                } else {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    drivePad.setControlsAvailable(true);
                    updateTuningPreview();
                }
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                drivePad.setControlsAvailable(false);
                releaseManualInputs();
                control.disarm();
            }
            updateLockButton();
        });
        label(root, "按住前進／倒車會由本機持續送封包，直到放開、急停或失去控制更新。AI 油門保持可一次最多 30 秒，方向可以獨立微調；車端沒有 ACK。", 12);

        // The technical tuning indicator belongs in Control settings, not
        // a permanent bar below the FPV feed.
        tuningActive = label(root, "50ms｜轉向100%｜油門100%", 12);
        tuningActive.setSingleLine(false);

        settingsCuts[5] = root.getChildCount(); // control hold explanation
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
        label(root, "Gemini 語音駕駛：解鎖後直接說「前進一點／往前進／持續前進／往左前方」。" +
                "不需要額外導向授權。本地程式決定動作時間，到期自動停止；無測距與避障能力。", 12);
        root.addView(button("測試 AI 查詢工具（不移動）", v -> {
            if (!gemini.sendText("請先呼叫 get_car_status、get_camera_status、get_network_status，然後用繁體中文告訴我連線與安全狀態，不要移動小車。")) {
                log.e("TOOL", "Live disconnected; cannot request tool smoke test");
            } else log.i("TOOL", "read-only smoke test requested");
        }), new LinearLayout.LayoutParams(-1, dp(50)));
        transcript = label(root, "對話顯示區（僅保留最新訊息）", 13);
        transcript.setMinHeight(dp(50));

        settingsCuts[6] = root.getChildCount(); // Gemini Live
        label(root, "4. Debug（adb logcat -s CrewCar:D）", 17).setTypeface(null, Typeface.BOLD);
        CheckBox visionHudToggle = new CheckBox(this);
        visionHudToggle.setText("FPV 顯示 Vision 診斷 HUD（只有數字，不存影像）");
        visionHudToggle.setChecked(visionDebugShown);
        visionHudToggle.setOnCheckedChangeListener((button, checked) -> {
            visionDebugShown = checked;
            getPreferences(MODE_PRIVATE).edit()
                    .putBoolean("vision_debug_hud", checked).apply();
            updateVisionDebugHud();
        });
        root.addView(visionHudToggle, new LinearLayout.LayoutParams(-1, dp(48)));
        row(root, button("匯出 Debug 文字", v -> {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, log.snapshot());
            startActivity(Intent.createChooser(send, "分享 Crew Car Debug"));
        }), button("重新檢查網路", v -> routes.diagnostics()));
        logs = label(root, "", 11);
        logs.setTextIsSelectable(true);
        settingsCuts[7] = root.getChildCount(); // debug
        repartitionSettings(root, settingsCuts);
        switchingCarProfile = true;
        restoreVehicleSettings();
        switchingCarProfile = false;
        if (activeProfile == CarProfile.KL_FPV) cameraWanted = false;
        updateNetworkStatus();
        updateTxStatus();
        updateMode();
    }

    /**
     * Build category panels by MOVING existing form Views without reconstructing them.
     * This keeps all original preference bindings, listeners, spinners and callbacks.
     * Sections in original order:
     * vehicle intro, camera, vehicle profile, tuning, protocol, control notes,
     * Gemini, diagnostics.
     */
    private void repartitionSettings(LinearLayout root, int[] cuts) {
        if (!SettingsSectionRouter.validCuts(cuts, root.getChildCount()))
            throw new IllegalStateException("settings section boundaries changed");
        settingsSections = new LinearLayout[4];
        for (int i = 0; i < settingsSections.length; i++) {
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(0, 0, 0, dp(16));
            settingsSections[i] = content;
        }
        int count = cuts[7];
        for (int i = 0; i < count; i++) {
            View element = root.getChildAt(0);
            root.removeViewAt(0);
            int chunk = 0;
            while (chunk < cuts.length - 1 && i >= cuts[chunk]) chunk++;
            int section = SettingsSectionRouter.sectionForChunk(chunk);
            settingsSections[section].addView(element);
        }
        for (LinearLayout panel : settingsSections) root.addView(panel);
        setSettingsTab(0);
    }

    private void setSettingsTab(int selected) {
        if (settingsSections == null || selected < 0 || selected >= settingsSections.length)
            return;
        activeSettingsTab = selected;
        for (int i = 0; i < settingsSections.length; i++) {
            boolean active = selected == i;
            settingsSections[i].setVisibility(active ? View.VISIBLE : View.GONE);
            if (settingsNavButtons != null && settingsNavButtons[i] != null) {
                Button button = settingsNavButtons[i];
                button.setTextColor(active ? Color.WHITE : Color.rgb(28, 45, 60));
                button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        active ? Color.rgb(37, 81, 113) : Color.rgb(234, 241, 247)));
            }
        }
        if (settingsScroller != null)
            settingsScroller.post(() -> settingsScroller.scrollTo(0, 0));
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


    private String vehicleSavedText(String name, String fallback) {
        android.content.SharedPreferences p = getPreferences(MODE_PRIVATE);
        String key = activeProfile.settingKey(name);
        if (p.contains(key)) return p.getString(key, fallback);
        return activeProfile.usesLegacySettings() ? saved(name, fallback) : fallback;
    }

    private int vehicleSavedInt(String name, int fallback) {
        android.content.SharedPreferences p = getPreferences(MODE_PRIVATE);
        String key = activeProfile.settingKey(name);
        if (p.contains(key)) return p.getInt(key, fallback);
        return activeProfile.usesLegacySettings() ? p.getInt(name, fallback) : fallback;
    }

    private void storeVehicleSettings(CarProfile model) {
        if (host == null) return;
        android.content.SharedPreferences.Editor e = getPreferences(MODE_PRIVATE).edit();
        e.putString(model.settingKey("host"), host.getText().toString().trim());
        e.putString(model.settingKey("port"), port.getText().toString().trim());
        e.putString(model.settingKey("camera_url"), cameraUrl.getText().toString().trim());
        e.putInt(model.settingKey("camera_adapter"), cameraAdapter.getSelectedItemPosition());
        e.putInt(model.settingKey("transport"), transport.getSelectedItemPosition());
        e.putInt(model.settingKey("encoding"), encoding.getSelectedItemPosition());
        e.putInt(model.settingKey("tuning_interval"), selectedTuning().intervalMs);
        e.putInt(model.settingKey("tuning_steer"), selectedTuning().steeringPercent);
        e.putInt(model.settingKey("tuning_throttle"), selectedTuning().throttlePercent);
        e.putInt(model.settingKey("tuning_preset"), tuningPreset.getSelectedItemPosition());
        for (Map.Entry<String, EditText> entry : packets.entrySet())
            e.putString(model.settingKey("packet_" + entry.getKey()),
                    entry.getValue().getText().toString().trim());
        e.apply();
    }

    private void restoreVehicleSettings() {
        if (host == null) return;
        host.setText(vehicleSavedText("host", activeProfile.defaultHost()));
        port.setText(vehicleSavedText("port", activeProfile.defaultPort()));
        cameraUrl.setText(vehicleSavedText("camera_url", ""));
        transport.setSelection(vehicleSavedInt("transport", 0));
        encoding.setSelection(vehicleSavedInt("encoding", 0));
        for (Map.Entry<String, EditText> entry : packets.entrySet())
            entry.getValue().setText(vehicleSavedText("packet_" + entry.getKey(), ""));
        cameraAdapter.setSelection(vehicleSavedInt("camera_adapter",
                activeProfile == CarProfile.WL_TECH ? 0 : 1));
        tuningPreset.setSelection(vehicleSavedInt("tuning_preset", 1));
        tuningPeriod.setProgress((vehicleSavedInt("tuning_interval", 50) -
                CarTuning.MIN_INTERVAL_MS) / CarTuning.INTERVAL_STEP_MS);
        tuningSteer.setProgress((vehicleSavedInt("tuning_steer", 100) - 50) / 5);
        tuningThrottle.setProgress((vehicleSavedInt("tuning_throttle", 100) - 50) / 5);
        updateTuningPreview();
    }

    private void switchVehicle(CarProfile next) {
        if (next == activeProfile || host == null) return;
        emergencyStopAndLock();
        stopCameraReceiver();
        storeVehicleSettings(activeProfile);
        activeProfile = next;
        switchingCarProfile = true;
        try {
            restoreVehicleSettings();
        } finally {
            switchingCarProfile = false;
        }
        vehicleTitle.setText("Crew Car · " + next.label);
        getPreferences(MODE_PRIVATE).edit()
                .putInt("protocol_profile", next.spinnerIndex).apply();
        cameraWanted = next != CarProfile.KL_FPV;
        cameraManualRequested = false;
        cameraStatus.setText(next == CarProfile.KL_FPV ?
                "KL FPV 協定未驗證：馬達與影像封包停用" :
                "已切換車型；請連接對應小車的 Wi-Fi");
        if (applyConfig()) {
            log.i("CAR", "selected model=" + next.id);
            maybeStartCamera();
        }
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

    /** Center-crop the H.265 stream to fill the cockpit without stretching people. */
    private void fitCameraAspect(int width, int height) {
        if (hevcPreview == null || width <= 0 || height <= 0) return;
        float desired = 16f / 9f;
        float actual = width / (float) height;
        Matrix matrix = new Matrix();
        if (actual > desired) {
            matrix.setScale(1f, actual / desired, width / 2f, height / 2f);
        } else {
            matrix.setScale(desired / actual, 1f, width / 2f, height / 2f);
        }
        hevcPreview.setTransform(matrix);
    }

    private void toggleMode() {
        // Opening settings must never leave a motor command running behind the overlay.
        if (drivingMode) emergencyStopAndLock();
        drivingMode = !drivingMode;
        updateMode();
    }

    /** Compact arming affordance; never automatically unlock on screen changes. */
    private void updateLockButton() {
        if (lockButton == null || control == null) return;
        boolean armedNow = control.armed() && arm != null && arm.isChecked();
        lockButton.setText(armedNow ? "🔓 已解鎖" : "🔒 已上鎖");
        lockButton.setTextColor(armedNow ? Color.rgb(15, 95, 68) : Color.rgb(52, 65, 81));
        lockButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                armedNow ? Color.rgb(204, 245, 223) : Color.rgb(225, 233, 241)));
        lockButton.setContentDescription(!drivingMode ? "設定模式，車輛已上鎖" :
                armedNow ? "車輛已解鎖，點擊立即停止並上鎖" :
                        "車輛已上鎖，點擊確認解鎖");
        lockButton.setEnabled(drivingMode);
    }

    private void toggleDriveLock() {
        if (!drivingMode || !foreground || arm == null || control == null) return;
        if (control.armed()) {
            emergencyStopAndLock();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("解鎖駕駛")
                .setMessage("請先確認周圍安全、車輛已連線且急停可使用。" +
                        "解鎖後仍需按住前進或倒車才會移動。")
                .setNegativeButton("取消", null)
                .setPositiveButton("確認解鎖", (dialog, which) -> {
                    if (!foreground || !drivingMode || arm == null || control.armed())
                        return;
                    arm.setChecked(true); // reuse the original safety validation path
                    updateLockButton();
                    if (!control.armed())
                        android.widget.Toast.makeText(this,
                                "無法解鎖：請確認車型、Wi-Fi 與控制設定",
                                android.widget.Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void updateMode() {
        if (settingsShell == null || drivePad == null || modeButton == null) return;
        drivePad.setVisibility(drivingMode ? View.VISIBLE : View.GONE);
        settingsShell.setVisibility(drivingMode ? View.GONE : View.VISIBLE);
        if (settingsSafetyLabel != null)
            settingsSafetyLabel.setVisibility(drivingMode ? View.GONE : View.VISIBLE);
        modeButton.setText(drivingMode ? "設定" : "返回駕駛");
        updateLockButton();
    }

    private DriverLayoutConfig loadDriverLayout() {
        android.content.SharedPreferences p = getPreferences(MODE_PRIVATE);
        try {
            return new DriverLayoutConfig(p.getInt("driver_side", 20),
                    p.getInt("driver_gap", 10), p.getInt("driver_offset", 0),
                    p.getInt("driver_deadzone", 26), p.getBoolean("driver_buttons", true),
                    p.getBoolean("driver_haptics", true));
        } catch (IllegalArgumentException ignored) {
            return DriverLayoutConfig.defaults();
        }
    }

    private void saveDriverLayout(DriverLayoutConfig cfg) {
        getPreferences(MODE_PRIVATE).edit()
                .putInt("driver_side", cfg.sidePercent).putInt("driver_gap", cfg.gapDp)
                .putInt("driver_offset", cfg.offsetDp)
                .putInt("driver_deadzone", cfg.deadZonePercent)
                .putBoolean("driver_buttons", cfg.buttonSteering)
                .putBoolean("driver_haptics", cfg.haptics).apply();
    }

    /** The camera resizes with the actual cockpit width, not screen metrics. */
    private void updateCameraWidth() {
        if (cockpit == null || cameraFrame == null || cockpit.getWidth() <= 0) return;
        int width = cockpit.getWidth();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cameraFrame.getLayoutParams();
        if (lp.width != width) {
            lp.width = width;
            lp.gravity = Gravity.CENTER;
            cameraFrame.setLayoutParams(lp);
        }
    }

    private void visualSlider(LinearLayout box, String title, int min, int max,
                              int value, java.util.function.IntConsumer change) {
        TextView info = label(box, title + "：" + value, 13);
        SeekBar slider = new SeekBar(this);
        slider.setMax(max - min);
        slider.setProgress(value - min);
        box.addView(slider, new LinearLayout.LayoutParams(-1, dp(33)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int selected = min + progress;
                info.setText(title + "：" + selected);
                change.accept(selected);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
    }

    /** Lightweight quick-settings popup; the main Settings page will be redesigned separately. */
    private void openControlLayoutDialog() {
        emergencyStopAndLock(); // changing hit targets during motion is unsafe
        int[] side = {driverLayout.sidePercent}, gap = {driverLayout.gapDp};
        int[] offset = {driverLayout.offsetDp}, deadzone = {driverLayout.deadZonePercent};
        ScrollView scroller = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(16), dp(8), dp(16), dp(12));
        box.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(box);
        label(box, "轉向方式（套用後需重新解鎖）", 13);
        Spinner mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"左右大按鍵", "虛擬搖桿"}));
        mode.setSelection(driverLayout.buttonSteering ? 0 : 1);
        box.addView(mode, new LinearLayout.LayoutParams(-1, dp(46)));
        visualSlider(box, "控制區寬度 %", 18, 26, side[0], v -> side[0] = v);
        visualSlider(box, "油門按鍵間距 dp", 8, 30, gap[0], v -> gap[0] = v);
        visualSlider(box, "油門上下位置 dp", -24, 24, offset[0], v -> offset[0] = v);
        visualSlider(box, "搖桿死區 %", 15, 35, deadzone[0], v -> deadzone[0] = v);
        CheckBox haptic = new CheckBox(this);
        haptic.setText("啟用輕微觸覺回饋");
        haptic.setChecked(driverLayout.haptics);
        box.addView(haptic);
        label(box, "僅影響觸控版面，不變更馬達封包、續控與安全逾時。", 12);
        new AlertDialog.Builder(this)
                .setTitle("駕駛操控版面")
                .setView(scroller)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢復預設", (d, w) -> applyDriverLayout(DriverLayoutConfig.defaults()))
                .setPositiveButton("套用", (d, w) -> applyDriverLayout(new DriverLayoutConfig(
                        side[0], gap[0], offset[0], deadzone[0],
                        mode.getSelectedItemPosition() == 0, haptic.isChecked())))
                .show();
    }

    private void applyDriverLayout(DriverLayoutConfig cfg) {
        emergencyStopAndLock();
        driverLayout = cfg;
        saveDriverLayout(cfg);
        if (drivePad != null) drivePad.setLayoutConfig(cfg);
        updateCameraWidth();
        log.i("UI", "driver layout changed side=" + cfg.sidePercent +
                "% steeringButtons=" + cfg.buttonSteering + " no motor-timing changes");
    }

    private void updateNetworkStatus() {
        if (netStatus != null) {
            Network wifi = routes.wifi();
            netStatus.setText(wifi == null ? "● Wi-Fi 未連" : "● Wi-Fi 已連");
            netStatus.setTextColor(wifi == null ? Color.rgb(160, 60, 55) :
                    Color.rgb(32, 116, 98));
        }
    }

    private void uncheck() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        changingConfig = true;
        arm.setChecked(false);
        if (drivePad != null) drivePad.setControlsAvailable(false);
        changingConfig = false;
        releaseManualInputs();
        control.disarm();
        updateLockButton();
    }

    private boolean applyConfig() {
        try {
            CarControl.Config cfg = new CarControl.Config();
            cfg.host = host.getText().toString().trim();
            cfg.carProfile = activeProfile;
            cfg.wltoys = activeProfile == CarProfile.WL_TECH;
            String rawPort = port.getText().toString().trim();
            cfg.port = cfg.wltoys ? WltoysProtocol.CONTROL_PORT :
                    (rawPort.isEmpty() ? 0 : Integer.parseInt(rawPort));
            cfg.tcp = !cfg.wltoys && transport.getSelectedItemPosition() == 1;
            cfg.hex = encoding.getSelectedItemPosition() == 0;
            cfg.tuning = selectedTuning();
            for (Map.Entry<String, EditText> entry : packets.entrySet())
                cfg.payloads.put(entry.getKey(), entry.getValue().getText().toString().trim());
            storeVehicleSettings(activeProfile);
            android.content.SharedPreferences.Editor prefs = getPreferences(MODE_PRIVATE).edit();
            prefs.putInt("protocol_profile", activeProfile.spinnerIndex);
            // Retain old preference keys for both pre-existing modes, never KL.
            if (activeProfile.usesLegacySettings()) {
                prefs.putString("host", cfg.host).putString("port", String.valueOf(cfg.port))
                        .putString("camera_url", cameraUrl.getText().toString().trim())
                        .putInt("transport", transport.getSelectedItemPosition())
                        .putInt("encoding", encoding.getSelectedItemPosition())
                        .putInt("camera_adapter", cameraAdapter.getSelectedItemPosition())
                        .putInt("tuning_interval", cfg.tuning.intervalMs)
                        .putInt("tuning_steer", cfg.tuning.steeringPercent)
                        .putInt("tuning_throttle", cfg.tuning.throttlePercent)
                        .putInt("tuning_preset", tuningPreset.getSelectedItemPosition());
                for (Map.Entry<String, EditText> entry : packets.entrySet())
                    prefs.putString("packet_" + entry.getKey(),
                            entry.getValue().getText().toString().trim());
            }
            prefs.apply();
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
        if (activeProfile == CarProfile.KL_FPV) {
            if (cameraStatus != null)
                cameraStatus.setText("KL FPV 影像協定未驗證：串流已停用");
            return;
        }
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
        lastRenderedHevcAt = 0;
        visionMetrics.reset();
        // A restarted camera must not inherit detections from the old feed/session.
        if (control != null) control.stopVisionFollow();
        if (visionEnabled) {
            crewVision.setEnabled(false);
            visionEnabled = false;
            updateVisionButton();
        }
        if (visionOverlay != null) visionOverlay.setVisionEnabled(false);
        lastCameraFrame = 0;
        lastFrameWidth = 0;
        lastFrameHeight = 0;
    }

    private void startCamera(long token) {
        if (activeProfile == CarProfile.KL_FPV) return;
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
                if (visionEnabled) crewVision.submit(frame, false);
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
        control.stopVisionFollow();
        releaseManualInputs();
        if (drivePad != null) drivePad.setControlsAvailable(false);
        control.disarm();
        control.emergencyStop();
        if (arm != null && arm.isChecked()) {
            changingConfig = true;
            arm.setChecked(false);
            changingConfig = false;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateLockButton();
        log.i("SAFETY", "USER E-STOP: disarmed, manual re-arm required");
    }

    /** Local rendered-image / screen-capture recency; not sensor exposure latency. */
    private long visionCameraAgeMs() {
        long at = currentCameraAdapter == 0 ? lastRenderedHevcAt : lastCameraFrame;
        return at <= 0 ? -1 : Math.max(0, SystemClock.elapsedRealtime() - at);
    }

    private void updateVisionDebugHud() {
        if (visionDebugHud == null) return;
        boolean visible = visionDebugShown && drivingMode && visionEnabled;
        visionDebugHud.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible || control == null) return;
        long age = visionMetrics.resultAgeMs(SystemClock.elapsedRealtime());
        PersonTracker.Box target = visionOverlay == null ? null : visionOverlay.selectedTarget();
        String info = String.format(java.util.Locale.US,
                "Vision %.1f FPS  |  推論 %dms  |  擷取→回報 %dms  |  影格 %s\n" +
                "人數 %d  |  目標 %.0f%%  |  中心偏差 %+.2f  |  %s",
                visionMetrics.fps(), visionMetrics.inferenceMs(),
                visionMetrics.processingMs(), age < 0 ? "無" : age + "ms",
                visionMetrics.people(), target == null ? 0f : target.score * 100f,
                target == null ? 0f : target.centerX() - .5f, control.owner());
        String stop = control.lastStopReason();
        visionDebugHud.setText(info + "\n停止原因：" + stop +
                " | STOP 本地送出 " + control.lastStopLocalDelayMs() +
                "ms（無車端 ACK）");
    }

    private void updateVisionButton() {
        if (visionButton == null) return;
        visionButton.setText(visionEnabled ? "辨識 ●" : "辨識");
        visionButton.setContentDescription(visionEnabled ?
                "關閉本地人物辨識，取消所有目標" : "開啟本地人物辨識（不會操作馬達）");
    }

    private void toggleVision() {
        control.stopVisionFollow();
        visionEnabled = !visionEnabled;
        visionMetrics.reset();
        if (visionOverlay != null) visionOverlay.setVisionEnabled(visionEnabled);
        crewVision.setEnabled(visionEnabled);
        updateVisionButton();
        updateVisionDebugHud();
        log.i("VISION", visionEnabled ?
                "offline person detection enabled; motor follow separately authorized" :
                "person detection stopped, target cleared");
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
            @Override public String onDriveIntent(String intent) {
                if (!foreground || !drivingMode || !gemini.ready() ||
                        activeProfile != CarProfile.WL_TECH || routes.wifi() == null ||
                        !control.armed())
                    return "blocked: Gemini/WL Wi-Fi/foreground and manual unlock required";
                return control.driveIntent(intent);
            }
            @Override public String onSteer(String direction, int holdMs) {
                if (!control.armed()) return "blocked: manual safety lock";
                if (!control.aiHoldActive())
                    return "blocked: no active AI cruise; start with set_drive_state first";
                if (cameraAgeMs() < 0 || cameraAgeMs() > 2500 ||
                        !gemini.ready() || gemini.liveVideoAgeMs() < 0 ||
                        gemini.liveVideoAgeMs() > 2500 || routes.wifi() == null)
                    return "blocked: camera/Live/Wi-Fi stale";
                return control.steerAi(direction, holdMs);
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
                        "motor_authority", control.owner().name(),
                        "voice_drive_intent_available", activeProfile == CarProfile.WL_TECH,
                        "voice_drive_intent_active", control.aiHoldActive(),
                        "voice_drive_intent_requires_extra_approval", false,
                        "voice_drive_intent_requires_manual_arm", true,
                        "voice_drive_intent_distance_measured", false,
                        "last_stop_reason", control.lastStopReason(),
                        "last_stop_local_send_delay_ms", control.lastStopLocalDelayMs(),
                        "last_stop_sent_at_elapsed_ms", control.lastStopSentAt(),
                        "stop_burst_attempts", control.stopBurstAttempts(),
                        "pulse_remaining_ms", control.remainingMs(),
                        "ai_hold_active", control.aiHoldActive(),
                        "ai_steering", control.aiSteering(),
                        "ai_throttle", control.aiThrottle(),
                        "ai_lease_remaining_ms", control.aiLeaseRemainingMs(),
                        "manual_held_steering", heldSteer,
                        "manual_held_throttle", heldThrottle,
                        "car_profile", activeProfile.id,
                        "kl_protocol_verified", KlFpvProtocol.isVerified(),
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
                        "adapter", activeProfile == CarProfile.KL_FPV ? "KL unverified (disabled)" :
                                currentCameraAdapter == 0 ? "WLtoys 6405 UDP HEVC candidate" : "HTTP JPEG/MJPEG",
                        "frames_received", lastCameraFrame != 0,
                        "last_frame_age_ms", age,
                        "fresh", age >= 0 && age <= 2500,
                        "width", lastFrameWidth,
                        "height", lastFrameHeight,
                        "live_video_frame_queue_age_ms", liveAge,
                        "live_video_fresh", liveAge >= 0 && liveAge <= 2500,
                        "live_video_frames_queued", gemini.liveVideoFramesQueued(),
                        "scene_recognition_available_here", visionEnabled,
                        "local_person_detector_enabled", visionEnabled,
                        "local_person_detector_ready", crewVision != null && crewVision.ready(),
                        "people_detected", visionOverlay == null ? 0 : visionOverlay.personCount(),
                        "selected_target", visionOverlay != null && visionOverlay.hasSelection(),
                        "selected_target_lost", visionOverlay != null && visionOverlay.targetLost(),
                        "vision_follow_capable", control.candidateProtocol() &&
                                activeProfile == CarProfile.WL_TECH,
                        "vision_follow_active", control.visionFollowActive(),
                        "vision_follow_available_in_ui", false,
                        "vision_can_control_motors", control.visionFollowActive(),
                        "vision_inference_fps", (double) visionMetrics.fps(),
                        "vision_inference_ms", visionMetrics.inferenceMs(),
                        "vision_capture_to_callback_ms", visionMetrics.processingMs(),
                        "vision_result_age_ms", visionMetrics.resultAgeMs(SystemClock.elapsedRealtime()),
                        "vision_stop_reason", control.lastStopReason(),
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
        main.removeCallbacks(visionSampler);
        if (control != null) control.stopVisionFollow("app background");
        visionMetrics.reset();
        if (crewVision != null) crewVision.setEnabled(false);
        visionEnabled = false;
        if (visionOverlay != null) visionOverlay.setVisionEnabled(false);
        updateVisionButton();
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
        main.removeCallbacks(visionSampler);
        main.post(visionSampler);
        updateTalkButtons();
        updateVisionButton();
        updateLockButton();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(cameraMonitor);
        main.removeCallbacks(aiDriveTicker);
        main.removeCallbacks(visionSampler);
        main.removeCallbacks(saveGeminiKeyLater);
        stopCameraReceiver();
        liveConnectToken++;
        routes.releaseCellularForLive();
        if (gemini != null) gemini.disconnect();
        if (crewVision != null) crewVision.close();
        if (control != null) control.shutdown();
        super.onDestroy();
    }
}
