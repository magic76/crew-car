package com.crew.car;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * One multi-touch dispatcher, two independent thumb zones; central video stays visible.
 * No automated motion, no speed telemetry; only -1/0/+1 axes through the existing
 * CarControl safety lock, motor watchdog and explicit STOP.
 */
public final class DualStickDriveView extends View {
    public interface Listener { void onAxes(int steering, int throttle); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DualStickInput input = new DualStickInput();
    private final Listener listener;
    private final int colorPanel = Color.rgb(17, 26, 39);
    private final int colorAccent = Color.rgb(33, 211, 216);

    public DualStickDriveView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setContentDescription("左側左右轉向，右側上推前進下拉倒車，可雙指同時操作，鬆手停止");
        setFocusable(true);
        setClickable(true);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float width = getWidth(), height = getHeight();
        float side = width * DualStickInput.SIDE_FRACTION;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colorPanel);
        c.drawRoundRect(0, 0, side - dp(2), height, dp(16), dp(16), paint);
        c.drawRoundRect(width - side + dp(2), 0, width, height, dp(16), dp(16), paint);

        drawPanel(c, side * .5f, height * .53f, side, height,
                input.steering(), true);
        drawPanel(c, width - side * .5f, height * .53f, side, height,
                input.throttle(), false);
    }

    private void drawPanel(Canvas c, float cx, float cy, float side, float height,
                           int value, boolean steer) {
        float radius = Math.min(side * .30f, height * .24f);
        float offset = Math.min(side * .25f, height * .23f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);
        paint.setTextSize(dp(14));
        paint.setFakeBoldText(true);
        c.drawText(steer ? "轉向" : "油門／倒車", cx, dp(28), paint);
        paint.setFakeBoldText(false);
        paint.setColor(Color.rgb(107, 136, 157));
        paint.setTextSize(dp(20));
        if (steer) {
            c.drawText("◀", cx - offset * 1.55f, cy + dp(6), paint);
            c.drawText("▶", cx + offset * 1.55f, cy + dp(6), paint);
        } else {
            c.drawText("▲", cx, cy - offset * 1.65f, paint);
            c.drawText("▼", cx, cy + offset * 1.85f, paint);
        }
        paint.setColor(Color.rgb(42, 62, 82));
        c.drawCircle(cx, cy, radius, paint);
        paint.setColor(colorAccent);
        float knobX = steer ? cx + value * offset : cx;
        float knobY = steer ? cy : cy - value * offset;
        c.drawCircle(knobX, knobY, Math.max(dp(17), radius * .52f), paint);
        paint.setColor(Color.rgb(220, 235, 244));
        paint.setTextSize(dp(11));
        c.drawText(steer ? "放開回正" : "放開歸零", cx, height - dp(14), paint);
    }

    private void notifyChanged(int oldSteer, int oldThrottle) {
        if (oldSteer != input.steering() || oldThrottle != input.throttle()) {
            invalidate();
            listener.onAxes(input.steering(), input.throttle());
        }
    }

    public void cancelInput() {
        int oldSteer = input.steering(), oldThrottle = input.throttle();
        input.cancel();
        notifyChanged(oldSteer, oldThrottle);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int beforeSteer = input.steering(), beforeThrottle = input.throttle();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            input.down(event.getPointerId(index), event.getX(index), event.getY(index),
                    getWidth(), getHeight());
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int index = 0; index < event.getPointerCount(); index++) {
                input.move(event.getPointerId(index), event.getX(index), event.getY(index),
                        getWidth(), getHeight());
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            input.up(event.getPointerId(event.getActionIndex()));
            if (action == MotionEvent.ACTION_UP) performClick();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            input.cancel();
        }
        notifyChanged(beforeSteer, beforeThrottle);
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        cancelInput();
        super.onDetachedFromWindow();
    }
}
