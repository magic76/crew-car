package com.crew.car;

import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.ByteString;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * BYOK raw Gemini Live WebSocket: 16 kHz mic, <=1 FPS JPEG, 24 kHz speaker,
 * function calls -> guarded, bounded car motion.
 * Active API key is never logged; persistence is handled by encrypted Android Keystore storage.
 */
public final class GeminiLive {
    public interface Listener {
        void onStatus(String value);
        String onDrive(String direction, int milliseconds);
        String onDriveIntent(String intent);
        String onStartAiDriveSession(String goal);
        String onAdjustAiDriveSession(long sessionId, String steering, String speed, String motion);
        long currentAiDriveSessionId();
        String onGuideTowardPerson(String bearing);
        String onAlignSelectedPerson();
        String onDriveState(String steering, String throttle, int leaseMs);
        String onSteer(String direction, int holdMs);
        void onSpeechState(boolean speaking);
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
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile WebSocket socket;
    private volatile Runnable setupTimeout;
    private volatile String terminalStatus;
    private volatile boolean ready;
    private volatile boolean micRunning;
    private volatile boolean assistantSpeaking;
    private volatile boolean assistantTurnFinished;
    private volatile boolean ignoreAudioUntilTurnBoundary;
    private volatile long assistantSpeechTailMs;
    private volatile long speechGeneration;
    /** Time of the last JPEG accepted into the Live WebSocket send queue. */
    private volatile long lastFrameSent;
    private volatile long videoFramesQueued;
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
    public boolean assistantSpeaking() { return assistantSpeaking; }
    public boolean micListening() { return micRunning && !assistantSpeaking && ready; }
    /** A user-only interrupt. Clears buffered speech and requests the server to stop its turn. */
    public void interruptSpeech() {
        if (!ready || !assistantSpeaking) return;
        speaker.clear();
        assistantTurnFinished = true;
        assistantSpeechTailMs = 0;
        ++speechGeneration;
        ignoreAudioUntilTurnBoundary = true;
        setAssistantSpeaking(false);
        // Client content ends the ongoing server generation. The next user
        // microphone audio remains enabled; do not send a fake user recording.
        sendText("請停止剛剛的語音回答，現在先聽我說，不要繼續上一段回答。");
        log.i("AUDIO", "user tapped interrupt; playback flushed, mic allowed");
    }

    private void setAssistantSpeaking(boolean value) {
        if (assistantSpeaking == value) return;
        assistantSpeaking = value;
        if (listener != null) listener.onSpeechState(value);
        if (value) log.i("AUDIO", "assistant speaking; microphone recording will pause");
        else log.i("AUDIO", "assistant finished; microphone may resume");
    }

