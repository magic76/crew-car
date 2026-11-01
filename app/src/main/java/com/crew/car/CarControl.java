package com.crew.car;

import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Sole serial writer to the car. An explicit STOP packet is sent when movement times out. */
public final class CarControl {
    public static final class Config {
        public String host;
        public int port;
        public boolean tcp;
        public boolean hex;
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

    public CarControl(CarNetwork network, CarLog log) {
        this.network = network;
        this.log = log;
        io.scheduleAtFixedRate(() -> {
            if (!"stop".equals(active) && SystemClock.elapsedRealtime() > deadline) {
                stopInternal("watchdog");
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }
    public boolean armed() { return armed; }
    public boolean manual() { return manual; }
    public void setManual(boolean value) { manual = value; }

    public synchronized void configure(Config next) {
        armed = false;
        manual = false;
        deadline = 0;
        active = "stop";
        Config previous = config;
        config = next;
        io.execute(() -> {
            // Reconfiguration cannot redirect the old hardware STOP to the new IP/port.
            sendInternal("stop", previous);
            log.i("SAFETY", "STOP previous endpoint on reconfigure");
        });
        log.i("CTRL", "config host=" + next.host + ":" + next.port +
                " transport=" + (next.tcp ? "TCP" : "UDP") + " payload=" + (next.hex ? "HEX" : "TEXT"));
    }

    public synchronized String arm() {
        if (network.wifi() == null) return "沒有 Wi-Fi 連線";
        if (config.host == null || config.host.isBlank() || config.port < 1 || config.port > 65535)
            return "請設定控制 IP / Port";
        try {
            for (String d : new String[]{"forward","backward","left","right","stop"})
                CommandCodec.encode(config.payloads.get(d), config.hex);
        } catch (IllegalArgumentException e) { return e.getMessage(); }
        armed = true;
        log.i("SAFETY", "控制已解鎖；封包格式尚需實機驗證");
        return null;
    }

    public void disarm() {
        armed = false;
        manual = false;
        emergencyStop();
        log.i("SAFETY", "控制已上鎖");
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
            io.execute(() -> {
                if (!armed) return;
                active = command;
                deadline = SystemClock.elapsedRealtime() + ms;
                sendInternal(command);
            });
            return "queued " + command + " " + ms + "ms (unacknowledged)";
        } catch (Exception e) {
            return "blocked: " + e.getMessage();
        }
    }

    public void emergencyStop() {
        deadline = 0;
        active = "stop";
        io.execute(() -> stopInternal("E-STOP"));
    }

    private void stopInternal(String reason) {
        active = "stop";
        deadline = 0;
        log.i("SAFETY", "STOP reason=" + reason);
        sendInternal("stop");
    }

    private void sendInternal(String direction) {
        sendInternal(direction, config);
    }

    private void sendInternal(String direction, Config c) {
        try {
            byte[] bytes = CommandCodec.encode(c.payloads.get(direction), c.hex);
            network.send(c.host, c.port, c.tcp, bytes);
            log.i("TX", direction + " bytes=" + bytes.length + " hex=" + CommandCodec.hex(bytes) +
                    " (TX only; no device ACK)");
        } catch (Exception e) {
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
