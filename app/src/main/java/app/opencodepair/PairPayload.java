package app.opencodepair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what {@code opencode pair} produces.
 *
 * <p>The QR code is not a URL. It carries:
 * <pre>{"code":"&lt;one-time-code&gt;","urls":["http://192.168.x.x:port","https://&lt;id&gt;.opentunnel.xyz"]}</pre>
 * where {@code urls} are the server's base addresses with loopback already removed. The real
 * connect link for each base is {@code <base>/auth/connect/<code>}.
 *
 * <p>The three links printed next to the QR are those connect links themselves: loopback
 * (127.0.0.1), LAN (192.168.x.x) and the OpenTunnel HTTPS address. Plain links are accepted
 * as-is so any of the three can be typed, pasted or scanned.
 */
public final class PairPayload {

    private static final Pattern URL_RE =
            Pattern.compile("https?://[^\\s\"'<>()\\[\\]{}]+", Pattern.CASE_INSENSITIVE);

    private static final String CONNECT_PATH = "/auth/connect/";

    private PairPayload() {}

    /**
     * @return connect URLs, best first. Empty when the input is not usable.
     *         Loopback addresses are kept but moved last, because they only work on the PC itself.
     */
    public static List<String> parse(String raw) {
        List<String> found = new ArrayList<>();
        if (raw == null) return found;
        String s = raw.trim();
        if (s.isEmpty()) return found;

        if (s.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(s);
                String code = firstString(o, "code", "token", "pair", "pairingCode", "pairing_code");
                List<String> bases = stringsFrom(o, "urls", "url", "addresses", "address", "servers", "hosts");

                if (!bases.isEmpty()) {
                    for (String b : bases) {
                        String u = connectUrl(b, code);
                        if (u != null) found.add(u);
                    }
                    if (!found.isEmpty()) return dedupe(found);
                }
                // Some other JSON shape: fall through and look for a URL inside it.
            } catch (Exception ignored) {
                // not a JSON payload; treat it as text
            }
        }

        found.addAll(extractUrls(s));
        return dedupe(found);
    }

    /** True when the URL already points at a pairing endpoint. */
    public static boolean isPairingLink(String url) {
        return url != null && url.toLowerCase(Locale.US).contains(CONNECT_PATH);
    }

    /** Host part of an absolute URL, or "" when it cannot be read. */
    static String hostOf(String url) {
        if (url == null) return "";
        int at = url.indexOf("://");
        if (at < 0) return "";
        String rest = url.substring(at + 3);
        int end = rest.length();
        for (char c : new char[] {'/', '?', '#', ':'}) {
            int i = rest.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        return rest.substring(0, end).toLowerCase(Locale.US);
    }

    /** 127.0.0.1 / localhost only work on the machine running OpenCode. */
    static boolean isLoopback(String url) {
        String h = hostOf(url);
        return "localhost".equals(h) || "::1".equals(h) || "[::1]".equals(h) || h.startsWith("127.");
    }

    private static List<String> extractUrls(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = URL_RE.matcher(s);
        while (m.find()) {
            String u = trimTrailing(m.group());
            if (u.length() > "https://".length()) out.add(u);
        }
        return out;
    }

    private static String connectUrl(String base, String code) {
        if (base == null) return null;
        String b = trimTrailing(base.trim());
        if (b.isEmpty()) return null;
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://" + b;

        if (isPairingLink(b)) return b;                       // already a full connect link
        String prefix = trimSlashes(b);
        if (code == null || code.isEmpty()) return prefix;
        return prefix + CONNECT_PATH + encode(code);
    }

    private static String firstString(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty() && !"null".equals(v)) return v;
        }
        return "";
    }

    /** Accepts both a single string and an array of strings. */
    private static List<String> stringsFrom(JSONObject o, String... keys) {
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            if (!o.has(k)) continue;
            Object v = o.opt(k);
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                for (int i = 0; i < a.length(); i++) {
                    String s = a.optString(i, "");
                    if (!s.isEmpty() && !"null".equals(s)) out.add(s);
                }
            } else if (v instanceof String) {
                String s = (String) v;
                if (!s.isEmpty() && !"null".equals(s)) out.add(s);
            }
            if (!out.isEmpty()) return out;                   // first key that yields anything wins
        }
        return out;
    }

    private static List<String> dedupe(List<String> in) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> remote = new ArrayList<>();
        List<String> loopback = new ArrayList<>();
        for (String u : in) {
            if (u == null) continue;
            String v = u.trim();
            if (v.isEmpty() || !seen.add(v)) continue;
            (isLoopback(v) ? loopback : remote).add(v);
        }
        remote.addAll(loopback);
        return remote;
    }

    private static String trimTrailing(String s) {
        int end = s.length();
        while (end > 0 && ".,;:!?)]}'\"".indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(0, end);
    }

    private static String trimSlashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') end--;
        return s.substring(0, end);
    }

    private static String encode(String code) {
        try {
            return URLEncoder.encode(code, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return code;
        }
    }
}
