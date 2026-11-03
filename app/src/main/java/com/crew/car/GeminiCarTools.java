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
                "One explicit spoken RC-car command only. Fixed short Android motor leases; " +
                "no trajectory correction, distance sensing, wall detection or collision avoidance. " +
                "forward_small = '前進一點' (0.5s); forward = '往前進' (0.9s); " +
                "backward_small = '後退一點' (0.5s); backward = '倒車' (0.9s). " +
                "front_left/front_right = '往左前方/往右前方' (0.6s steering AND throttle, " +
                "both STOP together, no straight tail); _small is 0.5s. " +
                "Continuous/indefinite forward driving is NOT available without verified obstacle " +
                "feedback. Explain this limit rather than choosing another intent. " +
                "Never infer obstacle clearance, distance, arrival or successful vehicle movement. " +
                "Never chain motion tools. Only human unlock enables short supervised moves.");
        intent.getJSONObject("parameters")
                .put("properties", new JSONObject()
                        .put("intent", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray()
                                        .put("forward_small")
                                        .put("forward")
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
