package com.crew.car;

/**
 * A small, supervised visual-servo experiment for ONE manually selected person.
 *
 * It never estimates depth or obstacle clearance. A pulse needs forward throttle
 * to change the heading of an ordinary steered RC car. After EVERY pulse we wait
 * for a verified local STOP and a NEW camera frame captured after settling.
 * No target selection, auto-approach, reverse, search turns or infinite renewal.
 */
public final class VisionAlignPlanner {
    public static final int PULSE_MS = 160;
    public static final int MAX_PULSES = 3;
    public static final long SESSION_MS = 6500;
    public static final long SETTLE_MS = 240;
    public static final long STEP_TIMEOUT_MS = 1200;
    public static final long FRAME_MAX_AGE_MS = 300;

    public enum Command { WAIT, LEFT, RIGHT, ALIGNED, CANCEL }

    public static final class Decision {
        public final Command command;
        public final String reason;
        private Decision(Command command, String reason) {
            this.command = command;
            this.reason = reason;
        }
    }

    private boolean active;
    private boolean awaitingPulseStop;
    private long beganAt;
    private long requestedAt;
    private long acceptedBefore;
    private long lastFrameAt;
    private int pulses;
    private float previousError;
    private String lastReason = "未啟動";

    private static Decision decision(Command cmd, String why) {
        return new Decision(cmd, why);
    }

    public synchronized void start(long now, long currentFrameAt) {
        active = true;
        awaitingPulseStop = false;
        beganAt = now;
        requestedAt = 0;
        acceptedBefore = 0;
        lastFrameAt = currentFrameAt;
        pulses = 0;
        previousError = 0;
        lastReason = "等待下一張影像";
    }

    public synchronized boolean active() { return active; }
    public synchronized int pulses() { return pulses; }
    public synchronized String status() { return lastReason; }

    public synchronized void cancel(String why) {
        active = false;
        awaitingPulseStop = false;
        lastReason = why;
    }

    private Decision finish(Command command, String why) {
        cancel(why);
        return decision(command, why);
    }

    /** Called even without camera frames so an image freeze never leaves the session running. */
    public synchronized Decision tick(long now, boolean environmentReady) {
        if (!active) return decision(Command.WAIT, lastReason);
        if (!environmentReady) return finish(Command.CANCEL, "控制權、鏡頭或目標失效");
        if (now - beganAt >= SESSION_MS) return finish(Command.CANCEL, "對準時限已到");
        if (awaitingPulseStop && now - requestedAt >= STEP_TIMEOUT_MS)
            return finish(Command.CANCEL, "馬達停止或新影像等待逾時");
        if (lastFrameAt > 0 && now - lastFrameAt >= STEP_TIMEOUT_MS)
            return finish(Command.CANCEL, "目標影像中斷");
        return decision(Command.WAIT, lastReason);
    }

    public synchronized Decision observe(PersonTracker.Box target, long capturedAt, long now,
                                         boolean motorNeutral, long pulseAcceptedAt,
                                         long pulseStoppedAt, String stopReason) {
        Decision gate = tick(now, true);
        if (gate.command == Command.CANCEL) return gate;
        if (!active) return decision(Command.WAIT, lastReason);
        if (capturedAt <= lastFrameAt) return decision(Command.WAIT, "等待新畫面");
        if (capturedAt <= 0 || capturedAt > now || now - capturedAt > FRAME_MAX_AGE_MS)
            return finish(Command.CANCEL, "影格過期");
        // Update freshness even while waiting on motor STOP, for watchdog purposes.
        lastFrameAt = capturedAt;
        if (awaitingPulseStop) {
            if (pulseAcceptedAt <= acceptedBefore || pulseAcceptedAt < requestedAt)
                return decision(Command.WAIT, "等待馬達接受短動作");
            if (!motorNeutral || pulseStoppedAt < pulseAcceptedAt)
                return decision(Command.WAIT, "等待雙軸停止");
            if (!"vision align pulse lease expired".equals(stopReason))
                return finish(Command.CANCEL, "短動作未正常結束");
            if (now - pulseStoppedAt < SETTLE_MS ||
                    capturedAt < pulseStoppedAt + SETTLE_MS)
                return decision(Command.WAIT, "停車後等待新影格");
            awaitingPulseStop = false;
        } else if (!motorNeutral) {
            return finish(Command.CANCEL, "其他馬達控制權正在使用");
        }

        if (target == null || target.score < .67f)
            return finish(Command.CANCEL, "人物遺失或信心不足");
        float height = target.height();
        float error = target.centerX() - .5f;
        if (!Float.isFinite(height) || !Float.isFinite(error) ||
                height < .085f || height >= .23f || Math.abs(error) > .22f)
            return finish(Command.CANCEL, "目標太近、太小或偏離安全追蹤區");
        if (pulses > 0) {
            if (Math.abs(error) > Math.abs(previousError) + .02f)
                return finish(Command.CANCEL, "校正後偏差增加，停止並請人工確認");
            if (Math.signum(error) != Math.signum(previousError) &&
                    Math.abs(error) > .065f)
                return finish(Command.CANCEL, "方向跨越中心，避免反覆繞圈");
        }
        if (Math.abs(error) <= .065f)
            return finish(Command.ALIGNED, "人物已在畫面中央（僅視覺對準，非實車定位）");
        if (pulses >= MAX_PULSES)
            return finish(Command.CANCEL, "已達三次短動作上限，請人工檢查");

        pulses++;
        previousError = error;
        requestedAt = now;
        acceptedBefore = pulseAcceptedAt;
        awaitingPulseStop = true;
        lastReason = "第 " + pulses + " 次方向微調，等待停車後新影格";
        return decision(error < 0 ? Command.LEFT : Command.RIGHT, lastReason);
    }
}
