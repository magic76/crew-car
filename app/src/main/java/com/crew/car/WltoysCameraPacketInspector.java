package com.crew.car;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Inspect WL 6405 datagrams before treating a camera command reply as video.
 * Camera commands go to UDP 23459, whereas valid H.265 video fragments carry
 * a 32-byte WL-specific header. This class does not decode video.
 */
final class WltoysCameraPacketInspector {
    private static final int HEADER_BYTES = 32;
    private static final int MAX_FRAME = 1024 * 1024;
    private static final int MAX_FRAGMENTS = 1024;

    private WltoysCameraPacketInspector() {}

    static boolean looksLikeVideoFragment(byte[] data, int length) {
        if (data == null || length < HEADER_BYTES || length > data.length) return false;
        ByteBuffer h = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        int total = h.getInt(4);
        int fragments = h.getShort(20) & 0xffff;
        int index = h.getShort(22) & 0xffff;
        int offset = h.getInt(24);
        int payload = h.getInt(28);
        return total > 0 && total <= MAX_FRAME
                && fragments > 0 && fragments <= MAX_FRAGMENTS
                && index < fragments && offset >= 0
                && payload > 0 && payload <= length - HEADER_BYTES
                && (long) offset + payload <= total;
    }

    static boolean likelyCameraCommandReply(int sourcePort, byte[] data, int length) {
        return sourcePort == WltoysProtocol.CAMERA_COMMAND_PORT
                && !looksLikeVideoFragment(data, length);
    }
}
