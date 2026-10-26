package app.opencodepair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A pending permission prompt from the agent. */
public final class PermissionRequest {
    public String id = "";
    public String action = "";
    public String message = "";
    public final List<String> resources = new ArrayList<>();

    public static PermissionRequest from(JSONObject o) {
        PermissionRequest p = new PermissionRequest();
        p.id = o.optString("id", "");
        p.action = o.optString("action", "");
        p.message = o.optString("message", "");
        JSONArray a = o.optJSONArray("resources");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "");
                if (!s.isEmpty()) p.resources.add(s);
            }
        }
        return p;
    }
}
