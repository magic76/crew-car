package com.crew.car;

import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Sole serial writer to the car. An explicit STOP packet is sent when movement times out. */
public final class CarControl {
    /** Watchdog checks stay responsive even with a slower user-selected transmit cadence. */
    private static final long WATCHDOG_PERIOD_MS = 25;

    public static final class Config {
        public String host;
        public int port;
        public boolean tcp;
        public boolean hex;
        /** Open-source candidate firmware profile; user must still explicitly arm. */
        public boolean wltoys;
        public CarProfile carProfile = CarProfile.WL_TECH;
        public CarTuning tuning = CarTuning.OEM;
        public final Map<String, String> payloads = new HashMap<>();
    }
    private final CarNetwork network;
    private final CarLog log;
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
    private volatile Config config = new Config();
    private volatile boolean armed;
    private volatile boolean manual;
    private volatile long deadline;
    private volatile String active = "stop";
    private volatile boolean aiHoldActive;
    private volatile boolean visionFollowActive;
    private volatile long lastDriveIntentAt;
    private volatile long visionFollowLimitAt;
    /** Local observation-only alignment telemetry: application times, NOT RC acknowledgements. */
    private volatile long visionAlignPulseAcceptedAt;
    private volatile long visionAlignPulseStoppedAt;
    private volatile BooleanSupplier visionSafetyGate = () -> false;
    private volatile int aiSteer, aiThrottle;
    /** Expiring steering correction; 0 = latch current direction until explicitly changed. */
    private volatile long aiSteerReleaseAt;
    /** Voice diagonals stop both axes when steering ends; no straight throttle tail. */
    private volatile boolean aiStopWithSteering;
    private volatile BooleanSupplier aiSafetyGate = () -> false;
    private volatile BooleanSupplier aiVideoGate = () -> false;
    private volatile boolean aiVideoGuarded;
    /** Session ownership persists while steering changes; throttle never toggles to STOP on a turn. */
    private volatile boolean aiDriveSessionPending, aiDriveSessionActive;
    private volatile long aiDriveSessionId, aiDriveSessionStartedAt, aiDriveSessionLastControlAt;
    private volatile int aiDriveSpeedGain = AiDriveSessionPolicy.NORMAL_GAIN;
    private long aiDriveSessionSequence;
    private volatile long lastAiSafetyCheckAt;
    private final AtomicLong motionEpoch = new AtomicLong();
    private volatile String lastTxCommand = "none";
    private volatile String lastTxStatus = "not_sent";
    private volatile long lastTxAt;
    private volatile long lastStopLogAt;
    private volatile String lastStopReason = "not_requested";
    private volatile long lastStopRequestedAt;
    private volatile long lastStopSentAt;
    private volatile long lastStopLocalDelayMs = -1;
    private volatile long stopBurstAttempts;
    private static final int[] STOP_RETRY_DELAYS_MS = {45, 110};
    private ScheduledFuture<?> transmitTicker;
    private volatile long txPackets;
    private volatile long txFailures;
    private volatile long lastTxGapMs = -1;
    private volatile long maxTxGapMs;
    private volatile long lastSendDurationMs;
    private long lastDetailedTxLogAt;
    private String lastLoggedDirection = "";

