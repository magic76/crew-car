package com.crew.car;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offline, device-only MediaPipe person detector.
 * Not connected to CarControl or any Gemini motor tool. Busy frames are dropped.
 */
public final class CrewVision {
    public interface Listener {
        void onPeople(List<PersonTracker.Box> people, long frameAt, long inferMs);
        void onError(String message);
    }

    public static final int TARGET_INTERVAL_MS = 200; // 5 FPS target, not a guarantee
    private static final String MODEL_ASSET = "efficientdet_lite0.tflite";
    private final Context appContext;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CrewVision-CPU");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean enabled;
    private volatile long generation;
    private long lastAcceptedAt;
    private volatile ObjectDetector detector;

    public CrewVision(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean enabled() { return enabled; }
    public boolean ready() { return enabled && detector != null; }
    public boolean busy() { return busy.get(); }

    public synchronized void setEnabled(boolean active) {
        if (enabled == active) return;
        enabled = active;
        long token = ++generation;
        lastAcceptedAt = 0;
        executor.execute(() -> {
            if (detector != null) {
                detector.close();
                detector = null;
            }
            if (!active || !enabled || token != generation) return;
            try {
                ObjectDetector.ObjectDetectorOptions options =
                        ObjectDetector.ObjectDetectorOptions.builder()
                                .setBaseOptions(BaseOptions.builder()
                                        .setModelAssetPath(MODEL_ASSET).build())
                                .setRunningMode(RunningMode.IMAGE)
                                .setScoreThreshold(.35f)
                                .setMaxResults(8)
                                .build();
                detector = ObjectDetector.createFromOptions(appContext, options);
            } catch (Exception | LinkageError e) {
                enabled = false;
                main.post(() -> {
                    if (token == generation) listener.onError(
                            "MediaPipe 模型不可用：" + e.getClass().getSimpleName());
                });
            }
        });
    }

    /**
     * Ownership: if ownsFrame, this module always recycles the supplied bitmap.
     * MJPEG caller may pass a borrowed bitmap; it will never be recycled here.
     */
    public synchronized boolean submit(Bitmap frame, boolean ownsFrame) {
        long now = SystemClock.elapsedRealtime();
        if (!enabled || frame == null || frame.isRecycled() || detector == null ||
                now - lastAcceptedAt < TARGET_INTERVAL_MS ||
                !busy.compareAndSet(false, true)) {
            if (ownsFrame && frame != null && !frame.isRecycled()) frame.recycle();
            return false;
        }
        lastAcceptedAt = now;
        long token = generation;
        try {
            executor.execute(() -> {
                Bitmap reduced = null;
                try {
                    if (!enabled || token != generation || detector == null) return;
                    int w = frame.getWidth(), h = frame.getHeight();
                    int outW = Math.min(320, w);
                    int outH = Math.max(1, Math.round(h * outW / Math.max(1f, w)));
                    reduced = Bitmap.createScaledBitmap(frame, outW, outH, true);
                    MPImage image = new BitmapImageBuilder(reduced).build();
                    long before = SystemClock.elapsedRealtime();
                    ObjectDetectorResult result = detector.detect(image);
                    long infer = SystemClock.elapsedRealtime() - before;
                    List<PersonTracker.Box> people = new ArrayList<>();
                    for (Detection detection : result.detections()) {
                        boolean person = false;
                        float confidence = 0f;
                        for (Category category : detection.categories()) {
                            if ("person".equals(category.categoryName())) {
                                person = true;
                                confidence = category.score();
                                break;
                            }
                        }
                        if (!person) continue;
                        RectF rect = detection.boundingBox();
                        float left = Math.max(0f, Math.min(1f, rect.left / outW));
                        float top = Math.max(0f, Math.min(1f, rect.top / outH));
                        float right = Math.max(0f, Math.min(1f, rect.right / outW));
                        float bottom = Math.max(0f, Math.min(1f, rect.bottom / outH));
                        if (!Float.isFinite(left) || !Float.isFinite(top) ||
                                !Float.isFinite(right) || !Float.isFinite(bottom) ||
                                !Float.isFinite(confidence) ||
                                right <= left || bottom <= top) continue;
                        people.add(new PersonTracker.Box(left, top, right, bottom, confidence));
                    }
                    main.post(() -> {
                        if (enabled && token == generation)
                            listener.onPeople(people, now, infer);
                    });
                } catch (Exception | LinkageError error) {
                    enabled = false;
                    main.post(() -> {
                        if (token == generation) listener.onError(
                                "Vision 推論失敗：" + error.getClass().getSimpleName());
                    });
                } finally {
                    if (reduced != null && reduced != frame && !reduced.isRecycled())
                        reduced.recycle();
                    if (ownsFrame && frame != null && !frame.isRecycled())
                        frame.recycle();
                    busy.set(false);
                }
            });
            return true;
        } catch (RuntimeException e) {
            busy.set(false);
            if (ownsFrame && !frame.isRecycled()) frame.recycle();
            return false;
        }
    }

    public void close() {
        enabled = false;
        ++generation;
        executor.execute(() -> {
            if (detector != null) {
                detector.close();
                detector = null;
            }
        });
        executor.shutdown();
    }
}
