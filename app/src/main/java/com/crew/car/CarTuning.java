package com.crew.car;

/** Immutable, bounded on-device remote-control tuning. Percentages never exceed OEM deltas. */
public final class CarTuning {
    public static final int MIN_INTERVAL_MS = 10;
    public static final int MAX_INTERVAL_MS = 100;
    public static final int INTERVAL_STEP_MS = 10;
    public static final int MIN_GAIN_PERCENT = 50;
    public static final int MAX_GAIN_PERCENT = 100;
    public static final CarTuning OEM = new CarTuning(50, 100, 100);

    public final int intervalMs;
    public final int steeringPercent;
    public final int throttlePercent;

    public CarTuning(int intervalMs, int steeringPercent, int throttlePercent) {
        if (intervalMs < MIN_INTERVAL_MS || intervalMs > MAX_INTERVAL_MS ||
                intervalMs % INTERVAL_STEP_MS != 0)
            throw new IllegalArgumentException("interval must be 10–100ms in 10ms steps");
        if (steeringPercent < MIN_GAIN_PERCENT || steeringPercent > MAX_GAIN_PERCENT ||
                throttlePercent < MIN_GAIN_PERCENT || throttlePercent > MAX_GAIN_PERCENT ||
                steeringPercent % 5 != 0 || throttlePercent % 5 != 0)
            throw new IllegalArgumentException("steering/throttle must be 50–100% in 5% steps");
        this.intervalMs = intervalMs;
        this.steeringPercent = steeringPercent;
        this.throttlePercent = throttlePercent;
    }

    /** These are experiments, not physically calibrated speed measurements. */
    public static CarTuning preset(int choice) {
        switch (choice) {
            case 0: return new CarTuning(50, 65, 60); // gentle / small-space
            case 1: return OEM;                        // measured OEM send cadence
            case 2: return new CarTuning(70, 75, 75); // less traffic
            case 3: return new CarTuning(40, 100, 100); // experimental lower interval
            case 4: return new CarTuning(30, 100, 100); // 33 Hz experiment
            case 5: return new CarTuning(20, 100, 100); // 50 Hz experiment
            case 6: return new CarTuning(10, 100, 100); // 100 Hz experiment
            default: throw new IllegalArgumentException("unknown preset");
        }
    }

    @Override public String toString() {
        return intervalMs + "ms steer=" + steeringPercent + "% throttle=" + throttlePercent + "%";
    }
}
