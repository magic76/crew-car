package com.crew.car;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Single-surface drag pad. Works for 8 directions without multi-view pointer routing.
 * Releasing/cancelling the controlling pointer always emits neutral, never a stored axis.
 */
public final class DrivePadView extends View {
    public interface Listener {
        void onAxes(int steering, int throttle);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private int pointerId = -1;
    private int steering;
    private int throttle;
    private final int background = Color.rgb(22, 32, 46);
    private final int primary = Color.rgb(65, 196, 231);

    public DrivePadView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setContentDescription("八方向拖曳控制盤，放開立即停車");
        setFocusable(true);
    }

    private float dp(float x) { return x * getResources().getDisplayMetrics().density; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(background);
        canvas.drawRoundRect(dp(2), dp(2), w - dp(2), h - dp(2), dp(22), dp(22), paint);

        paint.setColor(Color.rgb(49, 70, 91));
        paint.setStrokeWidth(dp(2));
        canvas.drawLine(w * .5f, dp(20), w * .5f, h - dp(20), paint);
        canvas.drawLine(dp(20), h * .5f, w - dp(20), h * .5f, paint);

        paint.setColor(Color.rgb(83, 103, 125));
        canvas.drawCircle(w / 2f, h / 2f, dp(33), paint);
        paint.setColor(primary);
        float cx = w * .5f + steering * w * .31f;
        float cy = h * .5f - throttle * h * .31f;
        canvas.drawCircle(cx, cy, dp(25), paint);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(21));
        paint.setFakeBoldText(true);
        canvas.drawText("▲", w * .5f, h * .17f, paint);
        canvas.drawText("▼", w * .5f, h * .89f, paint);
        canvas.drawText("◀", w * .14f, h * .53f, paint);
        canvas.drawText("▶", w * .86f, h * .53f, paint);
        paint.setFakeBoldText(false);
    }

    private void emit(int steer, int gas) {
        if (steer == steering && gas == throttle) return;
        steering = steer;
        throttle = gas;
        invalidate();
        listener.onAxes(steer, gas);
    }

    /** Called by the activity on E-STOP, pause, losing authorization or mode changes. */
    public void cancelInput() {
        pointerId = -1;
        emit(0, 0);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            pointerId = event.getPointerId(0);
            getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cancelInput();
            if (action == MotionEvent.ACTION_UP) performClick();
            return true;
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            if (event.getPointerId(event.getActionIndex()) == pointerId) cancelInput();
            return true;
        } else if (action != MotionEvent.ACTION_MOVE) {
            return true;
        }
        if (pointerId == -1) return true;
        int i = event.findPointerIndex(pointerId);
        if (i < 0) { cancelInput(); return true; }
        int[] axes = DriveAxes.fromPoint(event.getX(i), event.getY(i), getWidth(), getHeight());
        emit(axes[0], axes[1]);
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
