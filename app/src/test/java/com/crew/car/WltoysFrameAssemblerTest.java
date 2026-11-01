package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class WltoysFrameAssemblerTest {
    private byte[] packet(int frameId, int totalSize, int count, int fragment, int offset, byte[] bytes) {
        ByteBuffer b = ByteBuffer.allocate(32 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x12345678); // Magic is not yet confirmed; not relied upon.
        b.putInt(totalSize);
        b.putInt(frameId);
        b.putShort((short) 0);
        b.putShort((short) 0);
        b.putInt(0);
        b.putShort((short) count);
        b.putShort((short) fragment);
        b.putInt(offset);
        b.putInt(bytes.length);
        b.put(bytes);
        return b.array();
    }
    @Test public void assemblesOutOfOrderFragments() {
        WltoysFrameAssembler a = new WltoysFrameAssembler();
        byte[] first = packet(1, 5, 2, 0, 0, new byte[]{1,2,3});
        byte[] second = packet(1, 5, 2, 1, 3, new byte[]{4,5});
        assertNull(a.accept(second, second.length));
        assertArrayEquals(new byte[]{1,2,3,4,5}, a.accept(first, first.length));
    }
    @Test public void ignoresDuplicateFragments() {
        WltoysFrameAssembler a = new WltoysFrameAssembler();
        byte[] one = packet(11, 5, 2, 0, 0, new byte[]{1,2,3});
        byte[] two = packet(11, 5, 2, 1, 3, new byte[]{4,5});
        assertNull(a.accept(one, one.length));
        assertNull(a.accept(one, one.length));
        assertArrayEquals(new byte[]{1,2,3,4,5}, a.accept(two, two.length));
    }
    @Test public void dropsIncompleteOldFrameWhenNewFrameArrives() {
        WltoysFrameAssembler a = new WltoysFrameAssembler();
        byte[] old = packet(2, 10, 2, 0, 0, new byte[]{1,2});
        byte[] fresh = packet(3, 3, 1, 0, 0, new byte[]{3,4,5});
        assertNull(a.accept(old, old.length));
        assertArrayEquals(new byte[]{3,4,5}, a.accept(fresh, fresh.length));
    }
    @Test public void rejectsOffsetOverflowWithoutAllocating() {
        WltoysFrameAssembler a = new WltoysFrameAssembler();
        byte[] bad = packet(1, 2, 1, 0, 1, new byte[]{1,2});
        assertNull(a.accept(bad, bad.length));
    }
    @Test public void rejectsExcessiveSize() {
        WltoysFrameAssembler a = new WltoysFrameAssembler();
        byte[] bad = packet(1, 2_000_000, 1, 0, 0, new byte[]{1});
        assertNull(a.accept(bad, bad.length));
    }
}
