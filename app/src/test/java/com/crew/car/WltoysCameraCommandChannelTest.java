package com.crew.car;

import org.junit.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

import static org.junit.Assert.*;

public class WltoysCameraCommandChannelTest {
    @Test public void cameraStartAndStopUseSameSourcePortAsVideoReceiver() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket carCommands = new DatagramSocket(0, loopback);
             DatagramSocket videoReceiver = new DatagramSocket(0, loopback)) {
            carCommands.setSoTimeout(1000);
            WltoysCameraCommandChannel channel = new WltoysCameraCommandChannel(
                    videoReceiver, loopback, carCommands.getLocalPort());

            channel.start();
            DatagramPacket start = receive(carCommands);
            assertEquals(videoReceiver.getLocalPort(), start.getPort());
            assertArrayEquals(CommandCodec.encode(WltoysProtocol.CAMERA_START_HEX, true),
                    payload(start));
            assertEquals(videoReceiver.getLocalPort(), channel.localPort());

            assertTrue(channel.stop());
            DatagramPacket stop = receive(carCommands);
            assertEquals(videoReceiver.getLocalPort(), stop.getPort());
            assertArrayEquals(CommandCodec.encode(WltoysProtocol.CAMERA_STOP_HEX, true),
                    payload(stop));
            assertFalse("Stop must be idempotent", channel.stop());
            try {
                receive(carCommands);
                fail("duplicate STOP packet");
            } catch (SocketTimeoutException expected) {
                // No duplicate packet on repeated stop.
            }
        }
    }

    @Test public void stopBeforeStartDoesNotEmitCameraCommand() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (DatagramSocket carCommands = new DatagramSocket(0, loopback);
             DatagramSocket videoReceiver = new DatagramSocket(0, loopback)) {
            carCommands.setSoTimeout(150);
            WltoysCameraCommandChannel channel = new WltoysCameraCommandChannel(
                    videoReceiver, loopback, carCommands.getLocalPort());
            assertFalse(channel.stop());
            try {
                receive(carCommands);
                fail("unexpected STOP before START");
            } catch (SocketTimeoutException expected) {
                // Correct: no packet sent.
            }
        }
    }

    private static DatagramPacket receive(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[128], 128);
        socket.receive(packet);
        return packet;
    }

    private static byte[] payload(DatagramPacket packet) {
        return Arrays.copyOfRange(packet.getData(), packet.getOffset(),
                packet.getOffset() + packet.getLength());
    }
}
