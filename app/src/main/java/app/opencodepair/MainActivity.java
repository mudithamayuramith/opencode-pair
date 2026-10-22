package app.opencodepair;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OpenCode Pair — a fullscreen WebView client for the OpenCode Pair server.
 *
 * No AndroidX, no Kotlin. The single library is the pure-Java ZXing decoder used for QR
 * pairing; everything else is platform API.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "opencode_pair", KEY_URL = "url";
    private static final String SCHEME_APP = "app";

    private static final int REQ_FILE = 1, REQ_SCAN = 2;

    private static final int C_BG      = 0xFF0B0B0F;
    private static final int C_SURFACE = 0xFF17171F;
    private static final int C_FIELD   = 0xFF13131A;
    private static final int C_STROKE  = 0xFF2A2A36;
    private static final int C_TEXT    = 0xFFE5E7EB;
    private static final int C_MUTED   = 0xFF9CA3AF;
    private static final int C_DIM     = 0xFF6B7280;
    private static final int C_ACCENT  = 0xFF4F46E5;

    private static final int S_IDLE    = 0xFF6B7280;
    private static final int S_LOADING = 0xFFFBBF24;
    private static final int S_OK      = 0xFF34D399;
    private static final int S_ERROR   = 0xFFF87171;

    private SharedPreferences prefs;
    private FrameLayout root;

    /** Toolbar + progress + WebView. Hidden while the address editor is open. */
    private LinearLayout main;
    private View dot;
    private TextView hostLabel;
    private ProgressBar progress;
    private WebView web;

    /** Address editor overlay, or null. */
    private View settings;

    private ValueCallback<Uri[]> fileCallback;
    private boolean failed = false;
    private String loadedUrl;

    /**
     * Connect links from a scanned pairing code, in the order the server listed them.
     * Empty when the address was typed by hand, which disables the fallback.
     */
    private final List<String> candidates = new ArrayList<>();
    private int candidateIndex = -1;

    /** Platform predictive-back callback (API 33+). */
    private OnBackInvokedCallback backCallback;
    /** Guards against a single physical back press being handled twice. */
    private boolean backConsumed = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);
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

        if (settings != null) {                    // cancel the address editor
            if (web != null) resumeWeb(); else finish();
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

    // ---------- Address editor ----------

    private void showSettings() {
        if (main != null) main.setVisibility(View.GONE);
        if (settings != null) root.removeView(settings);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(C_BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(48), dp(24), dp(24));

        // App mark
        FrameLayout mark = new FrameLayout(this);
        GradientDrawable mb = new GradientDrawable();
        mb.setColor(C_ACCENT);
        mb.setCornerRadius(dp(16));
        mark.setBackground(mb);
        ImageView markIcon = new ImageView(this);
        markIcon.setImageResource(R.drawable.ic_fg);
        markIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mark.addView(markIcon, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        col.addView(mark, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView title = new TextView(this);
        title.setText(R.string.connect_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(20);
        col.addView(title, tlp);

        TextView sub = new TextView(this);
        sub.setText(R.string.connect_subtitle);
        sub.setTextColor(C_MUTED);
        sub.setTextSize(13.5f);
        sub.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        slp.bottomMargin = dp(28);
        col.addView(sub, slp);

        TextView label = new TextView(this);
        label.setText(R.string.label_server_address);
        label.setTextColor(C_DIM);
        label.setTextSize(11);
        label.setAllCaps(true);
        label.setLetterSpacing(0.09f);
        label.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.bottomMargin = dp(8);
        col.addView(label, llp);

        final EditText input = new EditText(this);
        input.setHint(R.string.connect_hint);
        input.setHintTextColor(C_DIM);
        input.setTextColor(C_TEXT);
        input.setTextSize(14);
        input.setTypeface(Typeface.MONOSPACE);
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setBackground(fieldBackground());
        input.setText(prefs.getString(KEY_URL, ""));
        col.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        final TextView err = new TextView(this);
        err.setTextColor(S_ERROR);
        err.setTextSize(12.5f);
        err.setPadding(0, dp(8), 0, 0);
        col.addView(err);

        Button go = new Button(this);
        go.setText(R.string.connect_action);
        go.setAllCaps(false);
        go.setTextColor(Color.WHITE);
        go.setTextSize(15.5f);
        go.setTypeface(null, Typeface.BOLD);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(C_ACCENT);
        gb.setCornerRadius(dp(12));
        go.setBackground(gb);
        go.setStateListAnimator(null);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        glp.topMargin = dp(22);
        col.addView(go, glp);

        Button scanBtn = new Button(this);
        scanBtn.setText(R.string.connect_scan);
        scanBtn.setAllCaps(false);
        scanBtn.setTextColor(C_TEXT);
        scanBtn.setTextSize(15f);
        scanBtn.setTypeface(null, Typeface.BOLD);
        scanBtn.setBackground(outlineBackground());
        scanBtn.setStateListAnimator(null);
        scanBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_qr, 0, 0, 0);
        scanBtn.setCompoundDrawablePadding(dp(10));
        scanBtn.setOnClickListener(v -> startScan());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        blp.topMargin = dp(10);
        col.addView(scanBtn, blp);

        TextView hint = new TextView(this);
        hint.setText(R.string.hint_server);
        hint.setTextColor(C_DIM);
        hint.setTextSize(12);
        hint.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(16);
        col.addView(hint, hlp);

        col.addView(new View(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = new TextView(this);
        foot.setText(R.string.footnote_saved);
        foot.setTextColor(0xFF4B5563);
        foot.setTextSize(11);
        col.addView(foot);

        final Runnable submit = () -> {
            String text = input.getText().toString().trim();

            // The QR payload is JSON, but it can also be pasted as text.
            if (text.startsWith("{")) {
                List<String> urls = PairPayload.parse(text);
                if (urls.isEmpty()) { err.setText(R.string.connect_invalid); return; }
                hideKeyboard(input);
                startPairing(urls);
                return;
            }

            String u = normalize(text);
            if (u == null) { err.setText(R.string.connect_invalid); return; }
            if (PairPayload.isLoopback(u)) { err.setText(R.string.connect_loopback); return; }
            candidates.clear();
            candidateIndex = -1;
            prefs.edit().putString(KEY_URL, u).apply();
            hideKeyboard(input);
            showWeb(u, null);
        };
        go.setOnClickListener(v -> submit.run());
        input.setOnEditorActionListener((v, a, e) -> { submit.run(); return true; });

        sv.addView(col, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        settings = sv;
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
    }

    private GradientDrawable fieldBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(C_FIELD);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), C_STROKE);
        return d;
    }

    private GradientDrawable outlineBackground() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(C_SURFACE);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), C_STROKE);
        return d;
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /** Closes the address editor and returns to the existing page without reloading it. */
    private void resumeWeb() {
        if (settings != null) { root.removeView(settings); settings = null; }
        if (main != null) main.setVisibility(View.VISIBLE);
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

    // ---------- Pairing: QR codes and the three printed links ----------

    private void startScan() {
        try {
            startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
        } catch (Exception e) {
            Toast.makeText(this, R.string.scan_no_camera, Toast.LENGTH_LONG).show();
        }
    }

    private void handlePairingPayload(String raw) {
        List<String> urls = PairPayload.parse(raw);
        if (urls.isEmpty()) {
            Toast.makeText(this, R.string.scan_not_pairing, Toast.LENGTH_LONG).show();
            return;
        }
        startPairing(urls);
    }

    /**
     * Connects to the first candidate and remembers the rest. Every candidate carries the same
     * single-use code, so if the LAN address is unreachable the tunnel address is tried next.
     */
    private void startPairing(List<String> urls) {
        if (urls.isEmpty()) return;
        candidates.clear();
        candidates.addAll(urls);
        candidateIndex = 0;
        String first = candidates.get(0);
        prefs.edit().putString(KEY_URL, first).apply();
        showWeb(first, null);
    }

    /** Moves to the next pairing candidate. False when there is nothing left to try. */
    private boolean tryNextCandidate(WebView v) {
        if (candidateIndex < 0 || candidateIndex + 1 >= candidates.size()) return false;
        candidateIndex++;
        String next = candidates.get(candidateIndex);
        prefs.edit().putString(KEY_URL, next).apply();
        loadedUrl = next;
        updateHost(next);
        failed = false;
        setStatus(S_LOADING);
        if (progress != null) progress.setVisibility(View.VISIBLE);
        v.loadUrl(next);
        return true;
    }

    /**
     * A pairing link is single-use, so once it has been redeemed the page it led to is what
     * should be reopened next time. The session cookie makes that page work again.
     */
    private void rememberWorkingUrl(String url) {
        if (url == null || !url.startsWith("http")) return;
        String saved = prefs.getString(KEY_URL, "");
        if (!PairPayload.isPairingLink(saved) || PairPayload.isPairingLink(url)) return;
        if (!PairPayload.hostOf(saved).equals(PairPayload.hostOf(url))) return;   // same server only
        prefs.edit().putString(KEY_URL, url).apply();
        loadedUrl = url;
    }

    // ---------- WebView + toolbar ----------

    private void showWeb(String url, Bundle saved) {
        if (settings != null) { root.removeView(settings); settings = null; }
        if (main == null) buildMain();
        main.setVisibility(View.VISIBLE);
        updateHost(url);

        boolean wasFailed = failed;
        failed = false;

        if (saved != null && web.restoreState(saved) != null) {
            loadedUrl = url;
            setStatus(S_OK);
            return;
        }
        boolean same = url.equals(loadedUrl);
        if (same && !wasFailed && web.getUrl() != null) return;   // already showing this page
        loadedUrl = url;
        setStatus(S_LOADING);
        web.loadUrl(url);
    }

    private void buildMain() {
        main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundResource(R.drawable.bg_topbar);
        topBar.setPadding(dp(16), 0, dp(6), 0);

        dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(S_IDLE);
        dot.setBackground(dotBg);
        topBar.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));

        hostLabel = new TextView(this);
        hostLabel.setTextColor(C_MUTED);
        hostLabel.setTextSize(12.5f);
        hostLabel.setTypeface(Typeface.MONOSPACE);
        hostLabel.setSingleLine(true);
        hostLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hlp.setMarginStart(dp(10));
        topBar.addView(hostLabel, hlp);

        ImageButton reloadBtn = iconButton(R.drawable.ic_refresh, R.string.cd_reload);
        reloadBtn.setOnClickListener(v -> reload());
        reloadBtn.setOnLongClickListener(v -> { showSettings(); return true; });
        topBar.addView(reloadBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        ImageButton settingsBtn = iconButton(R.drawable.ic_settings, R.string.cd_settings);
        settingsBtn.setOnClickListener(v -> showSettings());
        topBar.addView(settingsBtn, new LinearLayout.LayoutParams(dp(40), dp(40)));

        main.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.INVISIBLE);          // reserve the 2dp so nothing jumps
        progress.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(C_SURFACE));
        main.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        web = new WebView(this);
        web.setBackgroundColor(C_BG);
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
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                setStatus(S_LOADING);
            }
            @Override public void onPageFinished(WebView v, String url) {
                cm.flush();
                progress.setVisibility(View.INVISIBLE);
                if (!failed) {
                    setStatus(S_OK);
                    // Reached the server: the pairing attempt is done, keep this address only.
                    candidates.clear();
                    candidateIndex = -1;
                    rememberWorkingUrl(url);
                }
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    if (tryNextCandidate(v)) return;
                    failed = true;
                    setStatus(S_ERROR);
                    v.loadDataWithBaseURL(null, errorHtml(String.valueOf(e.getDescription())),
                            "text/html", "utf-8", null);
                }
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                h.cancel();
                if (tryNextCandidate(v)) return;
                failed = true;
                setStatus(S_ERROR);
                v.loadDataWithBaseURL(null, errorHtml(sslErrorMessage(e.getPrimaryError())),
                        "text/html", "utf-8", null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                progress.setVisibility(p < 100 ? View.VISIBLE : View.INVISIBLE);
                progress.setProgress(p);
            }
            @Override public void onPermissionRequest(PermissionRequest r) { r.grant(r.getResources()); }
            @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); } catch (Exception e) { fileCallback = null; return false; }
                return true;
            }
        });
        web.setDownloadListener((u, ua, cd, mime, len) -> openExternally(Uri.parse(u)));

        main.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));
    }

    private ImageButton iconButton(int drawableRes, int contentDescRes) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(drawableRes);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(9), dp(9), dp(9), dp(9));
        b.setContentDescription(getString(contentDescRes));
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            b.setBackgroundResource(tv.resourceId);
        } else {
            b.setBackgroundColor(Color.TRANSPARENT);
        }
        return b;
    }

    private void updateHost(String url) {
        if (hostLabel == null) return;
        String authority = Uri.parse(url).getAuthority();
        hostLabel.setText(authority == null || authority.isEmpty() ? url : authority);
    }

    private void setStatus(int color) {
        if (dot == null) return;
        android.graphics.drawable.Drawable d = dot.getBackground();
        if (d instanceof GradientDrawable) ((GradientDrawable) d).setColor(color);
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
        setStatus(S_LOADING);
        if (failed || web.getUrl() == null || web.getUrl().startsWith("data:") || "about:blank".equals(web.getUrl())) {
            failed = false; loadedUrl = base; web.loadUrl(base);
        } else web.reload();
    }

    private String errorHtml(String msg) {
        String url = prefs.getString(KEY_URL, "");
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>body{margin:0;padding:56px 24px;background:#0B0B0F;color:#E5E7EB;"
            + "font-family:-apple-system,Roboto,system-ui,sans-serif;text-align:center;"
            + "-webkit-text-size-adjust:100%}"
            + "h2{margin:0 0 10px;font-size:20px;font-weight:650;letter-spacing:-.01em}"
            + "p{margin:0;font-size:14px;line-height:1.5;color:#9CA3AF}"
            + ".addr{margin:18px auto 0;display:inline-block;font-family:ui-monospace,monospace;"
            + "font-size:12.5px;color:#C7C7D1;background:#13131A;border:1px solid #2A2A36;"
            + "border-radius:8px;padding:7px 12px;word-break:break-all}"
            + ".why{margin-top:16px;font-size:12.5px;color:#6B7280}"
            + ".act{display:block;max-width:280px;margin:26px auto 0;padding:15px;border-radius:12px;"
            + "font-size:15px;font-weight:600;text-decoration:none}"
            + ".primary{background:#4F46E5;color:#fff}"
            + ".secondary{margin-top:10px;background:#17171F;border:1px solid #2A2A36;color:#E5E7EB;"
            + "font-weight:500}"
            + "</style></head><body>"
            + "<h2>Can't reach OpenCode Pair</h2>"
            + "<p>The server did not respond.</p>"
            + "<div class='addr'>" + escape(url) + "</div>"
            + "<p class='why'>" + escape(msg) + "</p>"
            + "<p class='why'>Check that OpenCode Pair is running on your PC and that both devices are on the same network.</p>"
            + "<a class='act primary' href='app://retry'>Try again</a>"
            + "<a class='act secondary' href='app://edit'>Change address</a>"
            + "</body></html>";
    }

    private static String escape(String s) { return TextUtils.htmlEncode(s == null ? "" : s); }

    private static String sslErrorMessage(int primaryError) {
        switch (primaryError) {
            case SslError.SSL_NOTYETVALID:   return "The server certificate is not valid yet.";
            case SslError.SSL_EXPIRED:       return "The server certificate has expired.";
            case SslError.SSL_IDMISMATCH:    return "The server certificate does not match the address.";
            case SslError.SSL_UNTRUSTED:     return "The server certificate is not trusted.";
            case SslError.SSL_DATE_INVALID:  return "The server certificate date is invalid.";
            case SslError.SSL_INVALID:       return "The server certificate is invalid.";
            default:                         return "The server certificate could not be verified.";
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_SCAN) {
            if (res == RESULT_OK && data != null) {
                handlePairingPayload(data.getStringExtra(ScanActivity.EXTRA_TEXT));
            }
            return;
        }
        if (req == REQ_FILE && fileCallback != null) {
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
        if (web != null) {
            if (main != null) main.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
