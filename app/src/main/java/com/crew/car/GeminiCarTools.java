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
                "forward_small = '前進一點' (0.5s). '往前進' starts one bounded 30s Android " +
                "continuous drive session. Android maintains throttle while Gemini makes " +
                "FPV-based steering/reverse/STOP updates in the SAME session. " +
                "backward_small = '後退一點' (0.5s); backward = '倒車' (0.9s). " +
                "front_left/front_right = '往左前方/往右前方' (0.6s steering AND throttle, " +
                "both STOP together, no straight tail); _small is 0.5s. " +
                "Never auto-renew continuous driving. Video freshness is NOT verified " +
                "obstacle clearance or delivered visual understanding. " +
                "Never infer obstacle clearance, distance, arrival or successful vehicle movement. " +
                "No motor ACK: do not wait for replies; use visible change between recent FPV frames " +
                "as uncertain visual evidence. Never auto-chain new drive sessions.");
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

        JSONObject adjust = action("adjust_ai_drive_session",
                "Use DURING an active Android-owned AI Drive Session ONLY, with the EXACT " +
                "session_id from start_ai_drive_session response or current status. " +
                "Gemini reviews recent FPV image to change steering and/or relative throttle gain " +
                "independently. steering = left/right/center/keep; left/right is a SHORT 900ms " +
                "correction that auto-centers, while FORWARD THROTTLE KEEPS GOING. " +
                "speed = slow/normal/fast/keep (bounded 70/85/100% OEM gain, NOT km/h). " +
                "motion = keep/forward/reverse/stop. Reverse, steer or STOP at any point " +
                "during the same session, without waiting for a new human speech turn. " +
                "On EVERY periodic FPV review, call adjust_ai_drive_session with the exact " +
                "session_id. If course is unchanged, reply steering=keep, speed=keep, " +
                "motion=keep so Android knows feedback is still live. " +
                "Without an update for 8s Android STOPs; absolute expiry is 30s. " +
                "Never assume a queued tool has vehicle ACK. " +
                "To decide turning, use recent FPV image coordinates: locate intended opening " +
                "(doorway, corridor) and estimate its CENTER relative to image center. " +
                "If opening center lies in left third choose steering=left, in right third " +
                "choose right, otherwise center. An open doorway is the gap between doorposts; " +
                "the nearest wall edge is NOT the steering target. After each correction, compare " +
                "the opening center or stationary background across two different FPV frames. " +
                "When path looks navigable prefer normal speed (85% OEM gain); use slow only for tight turns or actual uncertainty, not as the permanent default. " +
                "If the goal briefly leaves the image or framing is poor, adjust steering and use slow only if needed; use keep/center " +
                "to continue observing; do not stop simply because certainty is imperfect. " +
                "When a collision is clearly imminent, human STOP is requested, or link is lost, stop_car. " +
                "No measured depth or ACK; never self-restart an expired session.");
        adjust.getJSONObject("parameters")
                .put("properties", new JSONObject()
                        .put("session_id", new JSONObject().put("type", "INTEGER")
                                .put("description", "Exact integer session id from start response"))
                        .put("steering", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray().put("left").put("right")
                                        .put("center").put("keep")))
                        .put("speed", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray().put("slow").put("normal")
                                        .put("fast").put("keep")))
                        .put("motion", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray().put("keep").put("forward")
                                        .put("reverse").put("stop"))))
                .put("required", new JSONArray().put("session_id")
                        .put("steering").put("speed").put("motion"));

        JSONObject goalStart = action("start_ai_drive_session",
                "Start a locally controlled driving session on explicit human voice request: " +
                "'朝門口開/開到桌子附近/持續前進'. Capture the user destination in goal. " +
                "One explicit human activation lasts up to 30 seconds, with continuously repeated " +
                "Android throttle packets. Steering, throttle direction and gain can be " +
                "changed at any time DURING this SAME window using adjust_ai_drive_session " +
                "and the exact session_id. Do not demand a separate " +
                "UI unlock, but Android must verify WL car Wi-Fi and foreground Live. " +
                "Temporary image uncertainty is NOT itself grounds for STOP: prefer normal " +
                "gain (85%) when the route looks navigable, and use slow (70%) only for tight " +
                "corners or when genuinely needed; adjust keep/center and keep observing. " +
                "STOP for explicit command, " +
                "imminent collision, camera/Live/Wi-Fi failure, manual takeover, " +
                "8-second missing model updates, or 30-second absolute expiry. " +
                "This is not verified navigation, distance sensing or obstacle avoidance. " +
                "Never restart an expired session automatically. This is not autonomous navigation.");
        goalStart.getJSONObject("parameters")
                .put("properties", new JSONObject()
                        .put("goal", new JSONObject().put("type", "STRING")
                                .put("description", "Human-requested destination, e.g. 門口, 桌子; " +
                                        "use 往前探索 if the user named no destination")));

        return new JSONArray()
                .put(intent)
                .put(goalStart)
                .put(adjust)
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
                .put(action("get_car_status",
                        "Read Android motor ownership/status, not physical car position or telemetry."))
                .put(action("get_camera_status",
                        "Read FPV freshness metadata, not scene understanding or distances."))
                .put(action("get_network_status",
                        "Read Wi-Fi and Gemini Live route status, not car packet acknowledgements."));
    }
}
