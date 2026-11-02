package com.crew.car;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * One multi-touch dispatcher, a left steering pad and two right-side hold buttons;
 * central video stays visible.
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
    private int throttlePointer = -1;
    private int buttonThrottle;

    public DualStickDriveView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setContentDescription("左側左右轉向；右側上方按住前進、下方按住倒車，可同時轉向，放開停止");
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

        drawSteeringPanel(c, side * .5f, height * .53f, side, height, input.steering());
        drawThrottleButtons(c, width - side * .5f, side, height);
    }

    private void drawSteeringPanel(Canvas c, float cx, float cy, float side, float height,
                                   int value) {
        float radius = Math.min(side * .30f, height * .24f);
        float offset = Math.min(side * .25f, height * .23f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);
        paint.setTextSize(dp(14));
        paint.setFakeBoldText(true);
        c.drawText("轉向", cx, dp(28), paint);
        paint.setFakeBoldText(false);
        paint.setColor(Color.rgb(107, 136, 157));
        paint.setTextSize(dp(20));
        c.drawText("◀", cx - offset * 1.55f, cy + dp(6), paint);
        c.drawText("▶", cx + offset * 1.55f, cy + dp(6), paint);
        paint.setColor(Color.rgb(42, 62, 82));
        c.drawCircle(cx, cy, radius, paint);
        paint.setColor(colorAccent);
        c.drawCircle(cx + value * offset, cy, Math.max(dp(17), radius * .52f), paint);
        paint.setColor(Color.rgb(220, 235, 244));
        paint.setTextSize(dp(11));
        c.drawText("放開回正", cx, height - dp(14), paint);
    }

    private void drawThrottleButtons(Canvas c, float cx, float side, float height) {
        float left = cx - side * .5f + dp(13);
        float right = cx + side * .5f - dp(13);
        float split = height * .5f;
        float top = dp(48);
        float upperBottom = split - dp(7);
        float lowerTop = split + dp(7);
        float bottom = height - dp(40);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(14));
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        c.drawText("油門／倒車", cx, dp(28), paint);
        drawThrottleButton(c, left, top, right, upperBottom, 1, "▲  前進");
        drawThrottleButton(c, left, lowerTop, right, bottom, -1, "▼  倒車");
        paint.setFakeBoldText(false);
        paint.setTextSize(dp(11));
        paint.setColor(Color.rgb(220, 235, 244));
        c.drawText("按住行駛，放開停止", cx, height - dp(14), paint);
    }

    private void drawThrottleButton(Canvas c, float left, float top, float right, float bottom,
                                    int value, String label) {
        boolean active = buttonThrottle == value;
        paint.setColor(active ? colorAccent : Color.rgb(42, 62, 82));
        c.drawRoundRect(left, top, right, bottom, dp(14), dp(14), paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(17));
        paint.setFakeBoldText(true);
        paint.setColor(active ? Color.rgb(17, 26, 39) : Color.rgb(220, 235, 244));
        c.drawText(label, (left + right) * .5f, (top + bottom) * .5f + dp(6), paint);
        paint.setFakeBoldText(false);
    }

    private int throttleButtonAt(float x, float y) {
        float width = getWidth(), height = getHeight();
        float side = width * DualStickInput.SIDE_FRACTION;
        float left = width - side + dp(13);
        float right = width - dp(13);
        float split = height * .5f;
        if (x < left || x > right) return 0;
        if (y >= dp(48) && y <= split - dp(7)) return 1;
        if (y >= split + dp(7) && y <= height - dp(40)) return -1;
        return 0;
    }

    private void notifyChanged(int oldSteer, int oldThrottle) {
        if (oldSteer != input.steering() || oldThrottle != buttonThrottle) {
            invalidate();
            listener.onAxes(input.steering(), buttonThrottle);
        }
    }

    public void cancelInput() {
        int oldSteer = input.steering(), oldThrottle = buttonThrottle;
        input.cancel();
        throttlePointer = -1;
        buttonThrottle = 0;
        notifyChanged(oldSteer, oldThrottle);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int beforeSteer = input.steering(), beforeThrottle = buttonThrottle;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int index = event.getActionIndex();
            int id = event.getPointerId(index);
            float x = event.getX(index), y = event.getY(index);
            if (x >= getWidth() * (1f - DualStickInput.SIDE_FRACTION)) {
                int pressed = throttleButtonAt(x, y);
                if (pressed != 0 && throttlePointer == -1) {
                    throttlePointer = id;
                    buttonThrottle = pressed;
                }
            } else {
                input.down(id, x, y, getWidth(), getHeight());
            }
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int index = 0; index < event.getPointerCount(); index++) {
                int id = event.getPointerId(index);
                float x = event.getX(index), y = event.getY(index);
                input.move(id, x, y, getWidth(), getHeight());
                // A physical throttle hold follows its original pointer until UP/CANCEL.
                // Small thumb drift must NOT release the accelerator or reverse button.
                // Sliding across to the other button also cannot silently reverse.
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int id = event.getPointerId(event.getActionIndex());
            input.up(id);
            if (id == throttlePointer) {
                throttlePointer = -1;
                buttonThrottle = 0;
            }
            if (action == MotionEvent.ACTION_UP) performClick();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            input.cancel();
            throttlePointer = -1;
            buttonThrottle = 0;
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
