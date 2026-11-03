package com.crew.car;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One advertised movement tool avoids confusing 500ms tap, target-approach
 * approval and 15s cruise. STOP/lock always remain separately callable.
 * Legacy tool handlers are retained locally for protocol compatibility but
 * are intentionally NOT declared to new Gemini Live sessions.
 */
public final class GeminiCarTools {
    private GeminiCarTools() {}

    private static JSONObject action(String name, String description) throws JSONException {
        return new JSONObject()
                .put("name", name).put("description", description)
                .put("behavior", "NON_BLOCKING")
                .put("parameters", new JSONObject()
                        .put("type", "OBJECT")
                        .put("properties", new JSONObject()));
    }

    public static JSONArray declarations() throws JSONException {
        JSONObject intent = action("drive_intent",
                "Use for ONE explicit user speech command to move the RC car. " +
                "No extra 8/20-second UI authorization: human only needs to unlock the car. " +
                "Choose the exact requested intent, not milliseconds. " +
                "forward_small = '前進一點/稍微往前' (0.5s); " +
                "forward = '往前進/向前開/前進' (2.5s, NOT a tiny tap); " +
                "forward_continuous = '一直前進/持續往前/往前開不要停' (bounded 15s local lease). " +
                "backward_small = '後退一點' (0.5s), backward = '倒車/往後退' (2s); " +
                "front_left/front_right = '往左前方/往右前方' (2s forward plus steering " +
                "automatically centered within 650ms). Add _small only if user said '一點'. " +
                "Never select forward_small for plain '前進'. " +
                "Unknown target location = ask user for direction. Never infer physical distance, " +
                "obstacle clearance, arrival or actual car movement. Never chain calls automatically.");
        intent.getJSONObject("parameters")
                .put("properties", new JSONObject()
                        .put("intent", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray()
                                        .put("forward_small")
                                        .put("forward")
                                        .put("forward_continuous")
                                        .put("backward_small")
                                        .put("backward")
                                        .put("front_left_small")
                                        .put("front_right_small")
                                        .put("front_left")
                                        .put("front_right"))
                                .put("description",
                                        "Required semantic user intent; local Android determines duration")))
                .put("required", new JSONArray().put("intent"));

        return new JSONArray()
                .put(intent)
                .put(action("stop_car",
                        "Immediately request neutral STOP even if locked; do not claim car ACK."))
                .put(action("lock_car",
                        "Disarm motor and send STOP; only physical app UI can unlock."))
                .put(action("get_car_status",
                        "Read Android motor ownership/status, not physical car position or telemetry."))
                .put(action("get_camera_status",
                        "Read FPV freshness metadata, not scene understanding or distances."))
                .put(action("get_network_status",
                        "Read Wi-Fi and Gemini Live route status, not car packet acknowledgements."));
    }
}