    private void resumeMicAfterPlayback(long generation) {
        if (generation != speechGeneration || !assistantSpeaking || !assistantTurnFinished) return;
        long delay = assistantSpeechTailMs - SystemClock.elapsedRealtime() + 180;
        if (delay > 0) {
            main.postDelayed(() -> resumeMicAfterPlayback(generation), Math.min(250, delay));
        } else setAssistantSpeaking(false);
    }
    /** -1 means no frame was accepted by the current Live session. */
    public long liveVideoAgeMs() {
        long last = lastFrameSent;
        return last <= 0 || !ready ? -1 :
                Math.max(0, SystemClock.elapsedRealtime() - last);
    }
    public long liveVideoFramesQueued() { return videoFramesQueued; }

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
        terminalStatus = null;
        this.ready = false;
        this.assistantSpeaking = false;
        this.assistantTurnFinished = false;
        this.ignoreAudioUntilTurnBoundary = false;
        this.assistantSpeechTailMs = 0;
        ++speechGeneration;
        this.lastFrameSent = 0L;
        this.videoFramesQueued = 0L;
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
                if (ws != socket) { ws.close(1000, "stale session"); return; }
                log.i("LIVE", "WS opened; sending setup");
                if (listener != null) listener.onStatus("WebSocket 已握手，等待 Gemini setupComplete");
                try {
                    boolean queued = ws.send(setup().toString());
                    log.i("LIVE", "setup message queued=" + queued);
                    if (!queued) {
                        failSession(ws, "Gemini 連線失敗：設定訊息未能送出", "setup message rejected by WebSocket queue");
                        return;
                    }
                } catch (Exception e) {
                    failSession(ws, "Gemini 連線失敗：無法建立連線設定", "setup build failed class=" + e.getClass().getSimpleName());
                    return;
                }
                Runnable timeout = () -> {
                    if (ws == socket && !ready)
                        failSession(ws, "Gemini 連線失敗：20 秒內未收到 setupComplete", "setup timeout after 20s");
                };
                setupTimeout = timeout;
                main.postDelayed(timeout, 20_000);
            }
            @Override public void onMessage(WebSocket ws, String text) {
                receive(ws, text);
            }
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                if (ws != socket) return;
                log.i("LIVE", "binary server frame bytes=" + bytes.size());
                String body = bytes.utf8();
                if (body.trim().startsWith("{")) receive(ws, body);
                else if (!ready)
                    failSession(ws, "Gemini 連線失敗：收到非預期的二進位回覆",
                            "unexpected binary server frame before setupComplete bytes=" + bytes.size());
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (ws != socket) return;
                ready = false;
                cancelSetupTimeout();
                stopMic();
                speaker.close();
                setAssistantSpeaking(false);
                // Do NOT log Throwable.getMessage: transport errors may contain auth URL.
                log.e("LIVE", "WS failed class=" + t.getClass().getSimpleName() +
                        " http=" + (response == null ? "none" : response.code()));
                String status = terminalStatus;
                if (status == null) {
                    status = "Gemini 連線失敗（" + (response == null ?
                            t.getClass().getSimpleName() : "HTTP " + response.code()) + "）";
                    terminalStatus = status;
                }
                if (listener != null) listener.onStatus(status);
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                if (ws != socket) return;
                if (!ready && terminalStatus == null) {
                    terminalStatus = "Gemini 連線失敗：伺服器在 setup 完成前關閉連線（" + code + "）";
                    if (listener != null) listener.onStatus(terminalStatus);
                    log.e("LIVE", "WS closed before setupComplete code=" + code);
                }
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                if (ws != socket) return;
                ready = false;
                cancelSetupTimeout();
                stopMic();
                speaker.close();
                setAssistantSpeaking(false);
                log.i("LIVE", "WS closed code=" + code);
                String status = terminalStatus;
                if (status == null) status = "Gemini 已中斷 code=" + code;
                if (listener != null) listener.onStatus(status);
            }
        });
    }

    private JSONObject setup() {
        try {
            String instruction = "你是 Crew Car 的繁體中文語音遙控助手。單次短移動才使用 drive_intent。" +
                    "短動作每次最多一次；AI Drive Session 的前進油門改由 Android 本地持續維持，" +
                    "你只更新方向與速度，不需要一停一走，也不得自行重新開新 session。" +
                    "「前進一點」選 forward_small（0.5秒），普通「往前進／前進」啟動一次5秒AI駕駛，" +
                    "5秒內可以用 adjust_ai_drive_session 控制左右轉、回正、倒退或停止。" +
                    "「後退一點」選 backward_small（0.5秒），「倒車」選 backward（0.9秒）。" +
                    "「左前方／右前方」選 front_left/front_right（0.6秒），轉向和油門會一起停止，" +
                    "沒有轉向完畢後繼續直行的尾段；加「一點」才選 _small（0.5秒）。" +
                    "使用者說「持續前進／朝門口開／到桌子附近／開始 AI 駕駛」請呼叫 start_ai_drive_session，" +
                    "把人類指定的門口、桌子等目標放在 goal 參數，作為同一段駕駛任務的方向參考。" +
                    "Android 本地維持前進油門，每次明確駕駛命令最多5秒；在5秒內可調整方向或切換倒車。" +
                    "期滿自動停止；沒有下一個明確人類命令不得自行重新啟動。" +
                    "開始時工具會回 session_id，行駛中以 adjust_ai_drive_session 搭配該 id 修改方向與速度。" +
                    "方向 left/right 每次只修正900毫秒後回正，前進／倒車油門維持；" +
                    "motion 可用 forward/reverse/keep/stop，改向不需重新開始行駛。" +
                    "速度 slow/normal/fast 分別是原廠增益的50/65/80%，並非量測的 km/h。" +
                    "Android 會定期要求你重新觀察 FPV，朝人類指定目標調整方向和速度。" +
                    "畫面偶爾模糊、目標暫時離開視野，不要只因不確定就停止；" +
                    "優先保持 slow 與 keep/center，持續嘗試辨識目標，不得假裝已看到目標。" +
                    "若明顯即將碰撞、使用者說停，或車端連線／影像失效才停止。" +
                    "不得自行重新 start、延長本地上限或毫無根據加速。" +
                    "鏡頭沒有可靠距離或障礙物感測，車輛沒有可驗證的安全距離、避障、車端ACK，" +
                    "影像新鮮不代表已經測出距離。朝門口等目標時要低速調整方向，" +
                    "不要把畫面裡有牆就視為必須停車；只有明顯即將碰撞才主動停止。" +
                    "使用者明確發出駕駛指令後，Android 驗證車型與連線並自動啟用控制，不需要先點擊解鎖。" +
                    " 使用者說停應立即 stop_car；Live 或車 Wi-Fi 中斷由 Android 停止。" +
                    "不需額外授權；工具 queued 只是 Android 已排入控制命令，不代表車端確認，也不需要等待車端ACK。" +
                    "車子沒有ACK通道。不要一直報告『沒有收到回應』。" +
                    "觀察前後兩張FPV影像相對於固定背景的位移，判斷看起來是否有前進或轉彎；" +
                    "如果沒有足夠可比影像，報告『尚無法從畫面確認』，不是網路沒有回應。" +
                    "只依照確實看見的畫面推估方向；未看到目標時持續低速觀察，不要猜測距離或宣稱抵達。" +
                    "使用者說「朝著那個人走／往前面的人方向走」，優先使用 guide_toward_person：" +
                    "依你真正可見的近期車頭 FPV 畫面判斷單一人物在左、中、右。" +
                    "這個一次移動工具不依賴 Android MediaPipe 人框，不用人先點框，但只能移動一次0.5秒；" +
                    "若已在 AI Drive Session 中，改用 adjust_ai_drive_session，不得重新起動一個短移動。" +
                    "若看不到、畫面不明、多個人物、不確定，請回報 bearing=unknown 或問人類，不能憑空猜。" +
                    "不可自行重複呼叫或宣稱跟隨中，下一次動作需要新的使用者明確要求。" +
                    "使用者說「對準已點選人物」，才使用 align_selected_person，需要人類先點框。" +
                    "這不是自動跟隨或避障；最多三次160毫秒小幅前進加轉向，每次都先停車再看新畫面；可能向前位移。" +
                    "不能自行選擇人物或連續重新開始對準，請求若被拒絕則請人類點選框並在開闊地方測試。";
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
            if (!ready && m.names() != null)
                log.i("LIVE", "pre-setup server frame keys=" + m.names());
            if (m.has("error")) {
                JSONObject error = m.optJSONObject("error");
                String code = error == null ? "unknown" :
                        error.optString("code", error.optString("status", "unknown"));
                String safeCode = safeErrorCode(code);
                String message = error == null ? "" : error.optString("message", "");
                String category = classifyApiError(message);
                failSession(ws, "Gemini 連線失敗：" + category + "（" + safeCode + "）",
                        "server error code=" + safeCode + " category=" + category);
                return;
            }
            if (m.has("setupComplete")) {
                cancelSetupTimeout();
                terminalStatus = null;
                ready = true;
                speaker.start(log);
                log.i("LIVE", "setupComplete model=3.8-live");
                if (listener != null) listener.onStatus("Gemini Live 已連線");
            }
            JSONObject content = m.optJSONObject("serverContent");
            if (content != null) {
                if (content.optBoolean("interrupted")) {
                    ignoreAudioUntilTurnBoundary = false;
                    speaker.clear();
                    assistantTurnFinished = true;
                    assistantSpeechTailMs = 0;
                    setAssistantSpeaking(false);
                }
                JSONObject modelTurn = content.optJSONObject("modelTurn");
                if (modelTurn != null) {
                    JSONArray parts = modelTurn.optJSONArray("parts");
                    if (parts != null) for (int i = 0; i < parts.length(); i++) {
                        JSONObject p = parts.optJSONObject(i);
                        if (p == null) continue;
                        JSONObject audio = p.optJSONObject("inlineData");
                        if (audio != null && audio.optString("mimeType").startsWith("audio/")) {
                            if (ignoreAudioUntilTurnBoundary) continue;
                            byte[] chunk = Base64.decode(audio.optString("data"), Base64.DEFAULT);
                            speaker.offer(chunk);
                            assistantTurnFinished = false;
                            long now = SystemClock.elapsedRealtime();
                            // 24 kHz 16-bit mono = 48 bytes/ms. Don't reopen mic
                            // until queued output should have reached the speaker.
                            assistantSpeechTailMs = Math.max(now, assistantSpeechTailMs)
                                    + Math.max(1, chunk.length / 48);
                            setAssistantSpeaking(true);
                        }
                        if (p.has("text") && listener != null) listener.onTranscript(p.optString("text"));
                    }
                }
                if (content.optBoolean("turnComplete")) {
                    ignoreAudioUntilTurnBoundary = false;
                    assistantTurnFinished = true;
                    long generation = ++speechGeneration;
                    main.post(() -> resumeMicAfterPlayback(generation));
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

    private void failSession(WebSocket ws, String status, String diagnostic) {
        if (ws != socket) return;
        terminalStatus = status;
        ready = false;
        cancelSetupTimeout();
        stopMic();
        speaker.close();
        setAssistantSpeaking(false);
        log.e("LIVE", diagnostic);
        if (listener != null) listener.onStatus(status);
        ws.close(1000, "setup failed");
    }

    private void cancelSetupTimeout() {
        Runnable timeout = setupTimeout;
        setupTimeout = null;
        if (timeout != null) main.removeCallbacks(timeout);
    }

    private static String safeErrorCode(String value) {
        String safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_-]", "");
        if (safe.isEmpty()) return "unknown";
        return safe.length() > 32 ? safe.substring(0, 32) : safe;
    }

    private static String classifyApiError(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        if (lower.contains("api key") || lower.contains("api_key") || lower.contains("permission"))
            return "API Key 或權限遭拒";
        if (lower.contains("quota") || lower.contains("exhausted") || lower.contains("rate limit"))
            return "API 額度或速率限制";
        if (lower.contains("model") || lower.contains("not found"))
            return "模型名稱或模型權限錯誤";
        if (lower.contains("invalid") || lower.contains("unsupported"))
            return "Live 設定格式遭拒";
        return "伺服器拒絕 Live 設定";
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
                        if (safetyOverride && ("drive_car".equals(name) ||
                                "set_drive_state".equals(name) || "steer_car".equals(name) ||
                                "drive_intent".equals(name) ||
                                "start_ai_drive_session".equals(name) ||
                                "adjust_ai_drive_session".equals(name) ||
                                "guide_toward_person".equals(name) ||
                                "align_selected_person".equals(name))) {
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
                String intentSummary = "";
                if ("drive_intent".equals(name)) {
                    JSONObject toolArgs = call.optJSONObject("args");
                    String chosen = toolArgs == null ? "" : toolArgs.optString("intent", "");
                    intentSummary = " selectedIntent=" +
                            (DriveIntentPolicy.plan(chosen) == null ? "invalid" : chosen);
                } else if ("adjust_ai_drive_session".equals(name)) {
                    JSONObject toolArgs = call.optJSONObject("args");
                    intentSummary = " driveSessionId=" +
                            (toolArgs == null ? 0 : toolArgs.optLong("session_id", 0)) +
                            " steering=" + (toolArgs == null ? "" : toolArgs.optString("steering", "")) +
                            " speed=" + (toolArgs == null ? "" : toolArgs.optString("speed", ""));
                } else if ("guide_toward_person".equals(name)) {
                    JSONObject toolArgs = call.optJSONObject("args");
                    String bearing = toolArgs == null ? "" : toolArgs.optString("bearing", "");
                    intentSummary = " personBearing=" +
                            (PersonBearingPolicy.intentFor(bearing) == null ? "unknown" : bearing);
                }
                log.i("TOOL", "name=" + name + intentSummary + " status=" + state +
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
            case "start_ai_drive_session": {
                String goal = args == null ? "" : args.optString("goal", "");
                String result = listener.onStartAiDriveSession(goal);
                boolean blocked = result == null || result.startsWith("blocked");
                return toolStatus(blocked ? "blocked" : "queued",
                        blocked ? result : "Android queued motor command for up to 5 seconds. " +
                                "Observe subsequent FPV frames to evaluate motion; no car feedback is expected.")
                        .put("session_id", blocked ? 0 : listener.currentAiDriveSessionId())
                        .put("goal", goal)
                        .put("max_session_ms", AiDriveSessionPolicy.MAX_SESSION_MS)
                        .put("steering_preserves_throttle", true)
                        .put("visual_review_required", true)
                        .put("visual_verification_source", "subsequent_FPV_frames")
                        .put("obstacle_clearance_verified", false);
            }
            case "adjust_ai_drive_session": {
                long sessionId = args == null ? 0 : args.optLong("session_id", 0);
                String steering = args == null ? "" : args.optString("steering", "");
                String speed = args == null ? "" : args.optString("speed", "");
                String motion = args == null ? "keep" : args.optString("motion", "keep");
                if (!AiDriveSessionPolicy.validSteering(steering) ||
                        !AiDriveSessionPolicy.validSpeed(speed) ||
                        !AiDriveSessionPolicy.validMotion(motion))
                    return toolStatus("blocked", "invalid steering, speed or motion; no movement");
                String result = listener.onAdjustAiDriveSession(sessionId, steering, speed, motion);
                boolean blocked = result == null || result.startsWith("blocked");
                boolean stopped = result != null && result.startsWith("stopped");
                return toolStatus(blocked ? "blocked" : stopped ? "requested" : "queued",
                        blocked ? result : stopped ? "Android STOP requested." :
                                "Android updated steering/motion. Compare later FPV frames to assess effect.")
                        .put("session_id", sessionId)
                        .put("motion", motion)
                        .put("session_extended", false)
                        .put("throttle_kept_on_during_turn", !blocked)
                        .put("steering_correction_ms", AiDriveSessionPolicy.STEERING_HOLD_MS)
                        .put("no_measured_speed_or_distance", true)
                        .put("visual_verification_source", "subsequent_FPV_frames");
            }
            case "drive_intent": {
                String intent = args == null ? "" : args.optString("intent", "");
                String violation = CarToolPolicy.validateDriveIntent(intent);
                if (violation != null) return toolStatus("blocked", violation);
                DriveIntentPolicy.Plan plan = DriveIntentPolicy.plan(intent);
                String result = listener.onDriveIntent(intent);
                boolean blocked = result == null || result.startsWith("blocked");
                return toolStatus(blocked ? "blocked" : "queued",
                        result == null ? "no result" : result)
                        .put("intent", intent)
                        .put("session_id", blocked ? 0 :
                                (("forward".equals(intent) || "forward_continuous".equals(intent)) ?
                                listener.currentAiDriveSessionId() : 0))
                        .put("local_lease_ms", plan.leaseMs)
                        .put("automatic_stop", true)
                        .put("extra_authorization_required", false)
                        .put("manually_armed_required", false)
                        .put("distance_measured", false)
                        .put("obstacle_clearance_verified", false)
                        .put("device_acknowledged", false)
                        .put("motion_verified", false);
            }
            case "guide_toward_person": {
                String bearing = args == null ? "" : args.optString("bearing", "");
                String violation = CarToolPolicy.validatePersonBearing(bearing);
                if (violation != null)
                    return toolStatus("blocked", violation)
                            .put("bearings_available", "left,center,right,unknown")
                            .put("obstacle_clearance_verified", false);
                String result = listener.onGuideTowardPerson(bearing);
                boolean blocked = result == null || result.startsWith("blocked");
                return toolStatus(blocked ? "blocked" : "queued",
                        result == null ? "no result" : result)
                        .put("person_bearing", bearing)
                        .put("local_lease_ms", DriveIntentPolicy.NUDGE_MS)
                        .put("one_step_only", true)
                        .put("human_reinstruction_required", true)
                        .put("recent_video_queued_required", true)
                        .put("obstacle_clearance_verified", false)
                        .put("device_acknowledged", false)
                        .put("motion_verified", false);
            }
            case "align_selected_person": {
                String result = listener.onAlignSelectedPerson();
                boolean blocked = result == null || result.startsWith("blocked");
                return toolStatus(blocked ? "blocked" : "started",
                        result == null ? "no result" : result)
                        .put("target_auto_selected", false)
                        .put("manual_target_selection_required", true)
                        .put("max_forward_steer_pulses", VisionAlignPlanner.MAX_PULSES)
                        .put("pulse_ms", VisionAlignPlanner.PULSE_MS)
                        .put("each_pulse_requires_post_stop_camera_frame", true)
                        .put("forward_translation_possible", true)
                        .put("obstacle_clearance_verified", false)
                        .put("device_acknowledged", false);
            }
            case "set_drive_state": {
                String steering = args == null ? "" : args.optString("steering", "");
                String throttle = args == null ? "" : args.optString("throttle", "");
                int lease = args == null ? CarToolPolicy.DEFAULT_HOLD_MS :
                        args.optInt("lease_ms", CarToolPolicy.DEFAULT_HOLD_MS);
                String violation = CarToolPolicy.validateHold(steering, throttle, lease);
                if (violation != null) return toolStatus("blocked", violation);
                String result = listener.onDriveState(steering, throttle, lease);
                boolean blocked = result == null || result.startsWith("blocked");
                return toolStatus(blocked ? "blocked" : "queued",
                        result == null ? "no result" : result)
                        .put("lease_ms", lease)
                        .put("device_acknowledged", false)
                        .put("motion_verified", false);
            }
            case "steer_car": {
                String direction = args == null ? "" : args.optString("direction", "");
                int hold = args == null ? 550 : args.optInt("hold_ms", 550);
                String violation = CarToolPolicy.validateSteer(direction, hold);
                if (violation != null) return toolStatus("blocked", violation);
                String result = listener.onSteer(direction, hold);
                boolean blocked = result == null || result.startsWith("blocked");
                return toolStatus(blocked ? "blocked" : "queued",
                        result == null ? "no result" : result)
                        .put("throttle_preserved", !blocked)
                        .put("vehicle_speed_measured", false)
                        .put("device_acknowledged", false);
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

    /** Lightweight JPEG input, <=1 fps; queued frames are not model acknowledgements. */
    public synchronized void frame(Bitmap original) {
        WebSocket target = socket;
        if (!ready || target == null || original == null) return;
        long now = SystemClock.elapsedRealtime();
        if (lastFrameSent > 0 && now - lastFrameSent < 1000 ||
                target.queueSize() > 1024 * 1024) return;
        try {
            long encodeStarted = SystemClock.elapsedRealtime();
            float scale = Math.min(1f, 448f / Math.max(original.getWidth(), original.getHeight()));
            Bitmap sized = Bitmap.createScaledBitmap(original,
                    Math.max(1, Math.round(original.getWidth() * scale)),
                    Math.max(1, Math.round(original.getHeight() * scale)), true);
            ByteArrayOutputStream stream = new ByteArrayOutputStream(32 * 1024);
            boolean encoded;
            try {
                encoded = sized.compress(Bitmap.CompressFormat.JPEG, 60, stream);
            } finally {
                if (sized != original) sized.recycle();
            }
            if (!encoded) {
                log.e("VIDEO", "JPEG encode failed");
                return;
            }
            long encodedMs = SystemClock.elapsedRealtime() - encodeStarted;
            byte[] bytes = stream.toByteArray();
            JSONObject m = new JSONObject().put("realtimeInput", new JSONObject()
                    .put("video", new JSONObject().put("mimeType", "image/jpeg")
                            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))));
            boolean queued = target.send(m.toString());
            if (queued && target == socket && ready) {
                lastFrameSent = SystemClock.elapsedRealtime();
                videoFramesQueued++;
                if (videoFramesQueued % 5 == 1)
                    log.i("VIDEO", "Live JPEG queued count=" + videoFramesQueued +
                            " src=" + original.getWidth() + "x" + original.getHeight() +
                            " jpegLongSide<=448 quality=60 bytes=" + bytes.length +
                            " encodeMs=" + encodedMs + " websocketQueueBytes=" +
                            target.queueSize() +
                            " (WebSocket queued; NOT model acknowledgement)");
            } else log.e("VIDEO", "WebSocket video queue rejected frame");
        } catch (Exception e) {
            log.e("VIDEO", "video frame error=" + e.getClass().getSimpleName());
        }
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
                boolean recording = true;
                boolean sentStreamEndForSpeech = false;
                while (micRunning && ready) {
                    if (assistantSpeaking) {
                        if (recording) {
                            r.stop();
                            recording = false;
                        }
                        if (!sentStreamEndForSpeech && socket != null) {
                            socket.send("{\"realtimeInput\":{\"audioStreamEnd\":true}}");
                            sentStreamEndForSpeech = true;
                        }
                        Thread.sleep(40);
                        continue;
                    }
                    if (!recording) {
                        r.startRecording();
                        recording = true;
                        sentStreamEndForSpeech = false;
                    }
                    int size = r.read(buf, 0, buf.length);
                    if (size > 0 && !assistantSpeaking && socket != null &&
                            socket.queueSize() < 1024 * 1024) {
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
        cancelSetupTimeout();
        terminalStatus = null;
        synchronized (recentToolResponses) { recentToolResponses.clear(); }
        stopMic();
        ready = false;
        if (socket != null) socket.close(1000, "user close");
        socket = null;
        speaker.close();
        ++speechGeneration;
        ignoreAudioUntilTurnBoundary = false;
        setAssistantSpeaking(false);
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
