package app.opencodepair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One model advertised by the server. */
public final class ServerModel {
    public String id = "";
    public String name = "";
    public String providerId = "";
    public boolean enabled = true;
    public final List<String> variants = new ArrayList<>();

    public static ServerModel from(JSONObject o) {
        ServerModel m = new ServerModel();
        m.id = o.optString("id", "");
        m.name = o.optString("name", m.id);
        m.providerId = o.optString("providerID", "");
        m.enabled = o.optBoolean("enabled", true);
        JSONArray v = o.optJSONArray("variants");
        if (v != null) {
            for (int i = 0; i < v.length(); i++) {
                JSONObject vo = v.optJSONObject(i);
                if (vo != null) {
                    String vid = vo.optString("id", "");
                    if (!vid.isEmpty()) m.variants.add(vid);
                }
            }
        }
        return m;
    }

    public String label() {
        return name == null || name.trim().isEmpty() ? id : name;
    }

    public String detail() {
        return providerId + "/" + id;
    }
}
