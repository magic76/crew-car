package com.crew.car;

import org.junit.Test;
import static org.junit.Assert.*;

public class CommandCodecTest {
    @Test public void hexBytes() {
        assertArrayEquals(new byte[]{(byte) 0xAA, 0x05, 0x00},
                CommandCodec.encode("AA 05 00", true));
        assertArrayEquals(new byte[]{(byte) 0xAA, 0x05},
                CommandCodec.encode("0xAA,0x05", true));
    }
    @Test(expected = IllegalArgumentException.class)
    public void rejectOddHex() { CommandCodec.encode("ABC", true); }
    @Test(expected = IllegalArgumentException.class)
    public void rejectNonHex() { CommandCodec.encode("GG", true); }
    @Test(expected = IllegalArgumentException.class)
    public void rejectEmpty() { CommandCodec.encode("", false); }
    @Test(expected = IllegalArgumentException.class)
    public void rejectUnexpectedMotion() { CommandCodec.direction("circle"); }
    @Test public void ascii() {
        assertArrayEquals("F".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                CommandCodec.encode("F", false));
    }
}
