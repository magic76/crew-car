package com.crew.car;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/** Draws person boxes above FPV. Touch selection NEVER moves the vehicle. */
public final class VisionOverlay extends View {
    public interface Listener { void onSelectionChanged(boolean selected); }

    private final PersonTracker tracker = new PersonTracker();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private boolean enabled;
    private boolean aspectFit;
    private float imageAspect = 16f / 9f;
    private long inferenceMs;

    public VisionOverlay(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setBackgroundColor(Color.TRANSPARENT);
        setClickable(true);
        setContentDescription("Crew Vision：點選綠色人物框鎖定目標，不能控制馬達");
    }

    public void setEnabled(boolean value) {
        enabled = value;
        tracker.clear();
        invalidate();
    }

    public void update(List<PersonTracker.Box> people, long at, long elapsedMs,
                       float sourceAspect, boolean imageFit) {
        if (!enabled) return;
        imageAspect = sourceAspect > 0f ? sourceAspect : 16f / 9f;
        aspectFit = imageFit;
        inferenceMs = elapsedMs;
        tracker.observe(people, at);
        invalidate();
    }

    public void expire(long now) {
        if (!enabled) return;
        tracker.expire(now);
        invalidate();
    }

    public boolean hasSelection() { return tracker.hasSelection(); }
    public boolean targetLost() { return tracker.targetLost(); }
    public int personCount() { return tracker.boxes().size(); }
    public long lastInferenceMs() { return inferenceMs; }
    public void clear() { tracker.clear(); invalidate(); }

    private RectF displayArea() {
        float w = getWidth(), h = getHeight();
        if (!aspectFit || w <= 0 || h <= 0) return new RectF(0, 0, w, h);
        float viewAspect = w / h;
        if (viewAspect > imageAspect) {
            float imageW = h * imageAspect;
            return new RectF((w - imageW) / 2, 0, (w + imageW) / 2, h);
        }
        float imageH = w / imageAspect;
        return new RectF(0, (h - imageH) / 2, w, (h + imageH) / 2);
    }

    private RectF toScreen(PersonTracker.Box b, RectF area) {
        return new RectF(
                area.left + b.left * area.width(),
                area.top + b.top * area.height(),
                area.left + b.right * area.width(),
                area.top + b.bottom * area.height());
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!enabled) return;
        RectF area = displayArea();
        List<PersonTracker.Box> boxes = tracker.boxes();
        int selected = tracker.selectedIndex();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        for (int i = 0; i < boxes.size(); i++) {
            RectF box = toScreen(boxes.get(i), area);
            paint.setColor(i == selected ? Color.rgb(255, 175, 60) : Color.rgb(51, 229, 133));
            canvas.drawRoundRect(box, dp(5), dp(5), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(12));
            paint.setFakeBoldText(true);
            canvas.drawText(i == selected ? "追蹤目標" :
                    "人 " + Math.round(boxes.get(i).score * 100) + "%",
                    box.left + dp(3), Math.max(dp(13), box.top - dp(4)), paint);
            paint.setFakeBoldText(false);
            paint.setStyle(Paint.Style.STROKE);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xD9152633);
        canvas.drawRoundRect(dp(3), dp(3), Math.min(getWidth() - dp(3), dp(168)),
                dp(27), dp(7), dp(7), paint);
        paint.setTextSize(dp(11));
        paint.setColor(Color.WHITE);
        String label = tracker.targetLost() ? "目標遺失 · 請重新選擇" :
                selected >= 0 ? "已鎖定（僅視覺）" :
                        "辨識 " + boxes.size() + " 人 · 點框鎖定";
        canvas.drawText(label, dp(10), dp(20), paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!enabled) return false;
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
        RectF area = displayArea();
        if (area.width() <= 0 || area.height() <= 0) return false;
        float nx = (event.getX() - area.left) / area.width();
        float ny = (event.getY() - area.top) / area.height();
        if (!tracker.select(nx, ny, android.os.SystemClock.elapsedRealtime())) return false;
        invalidate();
        listener.onSelectionChanged(true);
        performClick();
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}
