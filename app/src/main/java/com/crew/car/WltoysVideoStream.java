package com.crew.car;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Network;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;

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
    private volatile WltoysCameraCommandChannel cameraCommands;

    public WltoysVideoStream(CarNetwork network, CarLog log) {
        this.network = network;
        this.log = log;
    }

    public boolean isRunning() { return running; }

    public synchronized void start(String host, TextureView view, Consumer<Bitmap> frameCallback,
                                   Consumer<String> statusCallback) {
        stop();
        if (host == null || host.isBlank()) {
            log.e("HEVC", "camera host missing");
            publishStatus(statusCallback, sessionId, "攝影機 IP 未設定");
            return;
        }
        SurfaceTexture texture = view.isAvailable() ? view.getSurfaceTexture() : null;
        if (texture == null) {
            log.e("HEVC", "TextureView not ready; retry after camera view is visible");
            publishStatus(statusCallback, sessionId, "畫面元件尚未就緒；請回到攝影機區再連線");
            return;
        }
        Surface outputSurface = new Surface(texture);
        long token = ++sessionId;
        running = true;
        copying.set(false);
        publishStatus(statusCallback, token, "啟動 HEVC 解碼器…");
        worker = new Thread(() -> receive(host, view, outputSurface, frameCallback, statusCallback, token),
                "CrewCar-HEVC");
        worker.start();
    }

    private void receive(String host, TextureView view, Surface outputSurface, Consumer<Bitmap> frameCallback,
                         Consumer<String> statusCallback, long token) {
        MediaCodec decoder = null;
        DatagramSocket dataSocket = null;
        WltoysCameraCommandChannel commandChannel = null;
        String error = null;
        int packets = 0;
        int videoPackets = 0;
        int cameraReplies = 0;
        int invalidPackets = 0;
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
            decoder.configure(format, outputSurface, null, 0);
            decoder.start();
            log.i("HEVC", "Android MediaCodec decoder started");
            publishStatus(statusCallback, token, "解碼器就緒，準備聽 UDP 1234…");

            dataSocket = new DatagramSocket(null);
            dataSocket.setReuseAddress(true);
            dataSocket.bind(new java.net.InetSocketAddress("0.0.0.0", WltoysProtocol.CAMERA_DATA_PORT));
            wifi.bindSocket(dataSocket);
            // Short timeout lets the receiver thread issue camera STOP promptly
            // when the UI requests shutdown, without network I/O on the UI thread.
            dataSocket.setSoTimeout(100);
            // Keep the video receiver and camera command sender on the same
            // Wi-Fi-bound UDP 1234 socket. A separate ephemeral command socket
            // gives hotspot NAT no mapping for replies to local UDP 1234.
            commandChannel = new WltoysCameraCommandChannel(
                    dataSocket, wifi.getAllByName(host)[0], WltoysProtocol.CAMERA_COMMAND_PORT);
            cameraCommands = commandChannel;
            listening = dataSocket;
            if (!running || token != sessionId) return;
            commandChannel.start();
            log.i("HEVC", "camera START sent target=" + host + ":" +
                    WltoysProtocol.CAMERA_COMMAND_PORT + " srcPort=" + commandChannel.localPort() +
                    " rxPort=" + WltoysProtocol.CAMERA_DATA_PORT + " network=" + wifi);
            publishStatus(statusCallback, token, "已從 UDP 1234 啟動相機；等待影像回傳…");

            byte[] buffer = new byte[2048];
            MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
            long ptsUs = 0;
            while (running && token == sessionId) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    dataSocket.receive(packet);
                } catch (java.net.SocketTimeoutException ignored) {
                    if (SystemClock.elapsedRealtime() - reportAt >= 5000) {
                        log.i("HEVC", "rx packets=" + packets + " video=" + videoPackets +
                                " cameraReplies=" + cameraReplies + " invalid=" + invalidPackets +
                                " frames=" + frames + " decoded=" + decoded + " dropped=" + dropped);
                        publishStatus(statusCallback, token,
                                cameraStatus(videoPackets, cameraReplies, invalidPackets, frames, decoded, dropped));
                        reportAt = SystemClock.elapsedRealtime();
                    }
                    continue;
                }
                packets++;
                int len = packet.getLength();
                if (packets <= 3) {
                    log.i("HEVC", "packet #" + packets + " from=" +
                            packet.getAddress().getHostAddress() + ":" + packet.getPort() +
                            " toLocalPort=" + dataSocket.getLocalPort() + " bytes=" + len);
                }
                if (WltoysCameraPacketInspector.likelyCameraCommandReply(
                        packet.getPort(), buffer, len)) {
                    cameraReplies++;
                    continue;
                }
                if (!WltoysCameraPacketInspector.looksLikeVideoFragment(buffer, len)) {
                    invalidPackets++;
                    continue;
                }
                videoPackets++;
                if (videoPackets == 1) {
                    log.i("HEVC", "first valid video fragment from=" +
                            packet.getAddress().getHostAddress() + ":" + packet.getPort() +
                            " bytes=" + len);
                }
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
                    while (true) {
                        output = decoder.dequeueOutputBuffer(outputInfo, 0);
                        if (output >= 0) {
                            decoder.releaseOutputBuffer(output, true);
                            decoded++;
                            if (decoded <= 5 || decoded % 120 == 0) {
                                log.i("HEVC", "decoder output #" + decoded + " size=" +
                                        outputInfo.size + " flags=" + outputInfo.flags +
                                        " ptsUs=" + outputInfo.presentationTimeUs);
                            }
                            long now = SystemClock.elapsedRealtime();
                            if (now >= nextCopy && copying.compareAndSet(false, true)) {
                                nextCopy = now + 1000;
                                capture(view, frameCallback, statusCallback, token);
                            }
                        } else {
                            if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
                                log.i("HEVC", "decoder output format=" + decoder.getOutputFormat());
                            break;
                        }
                    }
                 }

                if (SystemClock.elapsedRealtime() - reportAt >= 5000) {
                    log.i("HEVC", "rx packets=" + packets + " video=" + videoPackets +
                            " cameraReplies=" + cameraReplies + " invalid=" + invalidPackets +
                            " frames=" + frames + " decoded=" + decoded + " dropped=" + dropped);
                    publishStatus(statusCallback, token,
                            cameraStatus(videoPackets, cameraReplies, invalidPackets, frames, decoded, dropped));
                    reportAt = SystemClock.elapsedRealtime();
                }
            }
        } catch (Throwable t) {
            if (running) {
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
                log.e("HEVC", "decoder/UDP failed " + error);
                publishStatus(statusCallback, token, "攝影機錯誤：" + error);
            }
        } finally {
            if (token == sessionId) running = false;
            // STOP on the same socket before closing it. An explicit stop()
            // may already have sent STOP and closed this socket; in that case
            // the channel suppresses a duplicate command.
            if (token == sessionId && commandChannel != null) {
                try {
                    if (commandChannel.stop()) log.i("HEVC", "camera STOP sent from UDP 1234");
                } catch (Exception e) {
                    log.e("HEVC", "camera STOP failed " + e.getClass().getSimpleName());
                }
            } else if (token != sessionId) log.i("HEVC", "session superseded; old STOP suppressed");
            if (dataSocket != null) dataSocket.close();
            if (listening == dataSocket) listening = null;
            if (cameraCommands == commandChannel) cameraCommands = null;
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                decoder.release();
            }
            outputSurface.release();
            log.i("HEVC", "closed packets=" + packets + " video=" + videoPackets +
                    " cameraReplies=" + cameraReplies + " invalid=" + invalidPackets +
                    " frames=" + frames + " decoded=" + decoded + " dropped=" + dropped);
            if (error != null) publishStatus(statusCallback, token, "攝影機停止：" + error);
        }
    }

    private String cameraStatus(int videoPackets, int cameraReplies, int invalidPackets,
                                int frames, int decoded, int dropped) {
        if (videoPackets == 0 && cameraReplies > 0)
            return "收到相機指令回覆 " + cameraReplies +
                    " 包，但沒有有效影像片段；手機熱點 NAT 可能未轉發 FPV";
        if (videoPackets == 0 && invalidPackets > 0)
            return "UDP 收到 " + invalidPackets + " 個非影像封包；影像串流尚未到達";
        if (videoPackets == 0)
            return "UDP 1234 尚無影像封包；請直連小車 Wi-Fi 對照或檢查熱點 NAT";
        if (frames == 0) return "已收 " + videoPackets + " 個影像片段，尚未組成完整影格";
        if (decoded == 0) return "已組成 " + frames + " 幀，解碼器尚未輸出影像（掉幀 " + dropped + "）";
        return "影像串流中：片段 " + videoPackets + "、組幀 " + frames +
                "、解碼輸出 " + decoded + "、掉幀 " + dropped;
    }

    private void publishStatus(Consumer<String> callback, long token, String text) {
        if (callback == null) return;
        main.post(() -> {
            if (token == sessionId) callback.accept(text);
        });
    }

    /** TextureView returns the rendered frame through Android's normal view composition. */
    private void capture(TextureView view, Consumer<Bitmap> callback,
                         Consumer<String> statusCallback, long token) {
        main.post(() -> {
            if (!running || token != sessionId || !view.isAvailable() ||
                    view.getWidth() <= 0 || view.getHeight() <= 0) {
                copying.set(false);
                return;
            }
            int targetW = Math.max(1, Math.min(512, view.getWidth()));
            int targetH = Math.max(1, view.getHeight() * targetW / Math.max(1, view.getWidth()));
            try {
                Bitmap bitmap = view.getBitmap(targetW, targetH);
                copying.set(false);
                if (bitmap != null && running && token == sessionId) {
                    log.i("HEVC", pixelStats(bitmap));
                    callback.accept(bitmap);
                } else if (bitmap != null) {
                    bitmap.recycle();
                } else {
                    String message = "TextureView 尚未提供可讀取的畫面";
                    log.e("HEVC", message);
                    publishStatus(statusCallback, token, message);
                }
            } catch (Exception e) {
                copying.set(false);
                log.e("HEVC", "TextureView capture failed " + e.getClass().getSimpleName());
            }
        });
    }

    /** Sample captured TextureView pixels to detect an all-black image. */
    private String pixelStats(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int stepX = Math.max(1, width / 32);
        int stepY = Math.max(1, height / 32);
        long total = 0;
        int samples = 0;
        int nonDark = 0;
        int min = 255;
        int max = 0;
        for (int y = 0; y < height; y += stepY) {
            for (int x = 0; x < width; x += stepX) {
                int pixel = bitmap.getPixel(x, y);
                int red = (pixel >>> 16) & 0xff;
                int green = (pixel >>> 8) & 0xff;
                int blue = pixel & 0xff;
                int luma = (red * 299 + green * 587 + blue * 114) / 1000;
                total += luma;
                samples++;
                if (luma > 16) nonDark++;
                min = Math.min(min, luma);
                max = Math.max(max, luma);
            }
        }
        int average = samples == 0 ? 0 : (int) (total / samples);
        int percent = samples == 0 ? 0 : nonDark * 100 / samples;
        return "TextureView sample=" + width + "x" + height + " avgLuma=" + average +
                " range=" + min + ".." + max + " nonDark=" + percent + "%";
    }

    public synchronized void stop() {
        // Never send UDP from the UI thread: Android enforces StrictMode and
        // throws NetworkOnMainThreadException. The receive worker owns STOP
        // and closes the socket in its finally block.
        running = false;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            if (t != Thread.currentThread()) {
                try { t.join(350); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        DatagramSocket socket = listening;
        if (socket != null && !socket.isClosed()) {
            // A worker that did not finish within the bounded join still needs
            // best-effort STOP. Send it off the main thread, then close socket.
            WltoysCameraCommandChannel command = cameraCommands;
            if (command != null) {
                Thread fallback = new Thread(() -> {
                    try {
                        if (command.stop())
                            log.i("HEVC", "camera STOP sent from UDP 1234 (shutdown fallback)");
                    } catch (Exception e) {
                        log.e("HEVC", "camera STOP fallback failed " + e.getClass().getSimpleName());
                    } finally {
                        socket.close();
                    }
                }, "CrewCar-CameraStop");
                fallback.start();
                try { fallback.join(150); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            socket.close();
        }
        // The previous worker had a chance to issue STOP before callbacks
        // become stale. Never automatically arm motors on camera reconnect.
        ++sessionId;
        worker = null;
    }
}
