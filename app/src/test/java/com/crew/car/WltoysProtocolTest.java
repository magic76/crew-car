package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WltoysProtocolTest {
    @Test public void neutralMatchesDiscoveredProtocol() {
        assertEquals("CA47D500000000006680808000008099",
                CommandCodec.hex(WltoysProtocol.control("stop")));
    }

    @Test public void directionsSetExpectedBytesAndXor() {
        byte[] forward = WltoysProtocol.control("forward");
        assertEquals(0x80, forward[9] & 0xff);
        assertEquals(0x9e, forward[10] & 0xff);
        assertEquals((forward[9] ^ forward[10] ^ forward[11]) & 0xff, forward[14] & 0xff);
        byte[] left = WltoysProtocol.control("left");
        assertEquals(0x80 - 45, left[9] & 0xff);
        assertEquals(0x80, left[10] & 0xff);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownDirection() { WltoysProtocol.control("takeoff"); }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOutOfBoundsByte() { WltoysProtocol.packet(256, 128, 128); }

    @Test public void goalDirectionNudgesUseCombinedForwardAndSteeringPayload() {
        CarTuning gentle = new CarTuning(50, 50, 50);
        byte[] leftForward = WltoysProtocol.control(-1, 1, gentle);
        byte[] straight = WltoysProtocol.control(0, 1, gentle);
        byte[] rightForward = WltoysProtocol.control(1, 1, gentle);
        assertEquals(128 - Math.round(45f * .5f), leftForward[9] & 255);
        assertEquals(128, straight[9] & 255);
        assertEquals(128 + Math.round(45f * .5f), rightForward[9] & 255);
        for (byte[] packet : new byte[][]{leftForward,straight,rightForward}) {
            assertEquals(128 + Math.round(30f * .5f), packet[10] & 255);
            assertEquals(16, packet.length);
            assertEquals((packet[9] ^ packet[10] ^ packet[11]) & 255,
                    packet[14] & 255);
        }
    }

    @Test public void aiDrivingGainLevelsIncreaseWlThrottleBytes() {
        int[] gains = {AiDriveSessionPolicy.SLOW_GAIN,
                AiDriveSessionPolicy.NORMAL_GAIN,
                AiDriveSessionPolicy.FAST_GAIN};
        int[] expectedForwardBytes = {149, 154, 158};
        for (int i = 0; i < gains.length; i++) {
            CarTuning tuning = new CarTuning(50, 65, gains[i]);
            byte[] bytes = WltoysProtocol.control(0, 1, tuning);
            assertEquals(expectedForwardBytes[i], bytes[10] & 0xff);
            assertEquals((bytes[9] ^ bytes[10] ^ bytes[11]) & 255, bytes[14] & 255);
        }
    }

    @Test public void aiGainDoesNotOverrideUserThrottleCap() {
        CarTuning limited = new CarTuning(50, 65, Math.min(60, AiDriveSessionPolicy.FAST_GAIN));
        assertEquals(146, WltoysProtocol.control(0, 1, limited)[10] & 0xff);
    }

    @Test public void videoHandshakeSequences() {
        assertEquals(16, CommandCodec.encode(WltoysProtocol.CAMERA_START_HEX, true).length);
        assertEquals(14, CommandCodec.encode(WltoysProtocol.CAMERA_STOP_HEX, true).length);
    }
}
