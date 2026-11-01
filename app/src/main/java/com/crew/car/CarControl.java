package com.crew.car;

import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Sole serial writer to the car. An explicit STOP packet is sent when movement times out. */
public final class CarControl {
    /** OEM app log showed 16-byte control sends at roughly 50 ms intervals. */
    private static final long CONTROL_PERIOD_MS = 50;

    public static final class Config {
        public String host;
        public int port;
        public boolean tcp;
        public boolean hex;
        /** Open-source candidate firmware profile; user must still explicitly arm. */
        public boolean wltoys;
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

    public CarControl(CarNetwork network, CarLog log) {
        this.network = network;
        this.log = log;
        io.scheduleAtFixedRate(() -> {
            if (armed && network.wifi() == null) {
                log.e("SAFETY", "Wi-Fi lost; disarming");
                disarm();
                return;
            }
            if (!"stop".equals(active) && SystemClock.elapsedRealtime() > deadline) {
                stopInternal("watchdog");
            } else if (armed && config.wltoys) {
                // Match the ~50 ms send cadence observed in the owner's OEM app log.
                sendInternal(active);
            }
        }, CONTROL_PERIOD_MS, CONTROL_PERIOD_MS, TimeUnit.MILLISECONDS);
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

    public synchronized void configure(Config next) {
        motionEpoch.incrementAndGet();
        boolean stopPrevious = armed || !"stop".equals(active);
        armed = false;
        manual = false;
        deadline = 0;
        active = "stop";
        Config previous = config;
        config = next;
        if (stopPrevious && hasStopPayload(previous)) {
            io.execute(() -> {
                // Reconfiguration cannot redirect the old hardware STOP to the new IP/port.
                sendInternal("stop", previous);
                log.i("SAFETY", "STOP previous endpoint on reconfigure");
            });
        }
        log.i("CTRL", "config host=" + next.host + ":" + next.port +
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
        log.i("SAFETY", "控制已解鎖；封包格式尚需實機驗證");
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
                active = command;
                deadline = SystemClock.elapsedRealtime() + ms;
                sendInternal(command);
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
            active = command;
            deadline = SystemClock.elapsedRealtime() + 500;
            sendInternal(command);
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
                bytes = WltoysProtocol.control(Integer.parseInt(axes[1]), Integer.parseInt(axes[2]));
            } else if (c.wltoys) {
                bytes = WltoysProtocol.control(direction);
            } else {
                bytes = CommandCodec.encode(c.payloads.get(direction), c.hex);
            }
            network.send(c.host, c.port, c.tcp, bytes);
            lastTxCommand = direction;
            lastTxStatus = "sent_without_hardware_ack";
            lastTxAt = SystemClock.elapsedRealtime();
            // Avoid flooding on-screen logs with 10 neutral heartbeats/sec.
            if (!"stop".equals(direction) || lastTxAt - lastStopLogAt >= 2000) {
                log.i("TX", direction + " target=" + c.host + ":" + c.port +
                        " transport=" + (c.tcp ? "TCP" : "UDP") +
                        " bytes=" + bytes.length + " hex=" + CommandCodec.hex(bytes) +
                        " (TX only; no device ACK)");
                if ("stop".equals(direction)) lastStopLogAt = lastTxAt;
            }
        } catch (Exception e) {
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
        io.shutdown();
    }
}
