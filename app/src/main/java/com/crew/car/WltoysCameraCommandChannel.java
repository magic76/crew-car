package com.crew.car;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Send camera START/STOP from the already bound video receive socket.
 *
 * Some Android hotspot NAT implementations may only return traffic to a
 * known outbound UDP source port. Keeping camera commands on UDP 1234 gives
 * the NAT a chance to map that port; it cannot guarantee compatibility with
 * endpoint-dependent filtering or hard-coded video destinations.
 */
final class WltoysCameraCommandChannel {
    private final DatagramSocket socket;
    private final InetAddress destination;
    private final int commandPort;
    private boolean started;

    WltoysCameraCommandChannel(DatagramSocket socket, InetAddress destination, int commandPort) {
        if (socket == null || destination == null || commandPort <= 0 || commandPort > 65535)
            throw new IllegalArgumentException("Invalid camera command endpoint");
        if (!socket.isBound()) throw new IllegalArgumentException("Camera socket must already be bound");
        this.socket = socket;
        this.destination = destination;
        this.commandPort = commandPort;
    }

    synchronized void start() throws IOException {
        send(CommandCodec.encode(WltoysProtocol.CAMERA_START_HEX, true));
        started = true;
    }

    /** Safe to call from the worker finally block or an explicit UI stop. */
    synchronized boolean stop() throws IOException {
        if (!started || socket.isClosed()) return false;
        send(CommandCodec.encode(WltoysProtocol.CAMERA_STOP_HEX, true));
        started = false;
        return true;
    }

    private void send(byte[] payload) throws IOException {
        socket.send(new DatagramPacket(payload, payload.length, destination, commandPort));
    }

    int localPort() { return socket.getLocalPort(); }
}
