package com.crew.car;

/**
 * Experimental WLtoys 6405/6401 remote control format based on:
 * https://github.com/lcgamboa/WL-Tech-FPV-CAR-6405
 *
 * NOT proven on the owner's vehicle. Never enable without explicit user arming.
 * 16-byte little-endian packet sent over UDP port 23458 to 172.16.11.1.
 */
public final class WltoysProtocol {
    public static final String DEFAULT_IP = "172.16.11.1";
    public static final int CONTROL_PORT = 23458;
    public static final int CAMERA_COMMAND_PORT = 23459;
    public static final int CAMERA_DATA_PORT = 1234;
    public static final String CAMERA_START_HEX = "A88A200008000000010002000000D204";
    public static final String CAMERA_STOP_HEX = "A88A210006000000010000000000";

    private static final int CENTER = 0x80;
    private static final int THROTTLE_DELTA = 30;
    private static final int STEERING_DELTA = 45;

    private WltoysProtocol() { }

    public static byte[] control(String direction) {
        switch (CommandCodec.direction(direction)) {
            case "forward": return control(0, 1);
            case "backward": return control(0, -1);
            case "left": return control(-1, 0);
            case "right": return control(1, 0);
            case "stop": return control(0, 0);
            default: throw new IllegalArgumentException("bad direction");
        }
    }

    /**
     * Encodes simultaneous steering and throttle axes. Each axis is -1, 0, or 1;
     * this supports diagonal movement using the same frame layout as the OEM app.
     */
    public static byte[] control(int steerDirection, int throttleDirection) {
        if (steerDirection < -1 || steerDirection > 1 ||
                throttleDirection < -1 || throttleDirection > 1)
            throw new IllegalArgumentException("axis must be -1, 0, or 1");
        return control(steerDirection, throttleDirection, CarTuning.OEM);
    }

    /** Apply bounded gains without modifying neutral or the OEM packet/checksum layout. */
    public static byte[] control(int steerDirection, int throttleDirection, CarTuning tuning) {
        if (tuning == null) throw new IllegalArgumentException("tuning missing");
        if (steerDirection < -1 || steerDirection > 1 ||
                throttleDirection < -1 || throttleDirection > 1)
            throw new IllegalArgumentException("axis must be -1, 0, or 1");
        int scaledSteer = Math.round(STEERING_DELTA * tuning.steeringPercent / 100f);
        int scaledThrottle = Math.round(THROTTLE_DELTA * tuning.throttlePercent / 100f);
        return packet(CENTER + steerDirection * scaledSteer,
                CENTER + throttleDirection * scaledThrottle, CENTER);
    }

    public static byte[] packet(int steer, int throttle, int trim) {
        for (int value : new int[]{steer, throttle, trim}) {
            if (value < 0 || value > 255) throw new IllegalArgumentException("control byte must be 0..255");
        }
        int checksum = steer ^ throttle ^ trim;
        return new byte[]{
                (byte) 0xCA, 0x47, (byte) 0xD5, 0x00, // little endian 0x00D547CA
                0, 0, 0, 0,
                0x66, (byte) steer, (byte) throttle, (byte) trim,
                0, 0, (byte) checksum, (byte) 0x99
        };
    }
}
