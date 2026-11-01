package com.crew.car;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class CommandCodec {
    private CommandCodec() {}

    /** Encodes a user-supplied, device-verified payload. Never assumes a vendor protocol. */
    public static byte[] encode(String value, boolean hex) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("未設定指令封包");
        }
        byte[] data;
        if (hex) {
            String clean = value.replace("0x", "").replace("0X", "")
                    .replaceAll("[\\s,:-]", "");
            if (clean.isEmpty() || (clean.length() & 1) != 0 || !clean.matches("[0-9a-fA-F]+")) {
                throw new IllegalArgumentException("HEX 格式錯誤：須為偶數個十六進位字元");
            }
            data = new byte[clean.length() / 2];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
            }
        } else {
            data = value.getBytes(StandardCharsets.UTF_8);
        }
        if (data.length > 256) {
            throw new IllegalArgumentException("單筆封包不得超過 256 bytes");
        }
        return data;
    }

    public static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte b : data) out.append(String.format(Locale.US, "%02X", b & 0xff));
        return out.toString();
    }

    public static String direction(String value) {
        String s = value == null ? "" : value.trim().toLowerCase(Locale.US);
        switch (s) {
            case "forward": case "backward": case "left": case "right": case "stop": return s;
            default: throw new IllegalArgumentException("未知方向: " + s);
        }
    }
}
