package app.opencodepair;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Saved computers. Each one is a base URL plus the token redeemed from the pairing link. */
public final class ConnectionStore {
    private static final String KEY_LIST = "connections";
    private static final String KEY_ACTIVE = "active_connection";
    private static final String KEY_LEGACY_URL = "url";

    public static final class Connection {
        public String id = "";
        public String name = "";
        public String baseUrl = "";
        public String token = "";
        public long lastUsed = 0;

        public Connection() {}

        public Connection(String name, String baseUrl, String token) {
            this.id = UUID.randomUUID().toString();
            this.name = name;
            this.baseUrl = baseUrl;
            this.token = token;
            this.lastUsed = System.currentTimeMillis();
        }

        public String host() {
            try {
                String h = Uri.parse(baseUrl).getAuthority();
                return h == null ? baseUrl : h;
            } catch (Exception e) {
                return baseUrl;
            }
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("baseUrl", baseUrl);
                o.put("token", token);
                o.put("lastUsed", lastUsed);
            } catch (Exception ignored) { }
            return o;
        }

        public static Connection from(JSONObject o) {
            Connection c = new Connection();
            c.id = o.optString("id", "");
            if (c.id.isEmpty()) c.id = UUID.randomUUID().toString();
            c.name = o.optString("name", "");
            c.baseUrl = o.optString("baseUrl", "");
            c.token = o.optString("token", "");
            c.lastUsed = o.optLong("lastUsed", 0);
            if (c.name.isEmpty()) c.name = c.host();
            return c;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;

    public ConnectionStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(Theme.PREFS, Context.MODE_PRIVATE);
    }

    public List<Connection> list() {
        List<Connection> out = new ArrayList<>();
        String raw = prefs.getString(KEY_LIST, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Connection c = Connection.from(o);
                if (c.baseUrl.isEmpty()) continue;
                out.add(c);
            }
        } catch (Exception ignored) { }
        Collections.sort(out, new Comparator<Connection>() {
            @Override public int compare(Connection a, Connection b) {
                return Long.compare(b.lastUsed, a.lastUsed);
            }
        });
        return out;
    }

    public Connection byId(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Connection c : list()) if (id.equals(c.id)) return c;
        return null;
    }

    public Connection active() {
        String id = prefs.getString(KEY_ACTIVE, "");
        List<Connection> all = list();
        if (!id.isEmpty()) for (Connection c : all) if (id.equals(c.id)) return c;
        return all.isEmpty() ? null : all.get(0);
    }

    public void setActive(String id) {
        prefs.edit().putString(KEY_ACTIVE, id == null ? "" : id).apply();
    }

    public void upsert(Connection c) {
        if (c == null || c.baseUrl.isEmpty()) return;
        List<Connection> all = list();
        if (c.id == null || c.id.isEmpty()) c.id = UUID.randomUUID().toString();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(c.id)) {
                all.set(i, c);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(c);
        save(all);
        if (prefs.getString(KEY_ACTIVE, "").isEmpty()) setActive(c.id);
    }

    public void remove(String id) {
        List<Connection> all = list();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
                break;
            }
        }
        save(all);
        if (id != null && id.equals(prefs.getString(KEY_ACTIVE, ""))) {
            setActive(all.isEmpty() ? "" : all.get(0).id);
        }
    }

    public void rename(String id, String name) {
        Connection c = byId(id);
        if (c == null) return;
        String trimmed = name == null ? "" : name.trim();
        c.name = trimmed.isEmpty() ? c.host() : trimmed;
        upsert(c);
    }

    private void save(List<Connection> all) {
        JSONArray a = new JSONArray();
        for (Connection c : all) a.put(c.toJson());
        prefs.edit().putString(KEY_LIST, a.toString()).apply();
    }

    /**
     * The previous version stored one URL and let the WebView keep the session cookie. Fold that
     * into the new connection list, reading the cookie value as the API token.
     */
    public void migrateLegacy() {
        String legacy = prefs.getString(KEY_LEGACY_URL, "");
        if (legacy == null || legacy.isEmpty()) return;
        if (list().isEmpty()) {
            String base = origin(legacy);
            if (!base.isEmpty()) {
                Connection c = new Connection();
                c.id = UUID.randomUUID().toString();
                c.baseUrl = base;
                c.name = Ui.host(base);
                c.token = cookieToken(context, base);
                c.lastUsed = System.currentTimeMillis();
                upsert(c);
                setActive(c.id);
            }
        }
        prefs.edit().remove(KEY_LEGACY_URL).apply();
    }

    public static String origin(String url) {
        if (url == null) return "";
        String s = url.trim();
        if (s.isEmpty()) return "";
        if (!s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) s = "http://" + s;
        try {
            Uri u = Uri.parse(s);
            String scheme = u.getScheme();
            if (scheme == null) return "";
            scheme = scheme.toLowerCase(java.util.Locale.US);
            if (!"http".equals(scheme) && !"https".equals(scheme)) return "";
            String host = u.getHost();
            if (host == null || host.isEmpty()) return "";
            StringBuilder b = new StringBuilder(scheme).append("://");
            if (host.contains(":") && !host.startsWith("[")) b.append('[').append(host).append(']');
            else b.append(host);
            int port = u.getPort();
            if (port > 0) b.append(':').append(port);
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Reads the session cookie the web UI uses; its value is the same token the API accepts. */
    public static String cookieToken(Context c, String baseUrl) {
        try {
            String cookies = CookieManager.getInstance().getCookie(baseUrl);
            if (cookies == null) return "";
            for (String part : cookies.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq <= 0) continue;
                String name = p.substring(0, eq);
                if ("opencode_session".equals(name) || name.startsWith("opencode_session_")) {
                    return Uri.decode(p.substring(eq + 1));
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    /**
     * Makes the full web UI work in the fallback WebView. The server names its cookie
     * {@code opencode_session_<port>} for non-default ports and {@code opencode_session} otherwise.
     */
    public static void setWebCookie(Context c, String baseUrl, String token) {
        if (baseUrl == null || baseUrl.isEmpty() || token == null || token.isEmpty()) return;
        try {
            String value = "; Path=/; Max-Age=2592000; SameSite=Lax";
            CookieManager cm = CookieManager.getInstance();
            cm.setCookie(baseUrl, "opencode_session=" + token + value);
            String suffix = portSuffix(baseUrl);
            if (!suffix.isEmpty()) {
                cm.setCookie(baseUrl, "opencode_session" + suffix + "=" + token + value);
            }
            cm.flush();
        } catch (Exception ignored) { }
    }

    private static String portSuffix(String baseUrl) {
        try {
            Uri u = Uri.parse(baseUrl);
            int port = u.getPort();
            String scheme = u.getScheme() == null ? "http" : u.getScheme().toLowerCase(java.util.Locale.US);
            boolean def = port <= 0 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
            return def ? "" : "_" + port;
        } catch (Exception e) {
            return "";
        }
    }
}
