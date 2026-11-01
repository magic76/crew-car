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

        return new JSONArray()
                .put(drive)
                .put(action("stop_car",
                        "Send immediate neutral/STOP command. Allowed even when locked. Does not guarantee the car received it if Wi-Fi is offline."))
                .put(action("lock_car",
                        "Disarm motor control and issue STOP. Lock can only be released by the human through the app UI."))
                .put(action("get_car_status",
                        "Read actual application safety/drive status, last attempted TX result and camera age; no physical motion or battery telemetry is available."))
                .put(action("get_camera_status",
                        "Check whether camera frames are being received recently, the last frame resolution and source adapter. Do not claim this tool sees the scene or detects obstacles."))
                .put(action("get_network_status",
                        "Read connectivity of car Wi-Fi and separate internet route used for Gemini Live. No network reset or switching is performed."));
    }
}
