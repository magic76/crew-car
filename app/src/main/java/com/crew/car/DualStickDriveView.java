package com.crew.car;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.HapticFeedbackConstants;
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
    private final SteeringButtonInput steeringButtons = new SteeringButtonInput();
    private DriverLayoutConfig layout = DriverLayoutConfig.defaults();
    private boolean controlsAvailable;
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
        setLayoutConfig(DriverLayoutConfig.defaults());
    }

    public void setLayoutConfig(DriverLayoutConfig next) {
        if (next == null) throw new IllegalArgumentException("missing control layout");
        cancelInput();
        layout = next;
        input.configure(next.sideFraction(), next.deadZonePercent / 100f);
        steeringButtons.cancel();
        setHapticFeedbackEnabled(next.haptics);
        setContentDescription(next.buttonSteering ?
                "左側大按鍵：左轉右轉；右側上方按住前進、下方按住倒車，放開停止" :
                "左側虛擬搖桿控制方向；右側上方前進、下方倒車，放開停止");
        invalidate();
    }

    public DriverLayoutConfig layoutConfig() { return layout; }
    public void setControlsAvailable(boolean available) {
        if (controlsAvailable != available) {
            controlsAvailable = available;
            invalidate();
        }
    }
    private int steering() {
        return layout.buttonSteering ? steeringButtons.steering() : input.steering();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float width = getWidth(), height = getHeight();
        float side = width * layout.sideFraction();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colorPanel);
        c.drawRoundRect(0, 0, side - dp(2), height, dp(16), dp(16), paint);
        c.drawRoundRect(width - side + dp(2), 0, width, height, dp(16), dp(16), paint);

        if (layout.buttonSteering) drawSteeringButtons(c, side, height);
        else drawSteeringPanel(c, side * .5f, height * .53f, side, height, input.steering());
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

    private void drawSteeringButtons(Canvas c, float width, float height) {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(13));
        paint.setColor(Color.WHITE);
        paint.setFakeBoldText(true);
        c.drawText("方向", width * .5f, dp(20), paint);
        float left = dp(4), right = width - dp(4), top = dp(28), bottom = height - dp(22);
        float mid = width * .5f, gap = dp(4);
        drawSteeringButton(c, left, top, mid - gap, bottom, -1, "◀");
        drawSteeringButton(c, mid + gap, top, right, bottom, 1, "▶");
        paint.setFakeBoldText(false);
        paint.setColor(Color.rgb(204, 225, 238));
        paint.setTextSize(dp(10));
        c.drawText("放開回正", width * .5f, height - dp(7), paint);
    }

    private void drawSteeringButton(Canvas c, float l, float t, float r, float b,
                                    int direction, String label) {
        boolean pressed = controlsAvailable && steering() == direction;
        paint.setColor(pressed ? colorAccent : Color.rgb(42, 62, 82));
        c.drawRoundRect(l, t, r, b, dp(12), dp(12), paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(24));
        paint.setFakeBoldText(true);
        paint.setColor(pressed ? colorPanel : Color.WHITE);
        c.drawText(label, (l+r)*.5f, (t+b)*.5f+dp(8), paint);
        paint.setFakeBoldText(false);
    }

    private void drawThrottleButtons(Canvas c, float cx, float side, float height) {
        float left = cx - side * .5f + dp(4);
        float right = cx + side * .5f - dp(4);
        float top = Math.max(dp(24), dp(27 + layout.offsetDp));
        float bottom = Math.min(height - dp(14), height - dp(18) + dp(layout.offsetDp));
        float split = (top + bottom) * .5f;
        float upperBottom = split - dp(layout.gapDp) * .5f;
        float lowerTop = split + dp(layout.gapDp) * .5f;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(14));
        paint.setFakeBoldText(true);
        paint.setColor(Color.WHITE);
        c.drawText("油門", cx, dp(20), paint);
        drawThrottleButton(c, left, top, right, upperBottom, 1, "▲  前進");
        drawThrottleButton(c, left, lowerTop, right, bottom, -1, "▼  倒車");
        paint.setFakeBoldText(false);
        paint.setTextSize(dp(11));
        paint.setColor(Color.rgb(220, 235, 244));
        c.drawText("按住行駛｜放開停止", cx, height - dp(6), paint);
    }

    private void drawThrottleButton(Canvas c, float left, float top, float right, float bottom,
                                    int value, String label) {
        boolean active = controlsAvailable && buttonThrottle == value;
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
        float left = width - side + dp(4);
        float right = width - dp(4);
        float top = Math.max(dp(24), dp(27 + layout.offsetDp));
        float bottom = Math.min(height - dp(14), height - dp(18) + dp(layout.offsetDp));
        float split = (top + bottom) * .5f;
        if (x < left || x > right) return 0;
        if (y >= top && y <= split - dp(layout.gapDp) * .5f) return 1;
        if (y >= split + dp(layout.gapDp) * .5f && y <= bottom) return -1;
        return 0;
    }

    private void notifyChanged(int oldSteer, int oldThrottle) {
        if (oldSteer != steering() || oldThrottle != buttonThrottle) {
            invalidate();
            listener.onAxes(steering(), buttonThrottle);
            if (layout.haptics && controlsAvailable &&
                    (steering() != 0 || buttonThrottle != 0))
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    public void cancelInput() {
        int oldSteer = steering(), oldThrottle = buttonThrottle;
        input.cancel();
        steeringButtons.cancel();
        throttlePointer = -1;
        buttonThrottle = 0;
        notifyChanged(oldSteer, oldThrottle);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int beforeSteer = steering(), beforeThrottle = buttonThrottle;
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
            } else if (x < getWidth() * layout.sideFraction()) {
                if (layout.buttonSteering)
                    steeringButtons.down(id, x, y, getWidth() * layout.sideFraction(), getHeight());
                else input.down(id, x, y, getWidth(), getHeight());
            }
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int index = 0; index < event.getPointerCount(); index++) {
                int id = event.getPointerId(index);
                float x = event.getX(index), y = event.getY(index);
                if (layout.buttonSteering)
                    steeringButtons.move(id, x, y, getWidth() * layout.sideFraction(), getHeight());
                else input.move(id, x, y, getWidth(), getHeight());
                if (id == throttlePointer && throttleButtonAt(x, y) != buttonThrottle) {
                    throttlePointer = -1;
                    buttonThrottle = 0;
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int id = event.getPointerId(event.getActionIndex());
            input.up(id);
            steeringButtons.up(id);
            if (id == throttlePointer) {
                throttlePointer = -1;
                buttonThrottle = 0;
            }
            if (action == MotionEvent.ACTION_UP) performClick();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            input.cancel();
            steeringButtons.cancel();
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
