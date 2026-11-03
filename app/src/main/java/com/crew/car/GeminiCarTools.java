package com.crew.car;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Only declare executable, observable functions; no unverifiable odometry or obstacle sensor. */
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
        JSONObject drive = action("drive_car",
                "Drive one short pulse ONLY on an explicit user request. Physical movement is unverified; never infer odometry, distances or obstacle clearance. Do not chain multiple pulses autonomously.");
        drive.getJSONObject("parameters")
                .put("properties", new JSONObject()
                        .put("direction", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray().put("forward").put("backward").put("left").put("right")))
                        .put("duration_ms", new JSONObject().put("type", "INTEGER")
                                .put("description", "Required short pulse between 100 and 650 milliseconds inclusive")))
                .put("required", new JSONArray().put("direction").put("duration_ms"));

        JSONObject hold = action("set_drive_state",
                "START/UPDATE sustained local throttle and steering, not a short tap. One call holds the accelerator " +
                "for 15 seconds by default, up to 30 seconds if requested; the phone sends motor packets automatically. " +
                "When changing steering, use throttle=keep so forward/reverse CONTINUES without braking. " +
                "Use steer_car for short turns while keeping throttle unchanged. " +
                "A previous active state is required for keep. No indefinite autopilot: the lease expires and STOPs; " +
                "use only when the human explicitly requests motion and supervises the car.");
        hold.getJSONObject("parameters")
                .put("properties", new JSONObject()
                        .put("steering", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray().put("left").put("right").put("center").put("keep")))
                        .put("throttle", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray().put("forward").put("reverse").put("neutral").put("keep")))
                        .put("lease_ms", new JSONObject().put("type", "INTEGER")
                                .put("description", "Optional duration from 500 to 30000 ms; default 15000. App keeps sending motor frames until expiry; never infinite.")));
        hold.getJSONObject("parameters").put("required",
                new JSONArray().put("steering").put("throttle"));

        JSONObject steering = action("steer_car",
                "During an ACTIVE sustained AI throttle hold, briefly steer left/right or center " +
                "without releasing gas or restarting the main cruise timer. " +
                "Steering auto-centers after hold_ms (default 550ms); throttle remains held.");
        steering.getJSONObject("parameters").put("properties", new JSONObject()
                .put("direction", new JSONObject().put("type", "STRING")
                        .put("enum", new JSONArray().put("left").put("right").put("center")))
                .put("hold_ms", new JSONObject().put("type", "INTEGER")
                        .put("description", "Optional 100–2000ms steering correction, default 550ms; gas unaffected.")));
        steering.getJSONObject("parameters").put("required", new JSONArray().put("direction"));

        JSONObject approach = action("approach_target",
                "Short, human-authorized front-bearing nudge: ALWAYS forward with slight left, " +
                "center or right steering in the SAME packet; NEVER spin in place, reverse, " +
                "hold throttle or claim to have reached a target. Use ONLY after the human " +
                "taps 導向 to authorize up to 3 nudges within 20 seconds, AND explicitly " +
                "requests a short movement with a clear front-left/ahead/front-right bearing. " +
                "If the camera is unclear and the human has not given the bearing, ASK; " +
                "never invent a target direction, distance, free path or obstacle clearance. " +
                "One invocation only per explicit movement request; do not chain autonomously.");
        approach.getJSONObject("parameters").put("properties", new JSONObject()
                .put("bearing", new JSONObject().put("type", "STRING")
                        .put("description", "Coarse front-bearing supplied by human or clearly observed in a recent image")
                        .put("enum", new JSONArray().put("front_left").put("ahead").put("front_right")))
                .put("duration_ms", new JSONObject().put("type", "INTEGER")
                        .put("description", "Required nudge 150..600 ms, never a continuous cruise")));
        approach.getJSONObject("parameters").put("required",
                new JSONArray().put("bearing").put("duration_ms"));

        return new JSONArray()
                .put(drive).put(hold).put(steering).put(approach)
                .put(action("stop_car",
                        "Send immediate neutral/STOP command. Allowed even when locked. Does not guarantee the car received it if Wi-Fi is offline."))
                .put(action("lock_car",
                        "Disarm motor control and issue STOP. Lock can only be released by the human through the app UI."))
                .put(action("get_car_status",
                        "Read ONLY Android application command status, manual safety lock and camera age. This is not physical car telemetry: the only sensor is a camera. No speed, distance, battery, position or movement feedback."))
                .put(action("get_camera_status",
                        "Read camera frame age, source and resolution AND whether a recent image was queued to Gemini Live. The only hardware sensor is the camera. This function reports transport health, not image contents, depth or obstacle clearance."))
                .put(action("get_network_status",
                        "Read connectivity of car Wi-Fi and separate internet route used for Gemini Live. No network reset or switching is performed."));
    }
}
