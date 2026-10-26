package app.opencodepair;

import org.json.JSONObject;

/** One row from {@code GET /api/session}. */
public final class SessionInfo {
    public String id = "";
    public String title = "";
    public String directory = "";
    public String parentId = "";
    public String projectId = "";
    public String modelId = "";
    public String providerId = "";
    public String variant = "";
    public String outcome = "";
    public long created = 0;
    public long updated = 0;

    public static SessionInfo from(JSONObject o) {
        SessionInfo s = new SessionInfo();
        s.id = o.optString("id", "");
        s.title = o.optString("title", "");
        s.parentId = o.optString("parentID", "");
        s.projectId = o.optString("projectID", "");
        s.outcome = o.optString("outcome", "");

        JSONObject time = o.optJSONObject("time");
        if (time != null) {
            s.created = time.optLong("created", 0);
            s.updated = time.optLong("updated", 0);
        }
        JSONObject loc = o.optJSONObject("location");
        if (loc != null) s.directory = loc.optString("directory", "");
        JSONObject model = o.optJSONObject("model");
        if (model != null) {
            s.modelId = model.optString("id", "");
            s.providerId = model.optString("providerID", "");
            s.variant = model.optString("variant", "");
        }
        return s;
    }

    public String displayTitle() {
        if (title == null || title.trim().isEmpty()) return "New session";
        return title.trim();
    }

    public boolean isSubagent() {
        return parentId != null && !parentId.isEmpty();
    }
}
