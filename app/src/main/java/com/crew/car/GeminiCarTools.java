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
                "forward_continuous = explicit '持續向前走' (ONE 2s video-fresh lease only). " +
                "backward_small = '後退一點' (0.5s); backward = '倒車' (0.9s). " +
                "front_left/front_right = '往左前方/往右前方' (0.6s steering AND throttle, " +
                "both STOP together, no straight tail); _small is 0.5s. " +
                "Never auto-renew continuous driving. Video freshness is NOT verified " +
                "obstacle clearance or delivered visual understanding. " +
                "Never infer obstacle clearance, distance, arrival or successful vehicle movement. " +
                "Never chain motion tools. Only human unlock enables short supervised moves.");
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

        JSONObject bearing = action("guide_toward_person",
                "For ONE explicit human request '朝著前面的人走/往那個人方向移動'. " +
                "Independently use recent FPV visual context to choose the person's coarse " +
                "image bearing: left, center, right, or unknown. Do NOT require local " +
                "MediaPipe person-box detection or a tapped box. If multiple people, " +
                "unclear image, stale/unknown person position, or any uncertainty, " +
                "choose unknown and do not move; ask the human. " +
                "Android executes ONE 500ms small forward or forward+steer command ONLY. " +
                "No follow loop, target tracking, safe distance or obstacle detection. " +
                "Never invent confidence or claim actual car motion; don't chain calls.");
        bearing.getJSONObject("parameters")
                .put("properties", new JSONObject()
                        .put("bearing", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray()
                                        .put("left").put("center").put("right")
                                        .put("unknown"))
                                .put("description",
                                        "Person location in current front-facing FPV image, not a distance")))
                .put("required", new JSONArray().put("bearing"));

        return new JSONArray()
                .put(intent)
                .put(bearing)
                .put(action("align_selected_person",
                        "Only for explicit request '看著那個人/對準那個人' after a human manually " +
                        "tapped that person's box in live camera. It starts a maximum of three " +
                        "160ms local low-gain FORWARD+STEER pulses, never forward-only. " +
                        "After every pulse both axes STOP, and another pulse requires a new camera " +
                        "frame AFTER actual local STOP and settling. May creep forward. No depth, " +
                        "collision avoidance, autonomous target selection or auto restart. " +
                        "Human must unlock and supervise; if blocked ask human to select a person."))
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
