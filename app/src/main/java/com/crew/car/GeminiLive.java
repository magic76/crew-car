package com.crew.car;

import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Network;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * BYOK raw Gemini Live WebSocket: 16 kHz mic, <=1 FPS JPEG, 24 kHz speaker,
 * function calls -> guarded, bounded car motion.
 * API key only exists in memory; never printed/persisted.
 */
public final class GeminiLive {
    public interface Listener {
        void onStatus(String value);
        String onDrive(String direction, int milliseconds);
        void onStop();
        void onLock();
        JSONObject onCarStatus();
        JSONObject onCameraStatus();
        JSONObject onNetworkStatus();
        void onTranscript(String text);
    }
    private static final String MODEL = "models/gemini-3.8-live";
    private static final String ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private final CarLog log;
    private volatile WebSocket socket;
    private volatile boolean ready;
    private volatile boolean micRunning;
    private volatile long lastFrameSent;
    private volatile AudioRecord recorder;
    private Thread micThread;
    private OkHttpClient client;
    private final Speaker speaker = new Speaker();
    private Listener listener;
    private final Map<String, JSONObject> recentToolResponses = new LinkedHashMap<String, JSONObject>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, JSONObject> eldest) {
            return size() > 64;
        }
    };

    public GeminiLive(CarLog log) { this.log = log; }
    public boolean ready() { return ready; }
    public boolean micRunning() { return micRunning; }

    public synchronized void connect(String apiKey, Network internet, Listener callback) {
        disconnect();
        if (apiKey == null || apiKey.isBlank()) {
            callback.onStatus("Gemini API Key 不可空白");
            return;
        }
        if (internet == null) {
            callback.onStatus("找不到可用的網路。小車 Wi-Fi 通常無網際網路，請開啟行動數據");
            log.e("LIVE", "no independent validated internet network");
            return;
        }
        this.listener = callback;
        this.ready = false;
        this.lastFrameSent = 0L;
        client = new OkHttpClient.Builder()
                .socketFactory(internet.getSocketFactory())
                .dns(host -> Arrays.asList(internet.getAllByName(host)))
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        // Google's documented raw-WebSocket endpoint authenticates via the query parameter.
        // Never log this full URL or the Request object.
        String url = ENDPOINT + "?key=" + android.net.Uri.encode(apiKey.trim());
        log.i("LIVE", "connecting model=gemini-3.8-live internetNetwork=" + internet);
        callback.onStatus("Gemini WebSocket 連線中");
        socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                log.i("LIVE", "WS opened; sending setup");
                ws.send(setup().toString());
            }
            @Override public void onMessage(WebSocket ws, String text) {
                receive(ws, text);
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (ws != socket) return;
                ready = false;
                stopMic();
                speaker.close();
                // Do NOT log Throwable.getMessage: transport errors may contain auth URL.
                log.e("LIVE", "WS failed class=" + t.getClass().getSimpleName() +
                        " http=" + (response == null ? "none" : response.code()));
                if (listener != null) listener.onStatus("Gemini 連線失敗（查看 LIVE Log；確認 API 可使用此模型）");
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                if (ws != socket) return;
                ready = false;
                stopMic();
                speaker.close();
                log.i("LIVE", "WS closed code=" + code);
                if (listener != null) listener.onStatus("Gemini 已中斷 code=" + code);
            }
        });
    }

    private JSONObject setup() {
        try {
            String instruction = "你是 Crew Car 的繁體中文語音助手。你可以看到低頻率車頭影像，也可聽使用者說話。" +
                    "可用工具：get_car_status 查安全鎖與最後傳送狀態、get_camera_status 查鏡頭新鮮度、get_network_status 查網路、stop_car 立即送 STOP、lock_car 鎖車、drive_car 短促移動。" +
                    "先用只讀工具回答車況，不能編造電池量、位置、距離、實際移動、障礙物偵測或感測器資料。" +
                    "只有使用者明確要求移動，才使用 drive_car，duration_ms 必須在 100–650 毫秒。不得自行連續移動、追人、追車或規劃自主導航。" +
                    "若鏡頭未更新、看不清路況、看見人或障礙物、或指令不明確，請停止並詢問。" +
                    "工具回傳 queued 只代表移動已排入本機佇列；TX 成功也不代表小車回覆或真正移動，絕對不可宣稱完成移動。" +
                    "stop_car 和 lock_car 可隨時呼叫；只有人類可以在手機介面重新解鎖。" +
                    "同一意圖連續失敗至多嘗試兩次，之後停止並告知使用者。";
            return new JSONObject().put("setup", new JSONObject()
                    .put("model", MODEL)
                    .put("generationConfig", new JSONObject()
                            .put("responseModalities", new JSONArray().put("AUDIO")))
                    .put("inputAudioTranscription", new JSONObject())
                    .put("outputAudioTranscription", new JSONObject())
                    .put("systemInstruction", new JSONObject()
                            .put("parts", new JSONArray().put(new JSONObject().put("text", instruction))))
                    .put("tools", new JSONArray().put(
                            new JSONObject().put("functionDeclarations", GeminiCarTools.declarations()))));
        } catch (Exception e) {
            throw new IllegalStateException("build Live setup", e);
        }
    }

    private void receive(WebSocket ws, String text) {
        if (ws != socket) return; // ignore late packets from an old Gemini session
        try {
            JSONObject m = new JSONObject(text);
            if (m.has("setupComplete")) {
                ready = true;
                speaker.start(log);
                log.i("LIVE", "setupComplete model=3.8-live");
                if (listener != null) listener.onStatus("Gemini Live 已連線");
            }
            JSONObject content = m.optJSONObject("serverContent");
            if (content != null) {
                if (content.optBoolean("interrupted")) speaker.clear();
                JSONObject modelTurn = content.optJSONObject("modelTurn");
                if (modelTurn != null) {
                    JSONArray parts = modelTurn.optJSONArray("parts");
                    if (parts != null) for (int i = 0; i < parts.length(); i++) {
                        JSONObject p = parts.optJSONObject(i);
                        if (p == null) continue;
                        JSONObject audio = p.optJSONObject("inlineData");
                        if (audio != null && audio.optString("mimeType").startsWith("audio/")) {
                            speaker.offer(Base64.decode(audio.optString("data"), Base64.DEFAULT));
                        }
                        if (p.has("text") && listener != null) listener.onTranscript(p.optString("text"));
                    }
                }
                forwardTranscript(content.optJSONObject("inputTranscription"), "你");
                forwardTranscript(content.optJSONObject("outputTranscription"), "Gemini");
            }
            forwardTranscript(m.optJSONObject("inputTranscription"), "你");
            forwardTranscript(m.optJSONObject("outputTranscription"), "Gemini");
            JSONObject tools = m.optJSONObject("toolCall");
            if (tools != null) handleTools(ws, tools.optJSONArray("functionCalls"));
            if (m.has("toolCallCancellation")) {
                log.i("LIVE", "toolCallCancellation -> emergency stop");
                if (listener != null) listener.onStop();
            }
            if (m.has("goAway")) log.i("LIVE", "server goAway; session ending");
        } catch (Exception e) {
            log.e("LIVE", "message decode error=" + e.getClass().getSimpleName());
        }
    }

    private void forwardTranscript(JSONObject obj, String prefix) {
        if (obj != null && listener != null && obj.has("text")) {
            listener.onTranscript(prefix + "：" + obj.optString("text"));
        }
    }

    /** Tool responses include the server-issued id; duplicate IDs never execute motion twice. */
    private void handleTools(WebSocket ws, JSONArray calls) {
        if (calls == null || ws != socket) return;
        JSONArray responses = new JSONArray();
        boolean safetyOverride = false;
        for (int i = 0; i < calls.length(); i++) {
            JSONObject item = calls.optJSONObject(i);
            if (item != null && ("stop_car".equals(item.optString("name")) ||
                    "lock_car".equals(item.optString("name")))) {
                safetyOverride = true;
                break;
            }
        }
        for (int i = 0; i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            if (call == null) continue;
            String name = call.optString("name", "");
            String id = call.optString("id", "");
            long started = SystemClock.elapsedRealtime();
            JSONObject response = null;
            if (!id.isEmpty()) {
                synchronized (recentToolResponses) {
                    response = recentToolResponses.get(id);
                }
            }
            boolean replay = response != null;
            try {
                if (response == null) {
                    JSONObject result;
                    try {
                        if (safetyOverride && "drive_car".equals(name)) {
                            result = toolStatus("blocked", "STOP/lock in same tool batch takes priority");
                        } else {
                            result = executeTool(name, call.optJSONObject("args"));
                        }
                    } catch (Exception e) {
                        result = toolStatus("blocked", "tool_exception_" + e.getClass().getSimpleName());
                        log.e("TOOL", "execution failed tool=" + name + " class=" + e.getClass().getSimpleName());
                    }
                    response = new JSONObject().put("name", name).put("response", result);
                    if (!id.isEmpty()) {
                        response.put("id", id);
                        synchronized (recentToolResponses) { recentToolResponses.put(id, response); }
                    }
                }
                responses.put(response);
                JSONObject result = response.optJSONObject("response");
                String state = result == null ? "invalid" : result.optString("status", "unknown");
                log.i("TOOL", "name=" + name + " status=" + state +
                        " replay=" + replay + " durationMs=" + (SystemClock.elapsedRealtime() - started) +
                        " idSuffix=" + (id.length() > 8 ? id.substring(id.length() - 8) : id));
            } catch (Exception e) {
                log.e("TOOL", "cannot serialize response name=" + name +
                        " class=" + e.getClass().getSimpleName());
            }
        }
        if (responses.length() > 0 && ws == socket) {
            try {
                boolean sent = ws.send(new JSONObject().put("toolResponse",
                        new JSONObject().put("functionResponses", responses)).toString());
                log.i("TOOL", "toolResponse count=" + responses.length() + " sent=" + sent);
            } catch (Exception e) {
                log.e("TOOL", "toolResponse failed class=" + e.getClass().getSimpleName());
            }
        }
    }

    private static JSONObject toolStatus(String status, String message) throws org.json.JSONException {
        return new JSONObject().put("status", status)
                .put("message", message).put("retryable", false);
    }

    private JSONObject executeTool(String name, JSONObject args) throws org.json.JSONException {
        if (listener == null) return toolStatus("blocked", "no active car session");
        if (!CarToolPolicy.isSupported(name))
            return toolStatus("blocked", "unknown tool; not supported");
        switch (name) {
            case "drive_car": {
                String direction = args == null ? "" : args.optString("direction", "");
                int duration = args == null ? -1 : args.optInt("duration_ms", -1);
                String violation = CarToolPolicy.validateMove(direction, duration);
                if (violation != null) return toolStatus("blocked", violation);
                String result = listener.onDrive(direction, duration);
                boolean blocked = result == null || result.startsWith("blocked");
                return toolStatus(blocked ? "blocked" : "queued", result == null ? "no result" : result)
                        .put("device_acknowledged", false)
                        .put("motion_verified", false);
            }
            case "stop_car":
                listener.onStop();
                return toolStatus("requested", "STOP packet queued. No hardware acknowledgement.")
                        .put("device_acknowledged", false);
            case "lock_car":
                listener.onLock();
                return toolStatus("requested", "motor locked and STOP queued; only human can unlock");
            case "get_car_status":
                return toolStatus("ok", "application state, NOT hardware telemetry")
                        .put("data", listener.onCarStatus());
            case "get_camera_status":
                return toolStatus("ok", "camera freshness metadata, NOT scene understanding")
                        .put("data", listener.onCameraStatus());
            case "get_network_status":
                return toolStatus("ok", "Android network route availability")
                        .put("data", listener.onNetworkStatus());
            default:
                return toolStatus("blocked", "unknown tool");
        }
    }

    public boolean sendText(String prompt) {
        if (!ready || socket == null) return false;
        try {
            JSONObject m = new JSONObject().put("clientContent", new JSONObject()
                    .put("turns", new JSONArray().put(new JSONObject()
                            .put("role", "user")
                            .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))))
                    .put("turnComplete", true));
            return socket.send(m.toString());
        } catch (Exception e) { return false; }
    }

    public void frame(Bitmap original) {
        if (!ready || socket == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastFrameSent < 1000 || socket.queueSize() > 1024 * 1024) return;
        lastFrameSent = now;
        try {
            float scale = Math.min(1f, 512f / Math.max(original.getWidth(), original.getHeight()));
            Bitmap sized = Bitmap.createScaledBitmap(original,
                    Math.max(1, Math.round(original.getWidth() * scale)),
                    Math.max(1, Math.round(original.getHeight() * scale)), true);
            ByteArrayOutputStream stream = new ByteArrayOutputStream(60 * 1024);
            sized.compress(Bitmap.CompressFormat.JPEG, 68, stream);
            if (sized != original) sized.recycle();
            JSONObject m = new JSONObject().put("realtimeInput", new JSONObject()
                    .put("video", new JSONObject().put("mimeType", "image/jpeg")
                            .put("data", Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP))));
            socket.send(m.toString());
        } catch (Exception e) { log.e("LIVE", "video frame error=" + e.getClass().getSimpleName()); }
    }

    public synchronized void startMic() {
        if (!ready || micRunning) return;
        micRunning = true;
        micThread = new Thread(() -> {
            AudioRecord r = null;
            try {
                int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                r = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(6400, min));
                recorder = r;
                if (r.getState() != AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("AudioRecord init");
                r.startRecording();
                log.i("MIC", "16k PCM started");
                byte[] buf = new byte[3200]; // 100ms
                while (micRunning && ready) {
                    int size = r.read(buf, 0, buf.length);
                    if (size > 0 && socket != null && socket.queueSize() < 1024 * 1024) {
                        JSONObject m = new JSONObject().put("realtimeInput", new JSONObject()
                                .put("audio", new JSONObject()
                                        .put("mimeType", "audio/pcm;rate=16000")
                                        .put("data", Base64.encodeToString(buf, 0, size, Base64.NO_WRAP))));
                        socket.send(m.toString());
                    }
                }
            } catch (Exception e) {
                log.e("MIC", "capture error=" + e.getClass().getSimpleName());
            } finally {
                micRunning = false;
                if (r != null) {
                    try { r.stop(); } catch (Exception ignored) {}
                    r.release();
                }
                recorder = null;
                log.i("MIC", "capture stopped");
            }
        }, "CrewCar-Mic");
        micThread.start();
    }

    public void stopMic() {
        micRunning = false;
        AudioRecord r = recorder;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
        }
        if (ready && socket != null) socket.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}");
    }

    public synchronized void disconnect() {
        synchronized (recentToolResponses) { recentToolResponses.clear(); }
        stopMic();
        ready = false;
        if (socket != null) socket.close(1000, "user close");
        socket = null;
        speaker.close();
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
        client = null;
    }

    private static final class Speaker {
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(32);
        private AudioTrack audio;
        private Thread thread;
        private volatile boolean playing;

        synchronized void start(CarLog log) {
            close();
            int min = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) { log.e("AUDIO", "invalid playback buffer"); return; }
            audio = new AudioTrack(AudioManager.STREAM_MUSIC, 24000,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(8192, min), AudioTrack.MODE_STREAM);
            if (audio.getState() != AudioTrack.STATE_INITIALIZED) {
                log.e("AUDIO", "AudioTrack init failed");
                audio.release();
                audio = null;
                return;
            }
            audio.play();
            playing = true;
            thread = new Thread(() -> {
                while (playing) {
                    try {
                        byte[] chunk = queue.poll(300, TimeUnit.MILLISECONDS);
                        if (chunk != null && audio != null) audio.write(chunk, 0, chunk.length);
                    } catch (InterruptedException e) { break; }
                    catch (Exception e) { break; }
                }
            }, "CrewCar-Speaker");
            thread.start();
            log.i("AUDIO", "24k PCM speaker started");
        }
        void offer(byte[] chunk) {
            if (!queue.offer(chunk)) {
                queue.poll();
                queue.offer(chunk);
            }
        }
        synchronized void clear() {
            queue.clear();
            if (audio != null) {
                try { audio.pause(); audio.flush(); audio.play(); } catch (Exception ignored) {}
            }
        }
        synchronized void close() {
            playing = false;
            queue.clear();
            if (thread != null) thread.interrupt();
            if (audio != null) {
                try { audio.pause(); audio.flush(); audio.release(); } catch (Exception ignored) {}
            }
            thread = null;
            audio = null;
        }
    }
}