    public CarControl(CarNetwork network, CarLog log) {
        this.network = network;
        this.log = log;
        io.scheduleAtFixedRate(() -> {
            if (visionFollowActive) {
                long now = SystemClock.elapsedRealtime();
                boolean safe;
                try { safe = visionSafetyGate.getAsBoolean(); }
                catch (Exception ignored) { safe = false; }
                if (!armed || manual || !safe || now >= visionFollowLimitAt ||
                        now >= deadline) {
                    boolean timedOut = now >= visionFollowLimitAt;
                    visionFollowActive = false;
                    if (timedOut || !safe) armed = false;
                    boolean alignmentPulse = active.startsWith("vision-align:");
                    stopInternal(timedOut ? "vision session limit" :
                            !safe ? "vision camera/target gate stale" :
                                    alignmentPulse ? "vision align pulse lease expired" :
                                            "vision input lease expired");
                    return;
                }
            }
            if (aiHoldActive && armed) {
                long now = SystemClock.elapsedRealtime();
                if (aiDriveSessionActive && AiDriveSessionPolicy.shouldStop(now,
                        aiDriveSessionStartedAt, aiDriveSessionLastControlAt)) {
                    stopInternal(AiDriveSessionPolicy.stopReason(now,
                            aiDriveSessionStartedAt, aiDriveSessionLastControlAt));
                    return;
                }
                // Expiry has priority over re-centering: never send a final
                // straight-throttle packet when a diagonal lease has ended.
                if (DriveIntentPolicy.mustStopBothAxes(now, deadline,
                        aiSteerReleaseAt, aiStopWithSteering)) {
                    stopInternal(aiStopWithSteering ?
                            "AI diagonal: steering and throttle stopped together" :
                            "AI lease expired");
                    return;
                }
                if (aiSteerReleaseAt > 0 && now >= aiSteerReleaseAt) {
                    aiSteerReleaseAt = 0;
                    aiStopWithSteering = false;
                    // Recenter steering but KEEP video watchdog and forward throttle.
                    aiSteer = 0;
                    String command = aiDriveSessionActive ?
                            "ai-session:0:" + aiThrottle + ":" + aiDriveSpeedGain :
                            "axes:0:" + aiThrottle;
                    if (!command.equals(active)) {
                        active = command;
                        sendInternal(command);
                    }
                    log.i("AI", "steering correction ended; throttle still held");
                }
                if (now - lastAiSafetyCheckAt >= 100) {
                    lastAiSafetyCheckAt = now;
                    boolean drivingGate = false;
                    boolean videoFresh = false;
                    try {
                        drivingGate = aiSafetyGate.getAsBoolean();
                        videoFresh = drivingGate &&
                                (!aiVideoGuarded || aiVideoGate.getAsBoolean());
                    } catch (Exception ignored) { videoFresh = false; }
                    if (!videoFresh) {
                        // Always stop motors, but a temporarily stale FPV frame
                        // must not permanently lock an otherwise connected manual pad.
                        boolean carWifiGone = network.wifi() == null;
                        if (carWifiGone) armed = false;
                        stopInternal(carWifiGone ? "Car Wi-Fi disconnected" :
                                !drivingGate ? "Gemini Live/foreground gate stale" :
                                        "AI FPV video queue stale");
                        return;
                    }
                }
            }
            if (armed && network.wifi() == null) {
                log.e("SAFETY", "Car Wi-Fi disconnected; stopping and disarming");
                armed = false;
                emergencyStop("Car Wi-Fi disconnected");
                return;
            }
            if (!"stop".equals(active) && SystemClock.elapsedRealtime() > deadline)
                stopInternal("watchdog");
        }, WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
        transmitTicker = scheduleTransmitter(CarTuning.OEM);
    }

    private ScheduledFuture<?> scheduleTransmitter(CarTuning tuning) {
        // Fixed-delay prevents a slow phone/UDP stack from creating catch-up packet bursts.
        return io.scheduleWithFixedDelay(() -> {
            if (armed && config.wltoys) {
                if (!"stop".equals(active) &&
                        SystemClock.elapsedRealtime() >= deadline) {
                    stopInternal(active.startsWith("vision-align:") ?
                            "vision align pulse lease expired" :
                            "motor deadline reached before transmit");
                } else {
                    sendInternal(active);
                }
            }
        }, tuning.intervalMs, tuning.intervalMs, TimeUnit.MILLISECONDS);
    }

    private void resetMetrics() {
        lastTxAt = 0;
        lastTxGapMs = -1;
        maxTxGapMs = 0;
        lastSendDurationMs = 0;
        lastDetailedTxLogAt = 0;
        lastLoggedDirection = "";
        txPackets = 0;
        txFailures = 0;
    }
    public MotorAuthorityPolicy.Owner owner() {
        return MotorAuthorityPolicy.current(armed, manual,
                aiHoldActive, visionFollowActive);
    }
    public String lastStopReason() { return lastStopReason; }
    public long lastStopLocalDelayMs() { return lastStopLocalDelayMs; }
    public long lastStopAgeMs() {
        return lastStopRequestedAt <= 0 ? -1 :
                Math.max(0L, SystemClock.elapsedRealtime() - lastStopRequestedAt);
    }
    public long lastStopSentAt() { return lastStopSentAt; }
    public long stopBurstAttempts() { return stopBurstAttempts; }
    public boolean armed() { return armed; }
    public boolean manual() { return manual; }
    public void setManual(boolean value) {
        if (value && (aiHoldActive || aiDriveSessionPending || visionFollowActive))
            emergencyStop("manual takeover");
        manual = value;
    }
    public void setAiSafetyGate(BooleanSupplier gate) {
        aiSafetyGate = gate == null ? () -> false : gate;
    }

    /** Camera/JPEG queue freshness is a watchdog, not depth or obstacle detection. */
    public void setAiVideoGate(BooleanSupplier gate) {
        aiVideoGate = gate == null ? () -> false : gate;
    }
    public boolean visionFollowActive() { return visionFollowActive && armed && SystemClock.elapsedRealtime() < visionFollowLimitAt; }
    public long visionAlignPulseAcceptedAt() { return visionAlignPulseAcceptedAt; }
    public long visionAlignPulseStoppedAt() { return visionAlignPulseStoppedAt; }
    public void setVisionSafetyGate(BooleanSupplier gate) {
        visionSafetyGate = gate == null ? () -> false : gate;
    }
    public boolean aiHoldActive() { return aiHoldActive && armed && deadline > SystemClock.elapsedRealtime(); }
    public boolean aiDriveSessionActive() {
        return aiDriveSessionActive && aiHoldActive() && !manual;
    }
    public long aiDriveSessionId() {
        return aiDriveSessionActive || aiDriveSessionPending ? aiDriveSessionId : 0;
    }
    /** Actual requested packet gain after the user's manual tuning ceiling. */
    public int aiDriveSpeedGain() {
        return aiDriveSessionActive() ?
                Math.min(aiDriveSpeedGain, config.tuning.throttlePercent) : 0;
    }
    public long aiDriveLastControlAgeMs() {
        return !aiDriveSessionActive() ? -1 :
                Math.max(0L, SystemClock.elapsedRealtime() - aiDriveSessionLastControlAt);
    }
    public int aiSteering() { return aiHoldActive() ? aiSteer : 0; }
    public int aiThrottle() { return aiHoldActive() ? aiThrottle : 0; }
    public long aiLeaseRemainingMs() {
        return aiHoldActive() ? Math.max(0, deadline - SystemClock.elapsedRealtime()) : 0;
    }
    public String activeCommand() { return active; }
    public long remainingMs() { return "stop".equals(active) ? 0 : Math.max(0, deadline - SystemClock.elapsedRealtime()); }
    public String lastTxCommand() { return lastTxCommand; }
    public String lastTxStatus() { return lastTxStatus; }
    public long lastTxAgeMs() { return lastTxAt == 0 ? -1 : Math.max(0, SystemClock.elapsedRealtime() - lastTxAt); }
    public boolean candidateProtocol() { return config.wltoys; }
    public CarTuning tuning() { return config.tuning; }
    public long txPackets() { return txPackets; }
    public long txFailures() { return txFailures; }
    public long lastTxGapMs() { return lastTxGapMs; }
    public long maxTxGapMs() { return maxTxGapMs; }
    public long lastSendDurationMs() { return lastSendDurationMs; }
    public String diagnosticSummary() {
        return "控制權 " + owner() + " | 最後停止 " + lastStopReason +
                " | 本地停車送出延遲 " + lastStopLocalDelayMs +
                "ms（非車端 ACK） | 停車重送 " + stopBurstAttempts + " | 設定週期 " +
                config.tuning.intervalMs + "ms | 本機傳送 " + txPackets +
                " | 失敗 " + txFailures + " | 上次間隔 " +
                (lastTxGapMs < 0 ? "—" : lastTxGapMs + "ms") +
                " | 最長間隔 " + maxTxGapMs + "ms | 最後送出耗時 " +
                lastSendDurationMs + "ms（不是車端回應時間）";
    }

    public synchronized void configure(Config next) {
        if (next == null || next.tuning == null || next.carProfile == null)
            throw new IllegalArgumentException("missing tuning or vehicle");
        if (next.carProfile == CarProfile.KL_FPV && next.wltoys)
            throw new IllegalArgumentException("Cannot send WL commands to KL");
        motionEpoch.incrementAndGet();
        aiDriveSessionPending = false;
        aiDriveSessionActive = false;
        aiHoldActive = false;
        visionFollowActive = false;
        visionFollowLimitAt = 0;
        aiSteerReleaseAt = 0;
        aiStopWithSteering = false;
        aiVideoGuarded = false;
        boolean stopPrevious = armed || !"stop".equals(active);
        armed = false;
        manual = false;
        deadline = 0;
        active = "stop";
        Config previous = config;
        config = next;
        io.execute(() -> {
            if (stopPrevious && hasStopPayload(previous)) {
                sendInternal("stop", previous);
                log.i("SAFETY", "STOP previous endpoint on reconfigure");
            }
            if (transmitTicker != null) transmitTicker.cancel(false);
            resetMetrics();
            transmitTicker = scheduleTransmitter(next.tuning);
        });
        log.i("CTRL", "tuning=" + next.tuning +
                " config host=" + next.host + ":" + next.port +
                " transport=" + (next.tcp ? "TCP" : "UDP") + " payload=" + (next.hex ? "HEX" : "TEXT") +
                " model=" + next.carProfile.id + " wltoysCandidate=" + next.wltoys);
    }

    public synchronized String arm() {
        if (config.carProfile == CarProfile.KL_FPV)
            return KlFpvProtocol.BLOCKED_REASON;
        if (network.wifi() == null) return "沒有 Wi-Fi 連線";
        if (config.host == null || config.host.isBlank() || config.port < 1 || config.port > 65535)
            return "請設定控制 IP / Port";
        try {
            if (config.wltoys) {
                if (config.tcp || config.port != WltoysProtocol.CONTROL_PORT)
                    return "WLtoys profile requires UDP port 23458";
                WltoysProtocol.control("stop");
            } else {
                for (String d : new String[]{"forward","backward","left","right","stop"})
                    CommandCodec.encode(config.payloads.get(d), config.hex);
            }
        } catch (IllegalArgumentException e) { return e.getMessage(); }
        armed = true;
        log.i("SAFETY", "控制已解鎖；" + config.tuning + "；仍需實車確認");
        return null;
    }

    public void disarm() {
        aiDriveSessionPending = false;
        aiDriveSessionActive = false;
        visionFollowActive = false;
        aiHoldActive = false;
        aiSteerReleaseAt = 0;
        aiStopWithSteering = false;
        aiVideoGuarded = false;
        boolean stopCurrent = armed || !"stop".equals(active);
        armed = false;
        manual = false;
        if (stopCurrent && hasStopPayload(config)) emergencyStop();
        log.i("SAFETY", "控制已上鎖");
    }

    private boolean hasStopPayload(Config c) {
        if (c == null || c.carProfile == CarProfile.KL_FPV || c.host == null ||
                c.host.isBlank() || c.port < 1 || c.port > 65535)
            return false;
        if (c.wltoys) return !c.tcp && c.port == WltoysProtocol.CONTROL_PORT;
        try {
            CommandCodec.encode(c.payloads.get("stop"), c.hex);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Duration is capped even if the model asks for an unbounded motion. */
    public String drive(String direction, int requestedMs, boolean fromAi) {
        try {
            direction = CommandCodec.direction(direction);
            if ("stop".equals(direction)) { emergencyStop(); return "stopped"; }
            if (!armed) return "blocked: control locked";
            if (fromAi && manual) return "blocked: manual priority";
            if (fromAi && visionFollowActive) return "blocked: visual follow owns controls";
            if (fromAi && aiHoldActive)
                return "blocked: throttle is held; use steer_car or set_drive_state with throttle=keep (no brake sent)";
            if (fromAi && !MotorAuthorityPolicy.canClaim(owner(), MotorAuthorityPolicy.Owner.GEMINI))
                return "blocked: motor authority unavailable";
            int ms = Math.max(100, Math.min(fromAi ? 650 : 500, requestedMs));
            final String command = direction;
            final long epoch = motionEpoch.get();
            io.execute(() -> {
                if (!armed || motionEpoch.get() != epoch || (fromAi && manual) ||
                        (fromAi && !MotorAuthorityPolicy.canClaim(
                                owner(), MotorAuthorityPolicy.Owner.GEMINI))) return;
                deadline = SystemClock.elapsedRealtime() + ms;
                if (!command.equals(active) || !config.wltoys) {
                    active = command;
                    // Custom profiles may require repeated pulses; WL profiles use periodic TX.
                    sendInternal(command);
                }
            });
            return "queued " + command + " " + ms + "ms (unacknowledged)";
        } catch (Exception e) {
            return "blocked: " + e.getMessage();
        }
    }

    /**
     * One tool call creates a bounded drive lease, maintained by the existing
     * 10-100ms motor ticker. Foreground, Live, Wi-Fi and human arm are
     * separately checked; a fresh camera image is NOT obstacle clearance.
     * Safety gate rechecked every 100ms and expiry sends neutral.
     */
    public String holdAiAxes(String steering, String throttle, int leaseMs) {
        return holdAiAxes(steering, throttle, leaseMs, false, false);
    }

    private String holdAiAxes(String steering, String throttle, int leaseMs,
                              boolean stopWithSteering, boolean videoGuarded) {
        String violation = CarToolPolicy.validateHold(steering, throttle, leaseMs);
        if (violation != null) return violation;
        if (!armed) return "blocked: control locked";
        if (manual) return "blocked: manual priority";
        if (visionFollowActive) return "blocked: visual follow owns controls";
        if (aiDriveSessionPending || aiDriveSessionActive)
            return "blocked: AI session owns throttle; use adjust_ai_drive";
        if (!MotorAuthorityPolicy.canClaim(owner(), MotorAuthorityPolicy.Owner.GEMINI) &&
                !MotorAuthorityPolicy.canUpdate(owner(), MotorAuthorityPolicy.Owner.GEMINI))
            return "blocked: motor authority unavailable";
        if (!config.wltoys) return "blocked: sustained two-axis control requires WL profile";
        boolean prior = aiHoldActive();
        if (("keep".equals(steering) || "keep".equals(throttle)) && !prior)
            return "blocked: keep requires an active AI drive lease";
        int steer = "keep".equals(steering) ? aiSteer :
                "left".equals(steering) ? -1 : "right".equals(steering) ? 1 : 0;
        int gas = "keep".equals(throttle) ? aiThrottle :
                "forward".equals(throttle) ? 1 : "reverse".equals(throttle) ? -1 : 0;
        if (stopWithSteering && (steer == 0 || gas == 0))
            return "blocked: diagonal stop-with-steering requires both axes";
        if (steer == 0 && gas == 0) {
            emergencyStop();
            return "stopped: both axes neutral";
        }
        boolean safe;
        try {
            safe = aiSafetyGate.getAsBoolean() &&
                    (!videoGuarded || aiVideoGate.getAsBoolean());
        } catch (Exception e) { safe = false; }
        if (!safe) return "blocked: Live/Wi-Fi/foreground or current video safety gate";
        final String cmd = "axes:" + steer + ":" + gas;
        final long epoch = motionEpoch.get();
        final long submittedAt = SystemClock.elapsedRealtime();
        io.execute(() -> {
            // Do not begin an outdated voice command after the serial I/O stalls.
            if (SystemClock.elapsedRealtime() - submittedAt > 500) {
                log.i("AI", "stale queued drive command discarded");
                return;
            }
            if (!armed || manual || visionFollowActive || epoch != motionEpoch.get() ||
                    (!MotorAuthorityPolicy.canClaim(owner(), MotorAuthorityPolicy.Owner.GEMINI) &&
                     !MotorAuthorityPolicy.canUpdate(owner(), MotorAuthorityPolicy.Owner.GEMINI))) return;
            try {
                if (!aiSafetyGate.getAsBoolean() ||
                        (videoGuarded && !aiVideoGate.getAsBoolean())) {
                    armed = false;
                    stopInternal("AI or video gate rejected");
                    return;
                }
            } catch (Exception e) {
                armed = false;
                stopInternal("AI gate failure");
                return;
            }
            aiSteer = steer;
            aiThrottle = gas;
            aiStopWithSteering = stopWithSteering;
            aiVideoGuarded = videoGuarded;
            // Unverified voice diagonals stop BOTH axes before this release.
            // Legacy internal steering-only callers may re-center while holding gas.
            long now = SystemClock.elapsedRealtime();
            aiSteerReleaseAt = steer == 0 ? 0 :
                    now + DriveIntentPolicy.TURN_RECENTER_MS;
            aiHoldActive = true;
            deadline = now + leaseMs;
            lastAiSafetyCheckAt = SystemClock.elapsedRealtime();
            if (!cmd.equals(active)) {
                active = cmd;
                sendInternal(cmd);
            }
            log.i("AI", "drive lease accepted steering=" + steer + " throttle=" + gas +
                    " remainingMs=" + leaseMs + " (no motor ACK)");
        });
        return "queued AI drive lease steering=" + steer + " throttle=" + gas +
                " expires_in_ms=" + leaseMs + " (no hardware ACK)";
    }

    /**
     * Local steering-only correction while the previously authorized AI throttle
     * remains unchanged. This does NOT extend the overall motor lease.
     * When the short correction ends, re-center steering but keep gas until lease expiry.
     */
    public String steerAi(String direction, int holdMs) {
        String violation = CarToolPolicy.validateSteer(direction, holdMs);
        if (violation != null) return violation;
        if (!aiHoldActive() || !armed) return "blocked: no active AI throttle lease";
        if (manual) return "blocked: manual priority";
        if (!MotorAuthorityPolicy.canUpdate(owner(), MotorAuthorityPolicy.Owner.GEMINI))
            return "blocked: motor authority unavailable";
        final int requested = "left".equals(direction) ? -1 : "right".equals(direction) ? 1 : 0;
        final long epoch = motionEpoch.get();
        io.execute(() -> {
            if (!aiHoldActive() || manual || epoch != motionEpoch.get() ||
                    !MotorAuthorityPolicy.canUpdate(owner(), MotorAuthorityPolicy.Owner.GEMINI))
                return;
            boolean safe;
            try { safe = aiSafetyGate.getAsBoolean(); } catch (Exception ex) { safe = false; }
            if (!safe) {
                armed = false;
                stopInternal("AI steering camera/Live gate stale");
                return;
            }
            aiSteer = requested;
            aiSteerReleaseAt = requested == 0 ? 0 : SystemClock.elapsedRealtime() + holdMs;
            String command = "axes:" + requested + ":" + aiThrottle;
            if (!command.equals(active)) {
                active = command;
                sendInternal(command);
            }
            log.i("AI", "steering=" + direction + " holdMs=" + holdMs +
                    " throttle=" + aiThrottle + " unchanged; cruiseDeadlineMs=" +
                    Math.max(0, deadline - SystemClock.elapsedRealtime()));
        });
        return "queued steering=" + direction + " for " + holdMs +
                "ms; throttle remains held, original cruise deadline unchanged";
    }

    /**
     * One natural-language intent equals one bounded local motor lease.
     * A plain forward instruction is a real movement, not a single short tap.
     * No secondary UI authorization or 20-second mode and no self-renewing AI loop.
     */
    public synchronized String driveIntent(String intent) {
        return driveIntentInternal(intent, "forward_continuous".equals(intent));
    }

    /**
     * One explicit user command starts a bounded Android-owned motor session.
     * Gemini may adjust its heading/speed; it cannot extend the absolute deadline.
     * All motor packets still originate on the serial I/O executor.
     */
    public synchronized String startAiDriveSession() {
        if (!armed || manual || visionFollowActive || aiHoldActive ||
                aiDriveSessionPending || aiDriveSessionActive || !"stop".equals(active) ||
                !config.wltoys ||
                !MotorAuthorityPolicy.canClaim(owner(), MotorAuthorityPolicy.Owner.GEMINI))
            return "blocked: stationary WL car, human arm and exclusive motor ownership required";
        boolean safe;
        try { safe = aiSafetyGate.getAsBoolean() && aiVideoGate.getAsBoolean(); }
        catch (Exception ex) { safe = false; }
        if (!safe) return "blocked: foreground Live/Wi-Fi/FPV video not fresh";
        final long epoch = motionEpoch.get();
        final long queuedAt = SystemClock.elapsedRealtime();
        final long sessionId = ++aiDriveSessionSequence;
        aiDriveSessionPending = true;
        aiDriveSessionId = sessionId; // expose the queued id so Gemini can echo it
        io.execute(() -> {
            long now = SystemClock.elapsedRealtime();
            if (epoch != motionEpoch.get() || !aiDriveSessionPending ||
                    !armed || manual || visionFollowActive || aiHoldActive ||
                    !"stop".equals(active) || now - queuedAt > 500) {
                if (epoch == motionEpoch.get()) {
                    aiDriveSessionPending = false;
                    aiDriveSessionId = 0;
                }
                log.i("AI_SESSION", "stale or competing session start discarded");
                return;
            }
            boolean stillSafe;
            try { stillSafe = aiSafetyGate.getAsBoolean() && aiVideoGate.getAsBoolean(); }
            catch (Exception ex) { stillSafe = false; }
            if (!stillSafe) {
                armed = false;
                stopInternal("AI session startup video or Live gate stale");
                return;
            }
            aiDriveSessionPending = false;
            aiDriveSessionActive = true;
            aiDriveSessionId = sessionId;
            aiDriveSessionStartedAt = now;
            aiDriveSessionLastControlAt = now;
            aiDriveSpeedGain = AiDriveSessionPolicy.NORMAL_GAIN;
            aiSteer = 0;
            aiThrottle = 1;
            aiSteerReleaseAt = 0;
            aiStopWithSteering = false;
            aiVideoGuarded = true;
            aiHoldActive = true;
            deadline = now + AiDriveSessionPolicy.MAX_SESSION_MS;
            active = "ai-session:0:1:" + aiDriveSpeedGain;
            sendInternal(active);
            log.i("AI_SESSION", "start sessionId=" + sessionId +
                    " gain=" + aiDriveSpeedGain +
                    " maxMs=" + AiDriveSessionPolicy.MAX_SESSION_MS +
                    " video watchdog on, motor unacknowledged");
        });
        return "queued AI Drive Session id=" + sessionId +
                ", initial normal forward for up to " + AiDriveSessionPolicy.MAX_SESSION_MS +
                "ms; steer/reverse/STOP allowed before expiry; no device ACK";
    }

    /**
     * Steering and throttle gain are INDEPENDENT. Turn correction expires to
     * center without stopping the forward throttle. No session extension.
     */
    public synchronized String adjustAiDrive(long sessionId, String steering, String pace,
                                             String motion) {
        if (!AiDriveSessionPolicy.validMotion(motion))
            return "blocked: motion must be keep/forward/reverse/stop";
        // A STOP request must always work, even if live video is stale.
        if ("stop".equals(motion)) {
            emergencyStop("AI requested stop during drive window");
            return "stopped: both axes neutral requested";
        }
        if (!AiDriveSessionPolicy.validSteering(steering) ||
                !AiDriveSessionPolicy.validSpeed(pace))
            return "blocked: steering left/center/right/keep; speed slow/normal/fast/keep";
        if (!aiDriveSessionPending && !aiDriveSessionActive)
            return "blocked: no active AI Drive Session";
        if (sessionId <= 0 || sessionId != aiDriveSessionId)
            return "blocked: stale or incorrect AI session id";
        if (!armed || manual || visionFollowActive)
            return "blocked: human lock or competing motor owner";
        boolean safe;
        try { safe = aiSafetyGate.getAsBoolean() && aiVideoGate.getAsBoolean(); }
        catch (Exception ex) { safe = false; }
        if (!safe) return "blocked: Live/FPV video stale, cannot adjust";
        final long epoch = motionEpoch.get();
        final long queuedAt = SystemClock.elapsedRealtime();
        io.execute(() -> {
            long now = SystemClock.elapsedRealtime();
            if (epoch != motionEpoch.get() || !aiDriveSessionActive || !armed || manual ||
                    !aiHoldActive || now >= deadline ||
                    now - queuedAt > 500 || sessionId != aiDriveSessionId ||
                    AiDriveSessionPolicy.shouldStop(now, aiDriveSessionStartedAt,
                            aiDriveSessionLastControlAt)) return;
            boolean stillSafe;
            try { stillSafe = aiSafetyGate.getAsBoolean() && aiVideoGate.getAsBoolean(); }
            catch (Exception ex) { stillSafe = false; }
            if (!stillSafe) {
                armed = false;
                stopInternal("AI session update video or Live gate stale");
                return;
            }
            aiSteer = AiDriveSessionPolicy.steering(steering, aiSteer);
            aiThrottle = AiDriveSessionPolicy.throttleFor(motion, aiThrottle);
            aiDriveSpeedGain = AiDriveSessionPolicy.speedGain(pace, aiDriveSpeedGain);
            if (!"keep".equals(steering)) {
                aiSteerReleaseAt = aiSteer == 0 ? 0 :
                        now + AiDriveSessionPolicy.STEERING_HOLD_MS;
            }
            aiDriveSessionLastControlAt = now;
            String command = "ai-session:" + aiSteer + ":" + aiThrottle +
                    ":" + aiDriveSpeedGain;
            if (!command.equals(active)) {
                active = command;
                sendInternal(command);
            }
            log.i("AI_SESSION", "update id=" + sessionId +
                    " steering=" + steering + " motion=" + motion + " speed=" + pace +
                    " gain=" + aiDriveSpeedGain + " leaseRemainingMs=" +
                    Math.max(0, deadline - now) + " no car ACK");
        });
        return "queued steering/throttle/speed update, 30s absolute deadline unchanged; " +
                "watchdog requires fresh model updates; verify motion via FPV (no car ACK)";
    }

    /** One 500ms step from a Gemini-proposed person bearing; no autonomous chaining. */
    public synchronized String guideTowardPerson(String bearing) {
        String intent = PersonBearingPolicy.intentFor(bearing);
        if (intent == null) return "blocked: ambiguous person direction; no movement";
        return driveIntentInternal(intent, true);
    }

    private synchronized String driveIntentInternal(String intent, boolean videoGuarded) {
        DriveIntentPolicy.Plan plan = DriveIntentPolicy.plan(intent);
        if (plan == null) return "blocked: unsupported voice driving intent";
        if (!armed || manual || visionFollowActive || aiHoldActive ||
                !"stop".equals(active) ||
                !MotorAuthorityPolicy.canClaim(owner(), MotorAuthorityPolicy.Owner.GEMINI))
            return "blocked: car must be armed and stationary with no competing motor owner";
        long now = SystemClock.elapsedRealtime();
        if (lastDriveIntentAt > 0 && now - lastDriveIntentAt < 750)
            return "blocked: drive intent cooldown; wait for previous movement";
        String result = holdAiAxes(plan.steering, plan.throttle, plan.leaseMs,
                plan.stopWithSteering, videoGuarded);
        if (result != null && !result.startsWith("blocked")) {
            lastDriveIntentAt = now;
            log.i("DRIVE_INTENT", "intent=" + plan.intent +
                    " durationMs=" + plan.leaseMs +
                    " stopBothAxes=" + plan.stopWithSteering +
                    " videoFreshnessRequired=" + videoGuarded +
                    " (no car acknowledgement)");
        }
        return result;
    }

    /**
     * One explicit human-authorized 8s local visual-follow session.
     * Each fresh inference must renew a 350ms motor lease; no model/cloud commands.
     * The motor output is gain-capped to 50% of OEM deltas, no reverse.
     */
    public synchronized String beginVisionFollow() {
        if (!MotorAuthorityPolicy.canClaim(owner(), MotorAuthorityPolicy.Owner.VISION) ||
                !"stop".equals(active))
            return "blocked: car must be armed and stationary, no manual/AI drive";
        if (!config.wltoys) return "blocked: visual following supports WL only";
        boolean safe;
        try { safe = visionSafetyGate.getAsBoolean(); }
        catch (Exception ignored) { safe = false; }
        if (!safe) return "blocked: vision target/camera/Wi-Fi not ready";
        motionEpoch.incrementAndGet();
        visionFollowActive = true;
        visionFollowLimitAt = SystemClock.elapsedRealtime() + 8000;
        deadline = SystemClock.elapsedRealtime() + 350;
        active = "stop";
        return "visual follow authorized for at most 8 seconds";
    }

    public String driveVisionAxes(int steer, int forward, long observedAt) {
        if (!visionFollowActive() ||
                !MotorAuthorityPolicy.canUpdate(owner(), MotorAuthorityPolicy.Owner.VISION))
            return "blocked: visual follow inactive";
        if (steer < -1 || steer > 1 || forward < 0 || forward > 1)
            return "blocked: vision axes invalid";
        long now = SystemClock.elapsedRealtime();
        if (observedAt <= 0 || observedAt > now || now - observedAt > 250)
            return "blocked: stale vision inference";
        final long epoch = motionEpoch.get();
        io.execute(() -> {
            long current = SystemClock.elapsedRealtime();
            if (!visionFollowActive || !armed || manual || aiHoldActive ||
                    !MotorAuthorityPolicy.canUpdate(owner(), MotorAuthorityPolicy.Owner.VISION) ||
                    epoch != motionEpoch.get() || current - observedAt > 250 ||
                    current >= visionFollowLimitAt) return;
            boolean safe;
            try { safe = visionSafetyGate.getAsBoolean(); }
            catch (Exception ignored) { safe = false; }
            if (!safe) {
                visionFollowActive = false;
                armed = false;
                stopInternal("vision gate stale");
                return;
            }
            deadline = Math.min(current + 350, visionFollowLimitAt);
            String command = forward == 0 ? "stop" : "vision:" + steer + ":" + forward;
            if (!command.equals(active)) {
                active = command;
                sendInternal(command);
            }
        });
        return "queued visual axes, short lease; no vehicle ACK";
    }

    /**
     * One 160ms low-power turn attempt for a manually selected target.
     * A conventional steered RC car needs a little forward throttle to change
     * heading. This is NOT a safe zero-translation turn or obstacle avoidance.
     * The existing VISION motor owner and watchdog enforce an actual neutral STOP.
     */
    public String driveVisionAlignPulse(int steer, long observedAt) {
        if (steer != -1 && steer != 1) return "blocked: alignment needs left/right";
        if (!armed || manual || aiHoldActive || visionFollowActive ||
                !"stop".equals(active) ||
                !MotorAuthorityPolicy.canClaim(owner(), MotorAuthorityPolicy.Owner.VISION))
            return "blocked: car not stationary, armed and idle";
        if (!config.wltoys || config.carProfile != CarProfile.WL_TECH)
            return "blocked: WL Tech only";
        long now = SystemClock.elapsedRealtime();
        if (observedAt <= 0 || observedAt > now || now - observedAt > 250)
            return "blocked: stale target frame";
        boolean safe;
        try { safe = visionSafetyGate.getAsBoolean(); }
        catch (Exception ex) { safe = false; }
        if (!safe) return "blocked: person/camera/Wi-Fi safety gate";
        final long epoch = motionEpoch.get();
        final long submittedAt = now;
        io.execute(() -> {
            long at = SystemClock.elapsedRealtime();
            if (at - submittedAt > 180 || at - observedAt > 250 ||
                    !armed || manual || aiHoldActive || visionFollowActive ||
                    !"stop".equals(active) || motionEpoch.get() != epoch ||
                    !MotorAuthorityPolicy.canClaim(owner(), MotorAuthorityPolicy.Owner.VISION)) {
                log.i("ALIGN", "late or competing alignment pulse discarded");
                return;
            }
            boolean fresh;
            try { fresh = visionSafetyGate.getAsBoolean(); }
            catch (Exception ex) { fresh = false; }
            if (!fresh) {
                log.i("ALIGN", "pulse rejected due to stale target");
                return;
            }
            visionFollowActive = true; // EXCLUSIVE motor authority during this pulse only.
            visionFollowLimitAt = at + VisionAlignPlanner.PULSE_MS + 300;
            deadline = at + VisionAlignPlanner.PULSE_MS;
            visionAlignPulseAcceptedAt = at;
            active = "vision-align:" + steer + ":1";
            sendInternal(active);
            log.i("ALIGN", "short heading nudge accepted steer=" + steer +
                    " ms=" + VisionAlignPlanner.PULSE_MS + " (no car ACK)");
        });
        return "queued one low-power alignment nudge (no car ACK)";
    }

    public void stopVisionFollow() { stopVisionFollow("vision stopped"); }

    public void stopVisionFollow(String reason) {
        if (visionFollowActive) {
            visionFollowActive = false;
            emergencyStop(reason);
        }
    }

    /** Manual two-axis input; the OEM profile can encode throttle and steering together. */
    public String driveManualAxes(int steer, int throttle) {
        if (steer < -1 || steer > 1 || throttle < -1 || throttle > 1)
            return "blocked: axis must be -1, 0, or 1";
        if (!armed) return "blocked: control locked";
        if (steer != 0 || throttle != 0) {
            if (!MotorAuthorityPolicy.canUpdate(owner(), MotorAuthorityPolicy.Owner.MANUAL))
                return "blocked: manual motor authority unavailable";
        }
        if (steer == 0 && throttle == 0) {
            emergencyStop();
            return "stopped";
        }
        if (!config.wltoys) {
            if (steer != 0 && throttle != 0)
                return "blocked: custom profile has no combined-axis payload";
            String direction = steer < 0 ? "left" : steer > 0 ? "right" :
                    throttle > 0 ? "forward" : "backward";
            return drive(direction, 480, false);
        }
        final String command = "axes:" + steer + ":" + throttle;
        final long epoch = motionEpoch.get();
        io.execute(() -> {
            if (!armed || motionEpoch.get() != epoch || !manual ||
                    !MotorAuthorityPolicy.canUpdate(owner(), MotorAuthorityPolicy.Owner.MANUAL))
                return;
            deadline = SystemClock.elapsedRealtime() + 500;
            if (!command.equals(active)) {
                active = command;
                sendInternal(command); // do not duplicate TX on each 180ms UI renewal
            }
        });
        return "queued axes " + steer + "," + throttle + " (unacknowledged)";
    }

    public void emergencyStop() { emergencyStop("E-STOP"); }

    /** Real Gemini WebSocket failure: stop AI without mislabeling car Wi-Fi. */
    public void stopForLiveDisconnect() { emergencyStop("Gemini Live disconnected"); }

    private void emergencyStop(String reason) {
        lastStopReason = reason;
        final long stopRequestedAt = SystemClock.elapsedRealtime();
        lastStopRequestedAt = stopRequestedAt;
        lastStopLocalDelayMs = -1;
        visionFollowActive = false;
        visionFollowLimitAt = 0;
        aiSteerReleaseAt = 0;
        aiStopWithSteering = false;
        aiVideoGuarded = false;
        aiHoldActive = false;
        aiDriveSessionPending = false;
        aiDriveSessionActive = false;
        aiDriveSessionId = 0;
        aiDriveSpeedGain = AiDriveSessionPolicy.NORMAL_GAIN;
        aiSteer = 0;
        aiThrottle = 0;
        motionEpoch.incrementAndGet();
        Config target = config;
        deadline = 0;
        active = "stop";
        io.execute(() -> stopInternal(reason, target, stopRequestedAt));
    }

    private void stopInternal(String reason) {
        stopInternal(reason, config);
    }

    private void stopInternal(String reason, Config target) {
        stopInternal(reason, target, SystemClock.elapsedRealtime());
    }

    private void stopInternal(String reason, Config target, long requestedAt) {
        long now = SystemClock.elapsedRealtime();
        if (active.startsWith("vision-align:"))
            visionAlignPulseStoppedAt = now;
        lastStopRequestedAt = requestedAt;
        lastStopLocalDelayMs = -1;
        lastStopReason = reason;
        long stopEpoch = motionEpoch.incrementAndGet(); // invalidate pending motor commands
        visionFollowActive = false;
        visionFollowLimitAt = 0;
        aiSteerReleaseAt = 0;
        aiStopWithSteering = false;
        aiVideoGuarded = false;
        aiHoldActive = false;
        aiDriveSessionPending = false;
        aiDriveSessionActive = false;
        aiDriveSessionId = 0;
        aiDriveSpeedGain = AiDriveSessionPolicy.NORMAL_GAIN;
        aiSteer = 0;
        aiThrottle = 0;
        active = "stop";
        deadline = 0;
        log.i("SAFETY", "STOP reason=" + reason);
        sendInternal("stop", target);
        // Best-effort neutral redundancy on the SAME persistent WL UDP socket.
        // These cannot preempt an already blocked sender or guarantee car receipt.
        if (target.wltoys && hasStopPayload(target)) {
            final long retryScheduledAt = now;
            for (int delay : STOP_RETRY_DELAYS_MS) {
                io.schedule(() -> {
                    if (motionEpoch.get() == stopEpoch &&
                            "stop".equals(active) &&
                            SystemClock.elapsedRealtime() - retryScheduledAt < 300) {
                        stopBurstAttempts++;
                        sendInternal("stop", target);
                    }
                }, delay, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void sendInternal(String direction) {
        sendInternal(direction, config);
    }

    private void sendInternal(String direction, Config c) {
        if (c.carProfile == CarProfile.KL_FPV) {
            lastTxStatus = "blocked_unverified_kl_fpv";
            return;
        }
        try {
            byte[] bytes;
            if (c.wltoys && (direction.startsWith("vision:") ||
                    direction.startsWith("vision-align:"))) {
                String[] axes = direction.split(":");
                if (axes.length != 3) throw new IllegalArgumentException("bad vision command");
                // Configurable hardware-safe minimum is 50% of OEM gains.
                // Alignment is bounded by a very short local motor lease.
                CarTuning reduced = new CarTuning(c.tuning.intervalMs, 50, 50);
                bytes = WltoysProtocol.control(Integer.parseInt(axes[1]),
                        Integer.parseInt(axes[2]), reduced);
            } else if (c.wltoys && direction.startsWith("ai-session:")) {
                String[] axes = direction.split(":");
                if (axes.length != 4) throw new IllegalArgumentException("bad AI session command");
                int steer = Integer.parseInt(axes[1]);
                int throttle = Integer.parseInt(axes[2]);
                int gain = Integer.parseInt(axes[3]);
                if ((throttle != 1 && throttle != -1) ||
                        (gain != AiDriveSessionPolicy.SLOW_GAIN &&
                         gain != AiDriveSessionPolicy.NORMAL_GAIN &&
                         gain != AiDriveSessionPolicy.FAST_GAIN))
                    throw new IllegalArgumentException("invalid AI drive packet gain/throttle");
                CarTuning limited = new CarTuning(c.tuning.intervalMs,
                        Math.min(c.tuning.steeringPercent, 65),
                        Math.min(c.tuning.throttlePercent, gain));
                bytes = WltoysProtocol.control(steer, throttle, limited);
            } else if (c.wltoys && direction.startsWith("axes:")) {
                String[] axes = direction.split(":");
                if (axes.length != 3) throw new IllegalArgumentException("bad axis command");
                bytes = WltoysProtocol.control(Integer.parseInt(axes[1]), Integer.parseInt(axes[2]), c.tuning);
            } else if (c.wltoys) {
                switch (direction) {
                    case "forward": bytes = WltoysProtocol.control(0, 1, c.tuning); break;
                    case "backward": bytes = WltoysProtocol.control(0, -1, c.tuning); break;
                    case "left": bytes = WltoysProtocol.control(-1, 0, c.tuning); break;
                    case "right": bytes = WltoysProtocol.control(1, 0, c.tuning); break;
                    case "stop": bytes = WltoysProtocol.control(0, 0, c.tuning); break;
                    default: throw new IllegalArgumentException("invalid direction");
                }
            } else {
                bytes = CommandCodec.encode(c.payloads.get(direction), c.hex);
            }
            long started = SystemClock.elapsedRealtime();
            network.sendControl(c.host, c.port, c.tcp, bytes);
            long finished = SystemClock.elapsedRealtime();
            lastSendDurationMs = finished - started;
            if (lastTxAt > 0 && finished >= lastTxAt) {
                long gap = finished - lastTxAt;
                if (gap <= 5000) {
                    lastTxGapMs = gap;
                    maxTxGapMs = Math.max(maxTxGapMs, gap);
                }
            }
            lastTxCommand = direction;
            lastTxStatus = "sent_without_hardware_ack";
            lastTxAt = finished;
            if ("stop".equals(direction)) {
                lastStopSentAt = finished;
                if (lastStopLocalDelayMs < 0 && lastStopRequestedAt > 0)
                    lastStopLocalDelayMs = Math.max(0L, finished - lastStopRequestedAt);
            }
            txPackets++;
            boolean directionChanged = !direction.equals(lastLoggedDirection);
            if (directionChanged || finished - lastDetailedTxLogAt >= 2500) {
                log.i("TX", direction + " bytes=" + bytes.length +
                        " config=" + c.tuning + " hex=" + CommandCodec.hex(bytes) +
                        " sent=" + txPackets + " fails=" + txFailures +
                        " gapMs=" + lastTxGapMs + " localSendMs=" + lastSendDurationMs +
                        " (no hardware ACK)");
                lastDetailedTxLogAt = finished;
                lastLoggedDirection = direction;
            }
        } catch (Exception e) {
            txFailures++;
            lastTxCommand = direction;
            lastTxStatus = "failed_" + e.getClass().getSimpleName();
            lastTxAt = SystemClock.elapsedRealtime();
            log.e("TX", direction + " failed=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (!"stop".equals(direction)) {
                armed = false;
                stopInternal("TX failure");
            }
        }
    }

    public void shutdown() {
        disarm();
        io.execute(() -> {
            if (transmitTicker != null) transmitTicker.cancel(false);
            network.closeControlChannel();
        });
        io.shutdown();
    }
}
