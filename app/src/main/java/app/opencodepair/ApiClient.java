package app.opencodepair;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The OpenCode HTTP API, reduced to the calls this app makes. Deliberately free of Android
 * imports so it can be exercised from a plain JVM.
 *
 * <p>Authentication is HTTP Basic with the username {@code opencode} and the token redeemed from
 * an {@code opencode pair} link as the password.
 */
public final class ApiClient {
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 30000;

    public static final class ApiException extends IOException {
        public final int status;
        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final class MessagePage {
        public final List<ChatMessage> data;
        public final String nextCursor;
        public final String prevCursor;

        MessagePage(List<ChatMessage> data, String nextCursor, String prevCursor) {
            this.data = data;
            this.nextCursor = nextCursor;
            this.prevCursor = prevCursor;
        }
    }

    private final String baseUrl;
    private final String token;

    public ApiClient(String baseUrl, String token) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.baseUrl = b;
        this.token = token == null ? "" : token;
    }

    public String baseUrl() { return baseUrl; }

    // ---------- Pairing ----------

    /**
     * Redeems a one-time {@code /auth/connect/<code>} link for an API token. The link is
     * single-use, so callers must try candidates one at a time.
     */
    public static String redeem(String pairingUrl) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(pairingUrl).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "OpenCodePair/1.0");
            int code = c.getResponseCode();
            String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 200) {
                JSONObject o;
                try {
                    o = new JSONObject(body);
                } catch (JSONException e) {
                    throw new ApiException(code, "The pairing response was not valid JSON.");
                }
                String t = o.optString("token", "");
                if (t.isEmpty()) {
                    JSONObject data = o.optJSONObject("data");
                    if (data != null) t = data.optString("token", "");
                }
                if (!t.isEmpty()) return t;
                throw new ApiException(code, "The pairing response did not contain a token.");
            }
            if (code >= 300 && code < 400) {
                throw new ApiException(code, "This pairing link was already used or has expired. Run `opencode pair` again.");
            }
            throw new ApiException(code, errorMessage(body, code));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Validates a token and address without changing anything. */
    public void check() throws IOException {
        raw("/api/info", "GET", null);
    }

    // ---------- Sessions ----------

    public List<SessionInfo> sessions(int limit, String cursor) throws IOException {
        String path = "/api/session?limit=" + limit;
        if (cursor != null && !cursor.isEmpty()) path += "&cursor=" + encode(cursor);
        JSONObject o = object(raw(path, "GET", null));
        List<SessionInfo> out = new ArrayList<>();
        JSONArray a = o.optJSONArray("data");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject so = a.optJSONObject(i);
                if (so != null) out.add(SessionInfo.from(so));
            }
        }
        return out;
    }

    public SessionInfo createSession(String directory) throws IOException {
        return createSession(directory, null, null, null);
    }

    public SessionInfo createSession(String directory, String providerId, String modelId, String variant) throws IOException {
        JSONObject body = new JSONObject();
        try {
            if (directory != null && !directory.isEmpty()) {
                body.put("location", new JSONObject().put("directory", directory));
            }
            if (providerId != null && !providerId.isEmpty()
                    && modelId != null && !modelId.isEmpty()) {
                JSONObject model = new JSONObject()
                        .put("providerID", providerId)
                        .put("id", modelId);
                if (variant != null && !variant.isEmpty()) model.put("variant", variant);
                body.put("model", model);
            }
        } catch (Exception ignored) { }
        JSONObject o = post("/api/session", body);
        JSONObject data = o.optJSONObject("data");
        SessionInfo s = SessionInfo.from(data != null ? data : o);
        if (s.modelId.isEmpty() && modelId != null && !modelId.isEmpty()) {
            s.modelId = modelId;
            s.providerId = providerId == null ? "" : providerId;
            s.variant = variant == null ? "" : variant;
        }
        return s;
    }

    public void deleteSession(String sessionId) throws IOException {
        raw("/api/session/" + encode(sessionId), "DELETE", null);
    }

    public MessagePage messages(String sessionId, int limit, String order, String cursor) throws IOException {
        String path = "/api/session/" + encode(sessionId) + "/message?limit=" + limit
                + "&order=" + encode(order == null ? "desc" : order);
        if (cursor != null && !cursor.isEmpty()) path += "&cursor=" + encode(cursor);
        JSONObject o = object(raw(path, "GET", null));
        List<ChatMessage> list = new ArrayList<>();
        JSONArray a = o.optJSONArray("data");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject mo = a.optJSONObject(i);
                if (mo != null) list.add(ChatMessage.from(mo));
            }
        }
        String next = null, prev = null;
        JSONObject cursorObj = o.optJSONObject("cursor");
        if (cursorObj != null) {
            next = cursorObj.optString("next", null);
            prev = cursorObj.optString("previous", null);
            if (next != null && next.isEmpty()) next = null;
            if (prev != null && prev.isEmpty()) prev = null;
        }
        return new MessagePage(list, next, prev);
    }

    /** Session IDs the server reports as currently running. */
    public Set<String> activeSessions() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        JSONObject o = object(raw("/api/session/active", "GET", null));
        JSONObject data = o.optJSONObject("data");
        if (data != null) {
            java.util.Iterator<String> it = data.keys();
            while (it.hasNext()) {
                String id = it.next();
                Object v = data.opt(id);
                if (v instanceof JSONObject) {
                    String type = ((JSONObject) v).optString("type", "");
                    if ("running".equals(type)) out.add(id);
                } else {
                    out.add(id);
                }
            }
        }
        return out;
    }

    public void prompt(String sessionId, String text) throws IOException {
        JSONObject body = new JSONObject();
        try { body.put("text", text); } catch (Exception ignored) { }
        post("/api/session/" + encode(sessionId) + "/prompt", body);
    }

    public void interrupt(String sessionId) throws IOException {
        raw("/api/session/" + encode(sessionId) + "/interrupt", "POST", null);
    }

    public void setModel(String sessionId, String providerId, String modelId, String variant) throws IOException {
        JSONObject model = new JSONObject();
        try {
            model.put("providerID", providerId);
            model.put("id", modelId);
            if (variant != null && !variant.isEmpty()) model.put("variant", variant);
        } catch (Exception ignored) { }
        JSONObject body = new JSONObject();
        try { body.put("model", model); } catch (Exception ignored) { }
        post("/api/session/" + encode(sessionId) + "/model", body);
    }

    // ---------- Permissions ----------

    public List<PermissionRequest> permissions(String sessionId) throws IOException {
        JSONObject o = object(raw("/api/session/" + encode(sessionId) + "/permission", "GET", null));
        List<PermissionRequest> out = new ArrayList<>();
        JSONArray a = o.optJSONArray("data");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject po = a.optJSONObject(i);
                if (po != null) out.add(PermissionRequest.from(po));
            }
        }
        return out;
    }

    public void replyPermission(String sessionId, String requestId, String decision) throws IOException {
        JSONObject body = new JSONObject();
        try { body.put("decision", decision); } catch (Exception ignored) { }
        post("/api/session/" + encode(sessionId) + "/permission/" + encode(requestId) + "/reply", body);
    }

    // ---------- Models and projects ----------

    public List<ServerModel> models() throws IOException {
        JSONObject o = object(raw("/api/model", "GET", null));
        List<ServerModel> out = new ArrayList<>();
        JSONArray a = o.optJSONArray("data");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject mo = a.optJSONObject(i);
                if (mo != null) out.add(ServerModel.from(mo));
            }
        }
        return out;
    }

    public List<ProjectInfo> projects() throws IOException {
        String body = raw("/api/project", "GET", null);
        List<ProjectInfo> out = new ArrayList<>();
        JSONArray a;
        String trimmed = body.trim();
        if (trimmed.startsWith("[")) {
            a = array(trimmed);
        } else {
            a = object(trimmed).optJSONArray("data");
        }
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject po = a.optJSONObject(i);
                if (po != null) out.add(ProjectInfo.from(po));
            }
        }
        return out;
    }

    // ---------- HTTP ----------

    private JSONObject post(String path, JSONObject body) throws IOException {
        String text = raw(path, "POST", body);
        if (text.trim().isEmpty()) return new JSONObject();
        return object(text);
    }

    private static JSONObject object(String text) throws IOException {
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException("The server returned an invalid JSON response.", e);
        }
    }

    private static JSONArray array(String text) throws IOException {
        try {
            return new JSONArray(text);
        } catch (JSONException e) {
            throw new IOException("The server returned an invalid JSON response.", e);
        }
    }

    private String raw(String path, String method, JSONObject body) throws IOException {
        HttpURLConnection c = null;
        try {
            URL url = new URL(baseUrl + path);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT);
            c.setReadTimeout(READ_TIMEOUT);
            c.setRequestMethod(method);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "OpenCodePair/1.0");
            if (!token.isEmpty()) {
                String basic = "opencode:" + token;
                c.setRequestProperty("Authorization", "Basic "
                        + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
            }

            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 300) throw new ApiException(code, errorMessage(text, code));
            return text;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream is) throws IOException {
        if (is == null) return "";
        try (InputStream in = is; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static String errorMessage(String body, int code) {
        if (body != null && !body.trim().isEmpty()) {
            try {
                JSONObject o = new JSONObject(body);
                String m = o.optString("message", "");
                if (!m.isEmpty()) return m;
                String tag = o.optString("_tag", "");
                if (!tag.isEmpty()) return tag;
            } catch (Exception ignored) { }
            String s = body.trim();
            if (s.length() > 220) s = s.substring(0, 219) + "\u2026";
            return s;
        }
        if (code == 401) return "Authentication failed. The pairing token may have expired.";
        return "HTTP " + code;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }
}
