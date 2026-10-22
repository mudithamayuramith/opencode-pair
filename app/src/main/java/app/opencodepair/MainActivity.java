package app.opencodepair;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;

public class MainActivity extends Activity {
    private static final String PREFS = "opencode_pair", KEY_URL = "url";
    private SharedPreferences prefs;
    private FrameLayout root;
    private WebView web;
    private ProgressBar progress;
    private TextView fab;
    private View settings;
    private ValueCallback<Uri[]> fileCallback;
    private boolean failed = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0B0F"));
        root.setFitsSystemWindows(true);
        setContentView(root);
        String url = prefs.getString(KEY_URL, "");
        if (url.isEmpty()) showSettings(); else showWeb(url, b);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

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
        title.setText("OpenCode Pair");
        title.setTextColor(Color.WHITE); title.setTextSize(28);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView sub = new TextView(this);
        sub.setText("Enter the server URL or IP:port shown by OpenCode Pair on your PC.");
        sub.setTextColor(Color.parseColor("#9CA3AF")); sub.setTextSize(14);
        sub.setPadding(0, dp(8), 0, dp(24));

        final EditText input = new EditText(this);
        input.setHint("http://192.168.1.10:4096");
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
        err.setPadding(0, dp(8), 0, 0);

        Button go = new Button(this);
        go.setText("Connect"); go.setAllCaps(false);
        go.setTextColor(Color.WHITE); go.setTextSize(16);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(Color.parseColor("#4F46E5")); gb.setCornerRadius(dp(12));
        go.setBackground(gb);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        glp.topMargin = dp(20);

        final Runnable submit = () -> {
            String u = normalize(input.getText().toString());
            if (u == null) { err.setText("Please enter a valid address."); return; }
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

    private String normalize(String s) {
        s = s.trim();
        if (s.isEmpty() || s.contains(" ")) return null;
        if (!s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) s = "http://" + s;
        Uri u = Uri.parse(s);
        return (u.getHost() == null || u.getHost().isEmpty()) ? null : s;
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
        web.setBackgroundColor(Color.parseColor("#0B0B0F"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("app".equals(u.getScheme())) {
                    if ("edit".equals(u.getHost())) showSettings(); else reload();
                    return true;
                }
                String base = prefs.getString(KEY_URL, "");
                String bh = Uri.parse(base).getHost();
                if (u.getHost() != null && bh != null && !u.getHost().equals(bh)
                        && ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) && r.isForMainFrame() && r.hasGesture()) {
                    // external link: open in system browser
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); return true; } catch (Exception ignored) {}
                }
                return false;
            }
            @Override public void onPageFinished(WebView v, String url) {
                cm.flush();
                progress.setVisibility(View.GONE);
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    failed = true;
                    v.loadDataWithBaseURL(null, errorHtml(String.valueOf(e.getDescription())), "text/html", "utf-8", null);
                }
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) { h.cancel(); }
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
        web.setDownloadListener((u, ua, cd, mime, len) -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Exception ignored) {}
        });

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

    private void reload() {
        String base = prefs.getString(KEY_URL, "");
        if (failed || web.getUrl() == null || web.getUrl().startsWith("data:") || "about:blank".equals(web.getUrl())) {
            failed = false; web.loadUrl(base);
        } else web.reload();
    }

    private String errorHtml(String msg) {
        String url = prefs.getString(KEY_URL, "");
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
            + "<body style='background:#0B0B0F;color:#E5E7EB;font-family:sans-serif;text-align:center;padding:48px 24px'>"
            + "<h2>Can't reach OpenCode Pair</h2><p style='color:#9CA3AF'>" + TextUtils_escape(url) + "<br>" + TextUtils_escape(msg) + "</p>"
            + "<p style='color:#9CA3AF;font-size:14px'>Check that the PC server is running and both devices are on the same network (or tunnel is up).</p>"
            + "<a href='app://retry' style='display:block;margin:24px auto 12px;max-width:260px;padding:14px;border-radius:12px;background:#4F46E5;color:#fff;text-decoration:none'>Retry</a>"
            + "<a href='app://edit' style='display:block;margin:0 auto;max-width:260px;padding:14px;border-radius:12px;background:#17171F;color:#fff;text-decoration:none'>Edit URL</a>"
            + "</body></html>";
    }
    private static String TextUtils_escape(String s) { return android.text.TextUtils.htmlEncode(s); }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
        } else super.onActivityResult(req, res, data);
    }

    @Override public void onBackPressed() {
        if (settings != null && web != null && !prefs.getString(KEY_URL, "").isEmpty()) {
            showWeb(prefs.getString(KEY_URL, ""), null); // cancel editing
            return;
        }
        if (web != null && web.getVisibility() == View.VISIBLE && web.canGoBack() && !failed) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); if (web != null) web.saveState(o); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onPause() { if (web != null) { web.onPause(); CookieManager.getInstance().flush(); } super.onPause(); }
    @Override protected void onDestroy() { if (web != null) { root.removeView(web); web.destroy(); } super.onDestroy(); }
}
