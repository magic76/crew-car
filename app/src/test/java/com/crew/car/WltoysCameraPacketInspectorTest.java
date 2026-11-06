package com.crew.car;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class WltoysCameraPacketInspectorTest {
    @Test public void cameraCommandReplyIsNotVideo() {
        byte[] ack = CommandCodec.encode(WltoysProtocol.CAMERA_START_HEX, true);
        assertTrue(WltoysCameraPacketInspector.likelyCameraCommandReply(
                WltoysProtocol.CAMERA_COMMAND_PORT, ack, ack.length));
        assertFalse(WltoysCameraPacketInspector.looksLikeVideoFragment(ack, ack.length));
    }

    @Test public void actualVideoFragmentIsNotDiscardedEvenIfFromCommandPort() {
        byte[] video = framePacket();
        assertTrue(WltoysCameraPacketInspector.looksLikeVideoFragment(video, video.length));
        assertFalse(WltoysCameraPacketInspector.likelyCameraCommandReply(
                WltoysProtocol.CAMERA_COMMAND_PORT, video, video.length));
    }

    @Test public void invalidVideoHeaderCannotBeCountedAsFrame() {
        byte[] invalid = framePacket();
        ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 1000);
        assertFalse(WltoysCameraPacketInspector.looksLikeVideoFragment(invalid, invalid.length));
    }

    private static byte[] framePacket() {
        ByteBuffer b = ByteBuffer.allocate(35).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x12345678);
        b.putInt(3);        // total frame size
        b.putInt(1);        // frame ID
        b.putShort((short) 0);
        b.putShort((short) 0);
        b.putInt(0);
        b.putShort((short) 1); // total fragments
        b.putShort((short) 0); // index
        b.putInt(0);       // offset
        b.putInt(3);       // payload size
        b.put(new byte[]{1, 2, 3});
        return b.array();
    }
}
