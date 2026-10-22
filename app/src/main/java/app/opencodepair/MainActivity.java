package app.opencodepair;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.Locale;

/**
 * OpenCode Pair — a fullscreen WebView client for the OpenCode Pair server.
 *
 * Dependency-free (no AndroidX, no Kotlin) so the build stays trivial.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "opencode_pair", KEY_URL = "url";
    private static final String SCHEME_APP = "app";
    private static final String BG = "#0B0B0F";

    private SharedPreferences prefs;
    private FrameLayout root;
    private WebView web;
    private ProgressBar progress;
    private TextView fab;
    private View settings;
    private ValueCallback<Uri[]> fileCallback;
    private boolean failed = false;

    /** Platform predictive-back callback (API 33+). */
    private OnBackInvokedCallback backCallback;
    /** Guards against a single physical back press being handled twice. */
    private boolean backConsumed = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor(BG));
        setContentView(root);
        applyWindowInsets();
        registerBackHandler();
        String url = prefs.getString(KEY_URL, "");
        if (url.isEmpty()) showSettings(); else showWeb(url, b);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ---------- Window insets / edge-to-edge ----------

    /**
     * Android 15+ enforces edge-to-edge for targetSdk 35+, and on Android 11+ the window no
     * longer resizes for the soft keyboard when decorFitsSystemWindows is false. So from
     * API 30 we lay the content out ourselves from system-bar, cutout and IME insets.
     * Older releases keep the classic fitsSystemWindows + adjustResize behaviour.
     */
    private void applyWindowInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return insets;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
        root.requestApplyInsets();
    }

    // ---------- Back handling ----------

    /**
     * Apps targeting Android 16+ no longer receive onBackPressed()/KEYCODE_BACK, so the
     * platform callback is registered on API 33+. Older releases use onBackPressed().
     */
    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    private void handleBack() {
        if (backConsumed) return;
        backConsumed = true;
        root.post(() -> backConsumed = false);

        String saved = prefs.getString(KEY_URL, "");
        if (settings != null) {                       // cancel the URL editor
            if (web != null && !saved.isEmpty()) showWeb(saved, null);
            else finish();
            return;
        }
        if (web != null && web.getVisibility() == View.VISIBLE && !failed && web.canGoBack()) {
            web.goBack();
            return;
        }
        finish();
    }

    /**
     * Legacy path: only reached on API 26-32, where the predictive back system does not exist
     * and onBackPressed() is still delivered. On API 33+ the OnBackInvokedCallback above is
     * used instead, so lint's GestureBackNavigation warning does not apply here.
     */
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }

    // ---------- Settings screen ----------

    private void showSettings() {
        if (web != null) web.setVisibility(View.GONE);
        if (fab != null) fab.setVisibility(View.GONE);
        if (progress != null) progress.setVisibility(View.GONE);
        if (settings != null) root.removeView(settings);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(dp(28), dp(28), dp(28), dp(28));

        TextView title = new TextView(this);
        title.setText(R.string.connect_title);
        title.setTextColor(Color.WHITE); title.setTextSize(28);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView sub = new TextView(this);
        sub.setText(R.string.connect_subtitle);
        sub.setTextColor(Color.parseColor("#9CA3AF")); sub.setTextSize(14);
        sub.setPadding(0, dp(8), dp(0), dp(24));

        final EditText input = new EditText(this);
        input.setHint(R.string.connect_hint);
        input.setHintTextColor(Color.parseColor("#6B7280"));
        input.setTextColor(Color.WHITE);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#17171F")); bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#2A2A36"));
        input.setBackground(bg);
        input.setText(prefs.getString(KEY_URL, ""));

        final TextView err = new TextView(this);
        err.setTextColor(Color.parseColor("#F87171")); err.setTextSize(13);
        err.setPadding(0, dp(8), dp(0), dp(0));

        Button go = new Button(this);
        go.setText(R.string.connect_action); go.setAllCaps(false);
        go.setTextColor(Color.WHITE); go.setTextSize(16);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(Color.parseColor("#4F46E5")); gb.setCornerRadius(dp(12));
        go.setBackground(gb);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        glp.topMargin = dp(20);

        final Runnable submit = () -> {
            String u = normalize(input.getText().toString());
            if (u == null) { err.setText(R.string.connect_invalid); return; }
            prefs.edit().putString(KEY_URL, u).apply();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            showWeb(u, null);
        };
        go.setOnClickListener(v -> submit.run());
        input.setOnEditorActionListener((v, a, e) -> { submit.run(); return true; });

        col.addView(title); col.addView(sub); col.addView(input);
        col.addView(err); col.addView(go, glp);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        settings = sv;
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
    }

    /**
     * Accepts a bare host, host:port or full URL and returns a normalised http(s) URL.
     * Anything else (file:, javascript:, content:, ...) is rejected.
     */
    private String normalize(String s) {
        s = s.trim();
        if (s.isEmpty() || s.contains(" ")) return null;
        if (!s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) s = "http://" + s;
        Uri u = Uri.parse(s);
        String scheme = u.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase(Locale.US);
        if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
        String host = u.getHost();
        if (host == null || host.isEmpty()) return null;
        return s;
    }

    // ---------- WebView ----------

    private void showWeb(String url, Bundle saved) {
        if (settings != null) { root.removeView(settings); settings = null; }
        if (web == null) buildWeb();
        web.setVisibility(View.VISIBLE);
        fab.setVisibility(View.VISIBLE);
        failed = false;
        if (saved != null && web.restoreState(saved) != null) return;
        web.loadUrl(url);
    }

    private void buildWeb() {
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor(BG));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.US);
                if (SCHEME_APP.equals(scheme)) {
                    if ("edit".equals(u.getHost())) showSettings(); else reload();
                    return true;
                }
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String base = prefs.getString(KEY_URL, "");
                    String bh = Uri.parse(base).getHost();
                    boolean sameHost = u.getHost() != null && u.getHost().equals(bh);
                    if (!sameHost && r.isForMainFrame() && r.hasGesture()) {
                        if (openExternally(u)) return true;   // hand off to the system browser
                    }
                    return false;
                }
                // tel:, mailto:, geo:, intent:, ... -> let another app handle it
                return openExternally(u);
            }
            @Override public void onPageFinished(WebView v, String url) {
                cm.flush();
                progress.setVisibility(View.GONE);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    failed = true;
                    v.loadDataWithBaseURL(null, errorHtml(String.valueOf(e.getDescription())),
                            "text/html", "utf-8", null);
                }
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                h.cancel();
                failed = true;
                v.loadDataWithBaseURL(null, errorHtml(sslErrorMessage(e.getPrimaryError())),
                        "text/html", "utf-8", null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
                progress.setProgress(p);
            }
            @Override public void onPermissionRequest(PermissionRequest r) { r.grant(r.getResources()); }
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try { startActivityForResult(p.createIntent(), 1); } catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });
        web.setDownloadListener((u, ua, cd, mime, len) -> openExternally(Uri.parse(u)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);

        fab = new TextView(this);
        fab.setText("\u21BB");
        fab.setGravity(Gravity.CENTER);
        fab.setTextColor(Color.WHITE); fab.setTextSize(20);
        GradientDrawable fb = new GradientDrawable();
        fb.setShape(GradientDrawable.OVAL); fb.setColor(Color.parseColor("#B34F46E5"));
        fab.setBackground(fb);
        fab.setOnClickListener(v -> reload());
        fab.setOnLongClickListener(v -> { showSettings(); return true; });

        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, dp(3));
        pl.gravity = Gravity.TOP;
        root.addView(progress, pl);
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(dp(44), dp(44));
        fl.gravity = Gravity.BOTTOM | Gravity.END;
        fl.setMargins(0, 0, dp(12), dp(84));
        root.addView(fab, fl);
    }

    /** Hands a non-web URL to another app. Returns false when nothing can handle it. */
    private boolean openExternally(Uri u) {
        try {
            Intent i;
            if ("intent".equalsIgnoreCase(u.getScheme())) {
                i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
            } else {
                i = new Intent(Intent.ACTION_VIEW, u);
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void reload() {
        String base = prefs.getString(KEY_URL, "");
        if (base.isEmpty()) { showSettings(); return; }
        if (failed || web.getUrl() == null || web.getUrl().startsWith("data:") || "about:blank".equals(web.getUrl())) {
            failed = false; web.loadUrl(base);
        } else web.reload();
    }

    private String errorHtml(String msg) {
        String url = prefs.getString(KEY_URL, "");
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
            + "<body style='background:#0B0B0F;color:#E5E7EB;font-family:sans-serif;text-align:center;padding:48px 24px'>"
            + "<h2>Can't reach OpenCode Pair</h2><p style='color:#9CA3AF'>" + escape(url) + "<br>" + escape(msg) + "</p>"
            + "<p style='color:#9CA3AF;font-size:14px'>Check that the PC server is running and both devices are on the same network (or tunnel is up).</p>"
            + "<a href='app://retry' style='display:block;margin:24px auto 12px;max-width:260px;padding:14px;border-radius:12px;background:#4F46E5;color:#fff;text-decoration:none'>Retry</a>"
            + "<a href='app://edit' style='display:block;margin:0 auto;max-width:260px;padding:14px;border-radius:12px;background:#17171F;color:#fff;text-decoration:none'>Edit URL</a>"
            + "</body></html>";
    }

    private static String escape(String s) { return android.text.TextUtils.htmlEncode(s == null ? "" : s); }

    private static String sslErrorMessage(int primaryError) {
        switch (primaryError) {
            case SslError.SSL_NOTYETVALID:   return "the server certificate is not valid yet";
            case SslError.SSL_EXPIRED:       return "the server certificate has expired";
            case SslError.SSL_IDMISMATCH:    return "the server certificate does not match the address";
            case SslError.SSL_UNTRUSTED:     return "the server certificate is not trusted";
            case SslError.SSL_DATE_INVALID:  return "the server certificate date is invalid";
            case SslError.SSL_INVALID:       return "the server certificate is invalid";
            default:                         return "the server certificate could not be verified";
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
        } else super.onActivityResult(req, res, data);
    }

    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); if (web != null) web.saveState(o); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onPause() { if (web != null) { web.onPause(); CookieManager.getInstance().flush(); } super.onPause(); }

    @Override protected void onDestroy() {
        if (backCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        if (web != null) { root.removeView(web); web.destroy(); }
        super.onDestroy();
    }
}
