package com.crew.car;

import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicLong motionEpoch = new AtomicLong();
    private volatile String lastTxCommand = "none";
    private volatile String lastTxStatus = "not_sent";
    private volatile long lastTxAt;
    private volatile long lastStopLogAt;
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
            if (armed && network.wifi() == null) {
                log.e("SAFETY", "Wi-Fi lost; disarming");
                disarm();
                return;
            }
            if (!"stop".equals(active) && SystemClock.elapsedRealtime() > deadline)
                stopInternal("watchdog");
        }, WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
        transmitTicker = scheduleTransmitter(CarTuning.OEM);
    }

    private ScheduledFuture<?> scheduleTransmitter(CarTuning tuning) {
        return io.scheduleAtFixedRate(() -> {
            if (armed && config.wltoys) sendInternal(active);
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
    public boolean armed() { return armed; }
    public boolean manual() { return manual; }
    public void setManual(boolean value) { manual = value; }
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
        return "設定週期 " + config.tuning.intervalMs + "ms | 本機傳送 " + txPackets +
                " | 失敗 " + txFailures + " | 上次間隔 " +
                (lastTxGapMs < 0 ? "—" : lastTxGapMs + "ms") +
                " | 最長間隔 " + maxTxGapMs + "ms | 最後送出耗時 " +
                lastSendDurationMs + "ms（不是車端回應時間）";
    }

    public synchronized void configure(Config next) {
        if (next == null || next.tuning == null)
            throw new IllegalArgumentException("missing control tuning");
        motionEpoch.incrementAndGet();
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
                " wltoysCandidate=" + next.wltoys);
    }

    public synchronized String arm() {
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
        boolean stopCurrent = armed || !"stop".equals(active);
        armed = false;
        manual = false;
        if (stopCurrent && hasStopPayload(config)) emergencyStop();
        log.i("SAFETY", "控制已上鎖");
    }

    private boolean hasStopPayload(Config c) {
        if (c == null || c.host == null || c.host.isBlank() || c.port < 1 || c.port > 65535)
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
            int ms = Math.max(100, Math.min(fromAi ? 650 : 500, requestedMs));
            final String command = direction;
            final long epoch = motionEpoch.get();
            io.execute(() -> {
                if (!armed || motionEpoch.get() != epoch || (fromAi && manual)) return;
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

    /** Manual two-axis input; the OEM profile can encode throttle and steering together. */
    public String driveManualAxes(int steer, int throttle) {
        if (steer < -1 || steer > 1 || throttle < -1 || throttle > 1)
            return "blocked: axis must be -1, 0, or 1";
        if (!armed) return "blocked: control locked";
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
            if (!armed || motionEpoch.get() != epoch || !manual) return;
            deadline = SystemClock.elapsedRealtime() + 500;
            if (!command.equals(active)) {
                active = command;
                sendInternal(command); // do not duplicate TX on each 180ms UI renewal
            }
        });
        return "queued axes " + steer + "," + throttle + " (unacknowledged)";
    }

    public void emergencyStop() {
        motionEpoch.incrementAndGet();
        Config target = config;
        deadline = 0;
        active = "stop";
        io.execute(() -> stopInternal("E-STOP", target));
    }

    private void stopInternal(String reason) {
        stopInternal(reason, config);
    }

    private void stopInternal(String reason, Config target) {
        active = "stop";
        deadline = 0;
        log.i("SAFETY", "STOP reason=" + reason);
        sendInternal("stop", target);
    }

    private void sendInternal(String direction) {
        sendInternal(direction, config);
    }

    private void sendInternal(String direction, Config c) {
        try {
            byte[] bytes;
            if (c.wltoys && direction.startsWith("axes:")) {
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
