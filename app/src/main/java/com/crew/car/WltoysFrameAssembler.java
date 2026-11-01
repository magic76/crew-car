package com.crew.car;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Streaming frame reassembly for the observed WLtoys 6405 camera UDP format.
 * Pure Java and independently unit-testable; no Android or real car required.
 */
public final class WltoysFrameAssembler {
    private static final int MAX_FRAME = 1024 * 1024;
    private static final int HEADER_BYTES = 32;
    private static final int MAX_FRAGMENTS = 1024;
    private long currentId = -1;
    private int frameSize;
    private byte[] contents;
    private boolean[] received;
    private int receivedBytes;
    private int receivedCount;

    public byte[] accept(byte[] packet, int length) {
        if (packet == null || length < HEADER_BYTES || length > packet.length) return null;
        ByteBuffer header = ByteBuffer.wrap(packet, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        header.getInt(); // magic
        int size = header.getInt();
        long id = header.getInt() & 0xffffffffL;
        header.getShort(); // checksum (meaning not yet validated)
        header.getShort(); // index
        header.getInt(); // packet type
        int fragments = header.getShort() & 0xffff;
        int fragmentIndex = header.getShort() & 0xffff;
        int offset = header.getInt();
        int payload = header.getInt();

        if (size <= 0 || size > MAX_FRAME ||
                fragments <= 0 || fragments > MAX_FRAGMENTS ||
                fragmentIndex >= fragments || payload <= 0 ||
                payload > length - HEADER_BYTES || offset < 0 ||
                (long) offset + payload > size) return null;

        if (currentId != id) {
            reset();
            currentId = id;
            frameSize = size;
            contents = new byte[size];
            received = new boolean[fragments];
        }
        if (frameSize != size || received.length != fragments ||
                received[fragmentIndex]) return null;

        received[fragmentIndex] = true;
        receivedCount++;
        System.arraycopy(packet, HEADER_BYTES, contents, offset, payload);
        receivedBytes += payload;

        if (receivedCount == fragments && receivedBytes == frameSize) {
            byte[] completed = contents;
            reset();
            return completed;
        }
        return null;
    }

    public void reset() {
        currentId = -1;
        frameSize = 0;
        contents = null;
        received = null;
        receivedBytes = 0;
        receivedCount = 0;
    }
}
