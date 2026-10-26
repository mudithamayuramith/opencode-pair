package app.opencodepair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A message in a session, flattened from the polymorphic API shape into something drawable. */
public final class ChatMessage {
    public static final int TYPE_OTHER = 0;
    public static final int TYPE_USER = 1;
    public static final int TYPE_ASSISTANT = 2;
    public static final int TYPE_SYSTEM = 3;
    public static final int TYPE_SYNTHETIC = 4;
    public static final int TYPE_SHELL = 5;
    public static final int TYPE_COMPACTION = 6;
    public static final int TYPE_IDLE = 7;

    public static final int PART_TEXT = 1;
    public static final int PART_REASONING = 2;
    public static final int PART_TOOL = 3;

    public String id = "";
    public int kind = TYPE_OTHER;
    public String text = "";
    public String outcome = "";
    public String error = "";
    public String finish = "";
    public String agent = "";
    public String modelId = "";
    public String providerId = "";
    public String variant = "";
    public long created = 0;
    public long completed = 0;
    public int retryAttempt = 0;
    public String retryError = "";
    public boolean optimistic = false;
    public final List<Part> parts = new ArrayList<>();

    public static final class Part {
        public int kind;
        public String text = "";
        public String toolName = "";
        public String toolStatus = "";
        public String toolInput = "";
        public String toolOutput = "";
        public String toolError = "";
        public boolean toolRunning = false;
    }

    public static ChatMessage from(JSONObject o) {
        ChatMessage m = new ChatMessage();
        m.id = o.optString("id", "");
        String type = o.optString("type", "");
        if ("user".equals(type)) m.kind = TYPE_USER;
        else if ("assistant".equals(type)) m.kind = TYPE_ASSISTANT;
        else if ("system".equals(type)) m.kind = TYPE_SYSTEM;
        else if ("synthetic".equals(type)) m.kind = TYPE_SYNTHETIC;
        else if ("shell".equals(type)) m.kind = TYPE_SHELL;
        else if ("compaction".equals(type)) m.kind = TYPE_COMPACTION;
        else if ("idle".equals(type)) m.kind = TYPE_IDLE;
        else m.kind = TYPE_OTHER;

        m.text = o.optString("text", "");
        m.outcome = o.optString("outcome", "");
        m.finish = o.optString("finish", "");
        m.agent = o.optString("agent", "");

        Object err = o.opt("error");
        if (err instanceof JSONObject) m.error = ((JSONObject) err).optString("message", "");
        else if (err instanceof String) m.error = (String) err;

        JSONObject time = o.optJSONObject("time");
        if (time != null) {
            m.created = time.optLong("created", 0);
            m.completed = time.optLong("completed", 0);
        }
        JSONObject model = o.optJSONObject("model");
        if (model != null) {
            m.modelId = model.optString("id", "");
            m.providerId = model.optString("providerID", "");
            m.variant = model.optString("variant", "");
        }
        JSONObject retry = o.optJSONObject("retry");
        if (retry != null) {
            m.retryAttempt = retry.optInt("attempt", 0);
            JSONObject retryError = retry.optJSONObject("error");
            if (retryError != null) m.retryError = retryError.optString("message", "");
        }

        if ("compaction".equals(type)) {
            if (m.text.isEmpty()) m.text = o.optString("summary", "");
        }
        if ("shell".equals(type)) {
            StringBuilder sb = new StringBuilder();
            sb.append(o.optString("command", ""));
            JSONObject out = o.optJSONObject("output");
            if (out != null) {
                String body = out.optString("output", "");
                if (!body.isEmpty()) sb.append('\n').append(body);
            }
            m.text = sb.toString().trim();
        }

        JSONArray content = o.optJSONArray("content");
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject p = content.optJSONObject(i);
                if (p == null) continue;
                String pt = p.optString("type", "");
                Part part = new Part();
                if ("text".equals(pt)) {
                    part.kind = PART_TEXT;
                    part.text = p.optString("text", "");
                } else if ("reasoning".equals(pt)) {
                    part.kind = PART_REASONING;
                    part.text = p.optString("text", "");
                } else if ("tool".equals(pt)) {
                    part.kind = PART_TOOL;
                    part.toolName = p.optString("name", "");
                    JSONObject state = p.optJSONObject("state");
                    if (state != null) {
                        part.toolStatus = state.optString("status", "");
                        part.toolRunning = "running".equals(part.toolStatus) || "streaming".equals(part.toolStatus);
                        part.toolInput = jsonText(state.opt("input"));
                        JSONArray out = state.optJSONArray("content");
                        if (out != null) part.toolOutput = contentText(out);
                        JSONObject e = state.optJSONObject("error");
                        if (e != null) part.toolError = e.optString("message", "");
                    }
                } else {
                    continue;
                }
                if (part.kind == PART_TEXT && part.text.isEmpty()) continue;
                if (part.kind == PART_REASONING && part.text.isEmpty()) continue;
                m.parts.add(part);
            }
        }
        return m;
    }

    public static ChatMessage optimistic(String id, String text) {
        ChatMessage m = new ChatMessage();
        m.id = id;
        m.kind = TYPE_USER;
        m.text = text;
        m.created = System.currentTimeMillis();
        m.optimistic = true;
        return m;
    }

    public boolean isBusy() {
        if (kind == TYPE_ASSISTANT && completed == 0) return true;
        for (Part p : parts) {
            if (p.kind == PART_TOOL && p.toolRunning) return true;
        }
        return false;
    }

    /**
     * Change detector used to avoid rebuilding the chat on every poll. Includes content hashes
     * rather than lengths: a streamed replacement can keep the same length, and tool output can
     * change in place while a command is still running.
     */
    public String signature() {
        StringBuilder sb = new StringBuilder(96);
        sb.append(id).append('|').append(kind).append('|')
                .append(created).append('|').append(completed).append('|')
                .append(outcome).append('|').append(error).append('|')
                .append(finish).append('|').append(agent).append('|')
                .append(modelId).append('|').append(retryAttempt).append('|')
                .append(hash(retryError)).append('|').append(hash(text)).append('|');
        for (Part p : parts) {
            sb.append(p.kind).append(':')
                    .append(hash(p.text)).append(':')
                    .append(p.toolName).append(':')
                    .append(p.toolStatus).append(':')
                    .append(p.toolRunning ? '1' : '0').append(':')
                    .append(hash(p.toolInput)).append(':')
                    .append(hash(p.toolOutput)).append(':')
                    .append(hash(p.toolError)).append('|');
        }
        return sb.toString();
    }

    private static int hash(String s) {
        return s == null ? 0 : s.hashCode();
    }

    private static String jsonText(Object o) {
        if (o == null) return "";
        if (o instanceof JSONObject) {
            try { return ((JSONObject) o).toString(2); } catch (Exception e) { return o.toString(); }
        }
        if (o instanceof JSONArray) {
            try { return ((JSONArray) o).toString(2); } catch (Exception e) { return o.toString(); }
        }
        if (o instanceof String) return (String) o;
        return String.valueOf(o);
    }

    private static String contentText(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String type = o.optString("type", "");
            if ("text".equals(type)) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(o.optString("text", ""));
            } else if ("file".equals(type)) {
                if (sb.length() > 0) sb.append('\n');
                String name = o.optString("name", "");
                String uri = o.optString("uri", "");
                sb.append("[file] ").append(name.isEmpty() ? uri : name);
            }
        }
        return sb.toString();
    }
}
