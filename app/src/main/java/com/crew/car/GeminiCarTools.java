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
                "Set a bounded motor state for up to 5 seconds. This is the accelerator being HELD, NOT one button tap. " +
                "To continue straight while changing steering, send throttle=keep and steering=left/right/center. " +
                "To keep steering while adjusting gas, send steering=keep. A previous AI state must still be active for keep. " +
                "No endless cruise: renew explicitly before expiration, otherwise the watchdog stops the car. " +
                "Only one human-authorized bounded motion at a time; never navigate unattended.");
        hold.getJSONObject("parameters")
                .put("properties", new JSONObject()
                        .put("steering", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray().put("left").put("right").put("center").put("keep")))
                        .put("throttle", new JSONObject().put("type", "STRING")
                                .put("enum", new JSONArray().put("forward").put("reverse").put("neutral").put("keep")))
                        .put("lease_ms", new JSONObject().put("type", "INTEGER")
                                .put("description", "Duration from 500 to 5000 ms; ends automatically with STOP unless renewed")));
        hold.getJSONObject("parameters").put("required",
                new JSONArray().put("steering").put("throttle").put("lease_ms"));

        return new JSONArray()
                .put(drive).put(hold)
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
