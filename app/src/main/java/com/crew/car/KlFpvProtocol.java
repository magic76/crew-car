package com.crew.car;

/** Fail-closed KL FPV placeholder. Never send guessed WL or generic FPV packets. */
public final class KlFpvProtocol {
    private KlFpvProtocol() {}
    public static final String BLOCKED_REASON = "KL FPV 協定尚未驗證，禁止猜測控制封包";
    public static boolean isVerified() { return false; }
    public static byte[] encodeAxes(int steer, int throttle) {
        throw new UnsupportedOperationException(BLOCKED_REASON);
    }
}
