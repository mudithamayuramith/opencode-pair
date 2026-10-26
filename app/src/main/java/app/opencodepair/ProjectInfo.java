package app.opencodepair;

import org.json.JSONObject;

/** A project (working directory) known to the server. */
public final class ProjectInfo {
    public String id = "";
    public String canonical = "";
    public long updated = 0;

    public static ProjectInfo from(JSONObject o) {
        ProjectInfo p = new ProjectInfo();
        p.id = o.optString("id", "");
        p.canonical = o.optString("canonical", "");
        JSONObject time = o.optJSONObject("time");
        if (time != null) p.updated = time.optLong("updated", 0);
        return p;
    }
}
