package com.crew.car;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Experimental WLtoys 6405 UDP 1234 HEVC decoder (on-device Android MediaCodec).
 * Wire framing adapted independently from the public analysis by lcgamboa:
 * 32-byte little endian header + HEVC fragment payload.
 * No fallback to fake JPEG content. Device/firmware compatibility unverified.
 */
public final class WltoysVideoStream {
    private static final int MAX_FRAME = 1024 * 1024;
    private final CarNetwork network;
    private final CarLog log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean copying = new AtomicBoolean();
    private volatile boolean running;
    private volatile long sessionId;
    private volatile Thread worker;
    private volatile DatagramSocket listening;

    public WltoysVideoStream(CarNetwork network, CarLog log) {
        this.network = network;
        this.log = log;
    }

    public synchronized void start(String host, SurfaceView view, Consumer<Bitmap> frameCallback) {
        stop();
        if (host == null || host.isBlank()) {
            log.e("HEVC", "camera host missing");
            return;
        }
        if (!view.getHolder().getSurface().isValid()) {
            log.e("HEVC", "Surface not ready; retry after camera view is visible");
            return;
        }
        long token = ++sessionId;
        running = true;
        copying.set(false);
        worker = new Thread(() -> receive(host, view, frameCallback, token), "CrewCar-HEVC");
        worker.start();
    }

    private void receive(String host, SurfaceView view, Consumer<Bitmap> frameCallback, long token) {
        MediaCodec decoder = null;
        DatagramSocket dataSocket = null;
        String error = null;
        int packets = 0;
        int frames = 0;
        int dropped = 0;
        int decoded = 0;
        long reportAt = SystemClock.elapsedRealtime();
        long nextCopy = 0;
        WltoysFrameAssembler assembler = new WltoysFrameAssembler();

        try {
            Network wifi = network.wifi();
            if (wifi == null) throw new IllegalStateException("car Wi-Fi not connected");
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1280, 720);
            decoder.configure(format, view.getHolder().getSurface(), null, 0);
            decoder.start();
            log.i("HEVC", "Android MediaCodec decoder started");

            dataSocket = new DatagramSocket(null);
            dataSocket.setReuseAddress(true);
            dataSocket.bind(new java.net.InetSocketAddress("0.0.0.0", WltoysProtocol.CAMERA_DATA_PORT));
            wifi.bindSocket(dataSocket);
            dataSocket.setSoTimeout(500);
            listening = dataSocket;
            // Must bind receive port before requesting camera transmission.
            network.send(host, WltoysProtocol.CAMERA_COMMAND_PORT, false,
                    CommandCodec.encode(WltoysProtocol.CAMERA_START_HEX, true));
            log.i("HEVC", "camera START sent target=" + host + ":" +
                    WltoysProtocol.CAMERA_COMMAND_PORT + " rxPort=" + WltoysProtocol.CAMERA_DATA_PORT);

            byte[] buffer = new byte[2048];
            MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
            long ptsUs = 0;
            while (running && token == sessionId) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    dataSocket.receive(packet);
                } catch (java.net.SocketTimeoutException ignored) {
                    if (SystemClock.elapsedRealtime() - reportAt >= 5000) {
                        log.i("HEVC", "rx packets=" + packets + " frames=" + frames +
                                " decoded=" + decoded + " dropped=" + dropped);
                        reportAt = SystemClock.elapsedRealtime();
                    }
                    continue;
                }
                packets++;
                int len = packet.getLength();
                byte[] assembled = assembler.accept(buffer, len);
                if (assembled != null) {
                    int expected = assembled.length;
                    frames++;
                    int input = decoder.dequeueInputBuffer(10000);
                    if (input >= 0) {
                        ByteBuffer dst = decoder.getInputBuffer(input);
                        if (dst != null && dst.capacity() >= expected) {
                            dst.clear();
                            dst.put(assembled, 0, expected);
                            decoder.queueInputBuffer(input, 0, expected, ptsUs, 0);
                            ptsUs += 50000; // nominal 20 fps, device timestamps unavailable
                        } else {
                            dropped++;
                            decoder.queueInputBuffer(input, 0, 0, ptsUs, 0);
                        }
                    } else dropped++;

                    int output;
                    while ((output = decoder.dequeueOutputBuffer(outputInfo, 0)) >= 0) {
                        decoder.releaseOutputBuffer(output, true);
                        decoded++;
                        long now = SystemClock.elapsedRealtime();
                        if (now >= nextCopy && copying.compareAndSet(false, true)) {
                            nextCopy = now + 1000;
                            capture(view, frameCallback, token);
                        }
                    }
                 }

                if (SystemClock.elapsedRealtime() - reportAt >= 5000) {
                    log.i("HEVC", "rx packets=" + packets + " frames=" + frames +
                            " decoded=" + decoded + " dropped=" + dropped);
                    reportAt = SystemClock.elapsedRealtime();
                }
            }
        } catch (Throwable t) {
            if (running) {
                error = t.getClass().getSimpleName();
                log.e("HEVC", "decoder/UDP failed class=" + error);
            }
        } finally {
            if (token == sessionId) running = false;
            if (dataSocket != null) dataSocket.close();
            if (listening == dataSocket) listening = null;
            if (token == sessionId) {
                try {
                    network.send(host, WltoysProtocol.CAMERA_COMMAND_PORT, false,
                            CommandCodec.encode(WltoysProtocol.CAMERA_STOP_HEX, true));
                    log.i("HEVC", "camera STOP sent");
                } catch (Exception e) { log.e("HEVC", "camera STOP failed " + e.getClass().getSimpleName()); }
            } else log.i("HEVC", "session superseded; old STOP suppressed");
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                decoder.release();
            }
            log.i("HEVC", "closed packets=" + packets + " frames=" + frames +
                    " decoded=" + decoded + " dropped=" + dropped);
        }
    }

    /** PixelCopy obtains a real rendered frame, not a stale encoded-buffer placeholder. */
    private void capture(SurfaceView view, Consumer<Bitmap> callback, long token) {
        main.post(() -> {
            if (!running || token != sessionId || view.getWidth() <= 0 || view.getHeight() <= 0 ||
                    !view.getHolder().getSurface().isValid()) {
                copying.set(false);
                return;
            }
            int targetW = Math.max(1, Math.min(512, view.getWidth()));
            int targetH = Math.max(1, view.getHeight() * targetW / Math.max(1, view.getWidth()));
            Bitmap bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            try {
                PixelCopy.request(view, bitmap, result -> {
                    copying.set(false);
                    if (running && token == sessionId && result == PixelCopy.SUCCESS) callback.accept(bitmap);
                    else bitmap.recycle();
                }, main);
            } catch (Exception e) {
                copying.set(false);
                bitmap.recycle();
                log.e("HEVC", "PixelCopy failed " + e.getClass().getSimpleName());
            }
        });
    }

    public synchronized void stop() {
        running = false;
        DatagramSocket s = listening;
        if (s != null) s.close();
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            if (t != Thread.currentThread()) {
                try { t.join(350); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
        // After the old worker has had a chance to issue camera STOP,
        // invalidate late callbacks from the old session.
        ++sessionId;
        worker = null;
    }
}
