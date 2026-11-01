package com.crew.car;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Diagnostic HTTP JPEG/MJPEG adapter. The WL Tech camera endpoint is NOT known yet.
 * This deliberately does not pretend to support proprietary UDP/H264 feeds.
 */
public final class CameraStream {
    private static final int MAX_JPEG_BYTES = 2 * 1024 * 1024;
    private final CarNetwork network;
    private final CarLog log;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile HttpURLConnection connection;

    public CameraStream(CarNetwork network, CarLog log) {
        this.network = network;
        this.log = log;
    }

    public boolean isRunning() { return running; }

    public synchronized void start(String source, Consumer<Bitmap> onFrame) {
        stop();
        if (source == null || !(source.startsWith("http://") || source.startsWith("https://"))) {
            log.e("CAM", "請填入實機確認的 http(s) JPEG/MJPEG URL");
            return;
        }
        running = true;
        thread = new Thread(() -> capture(source, onFrame), "CrewCar-Camera");
        thread.start();
    }

    private void capture(String source, Consumer<Bitmap> onFrame) {
        int count = 0;
        long lastLog = 0;
        long lastDecode = 0;
        try {
            HttpURLConnection conn = (HttpURLConnection) network.carConnection(new URL(source));
            connection = conn;
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(4000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "multipart/x-mixed-replace, image/jpeg");
            int code = conn.getResponseCode();
            String type = conn.getContentType();
            log.i("CAM", "HTTP " + code + " content-type=" + type);
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            if (type != null && !type.contains("jpeg") && !type.contains("multipart") && !type.contains("octet")) {
                log.e("CAM", "內容可能不是 JPEG/MJPEG；專有串流需要另一個 adapter");
            }
            try (InputStream input = new BufferedInputStream(conn.getInputStream(), 16 * 1024)) {
                ByteArrayOutputStream frame = new ByteArrayOutputStream(128 * 1024);
                boolean inJpeg = false;
                int prev = -1;
                int next;
                while (running && (next = input.read()) != -1) {
                    if (!inJpeg) {
                        if (prev == 0xff && next == 0xd8) {
                            frame.reset();
                            frame.write(0xff);
                            frame.write(0xd8);
                            inJpeg = true;
                        }
                    } else {
                        frame.write(next);
                        if (frame.size() > MAX_JPEG_BYTES) {
                            inJpeg = false;
                            frame.reset();
                            log.e("CAM", "JPEG > 2MB, discard and resync");
                        } else if (prev == 0xff && next == 0xd9) {
                            long decodeAt = android.os.SystemClock.elapsedRealtime();
                            if (decodeAt - lastDecode < 95) {
                                inJpeg = false;
                                frame.reset();
                                prev = next;
                                continue;
                            }
                            lastDecode = decodeAt;
                            byte[] bytes = frame.toByteArray();
                            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            if (bitmap != null) {
                                count++;
                                onFrame.accept(bitmap);
                                long now = android.os.SystemClock.elapsedRealtime();
                                if (now - lastLog > 5000) {
                                    log.i("CAM", "frames=" + count + " resolution=" +
                                            bitmap.getWidth() + "x" + bitmap.getHeight());
                                    lastLog = now;
                                }
                            }
                            inJpeg = false;
                            frame.reset();
                        }
                    }
                    prev = next;
                }
            }
            if (running) log.e("CAM", "stream EOF; received frames=" + count);
        } catch (Exception e) {
            if (running) log.e("CAM", "stream failed " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            running = false;
            HttpURLConnection conn = connection;
            if (conn != null) conn.disconnect();
            connection = null;
        }
    }

    public synchronized void stop() {
        running = false;
        HttpURLConnection c = connection;
        if (c != null) c.disconnect();
        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;
    }
}
