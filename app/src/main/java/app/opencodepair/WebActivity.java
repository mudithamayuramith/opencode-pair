package app.opencodepair;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

/**
 * The escape hatch: the official OpenCode web UI, authenticated with the token we already have.
 * Used for diffs, terminals and anything the native screens do not cover yet.
 */
@SuppressLint("GestureBackNavigation")
public final class WebActivity extends Activity {
    public static final String EXTRA_CONNECTION = "connection_id";

    private static final int REQ_FILE = 60;

    private Theme t;
    private WebView web;
    private View errorView;
    private TextView errorText;
    private ValueCallback<Uri[]> fileCallback;
    private String host = "";
    private String baseUrl = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        t = Theme.get(this);

        String id = getIntent().getStringExtra(EXTRA_CONNECTION);
        ConnectionStore.Connection c = new ConnectionStore(this).byId(id);
        if (c == null) {
            Toast.makeText(this, R.string.chat_session_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        baseUrl = c.baseUrl;
        host = Uri.parse(baseUrl).getAuthority();
        ConnectionStore.setWebCookie(this, baseUrl, c.token);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        setContentView(root);
        Ui.systemBars(this, t);
        applyInsets(root);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(t.surface);
        bar.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 8), 0);

        ImageButton back = Ui.iconButton(this, R.drawable.ic_back, t.text, R.string.cd_back, t);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = Ui.text(this, getString(R.string.web_title), 15f, t.text, true);
        titles.addView(title);
        TextView sub = Ui.text(this, host, 11f, t.muted, false);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titles.addView(sub);
        bar.addView(titles);

        ImageButton reload = Ui.iconButton(this, R.drawable.ic_refresh, t.muted, R.string.web_reload, t);
        reload.setOnClickListener(v -> {
            hideError();
            web.reload();
        });
        bar.addView(reload);

        page.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));
        page.addView(Ui.divider(this, t.border));

        FrameLayout frame = new FrameLayout(this);
        page.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        web = new WebView(this);
        web.setBackgroundColor(t.bg);
        frame.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(web, true);
        }

        errorView = buildErrorView();
        frame.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                hideError();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    showError(error == null ? "" : String.valueOf(error.getDescription()));
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                           android.net.http.SslError error) {
                handler.cancel();
                showError("TLS certificate error");
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent i = params.createIntent();
                    startActivityForResult(i, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, R.string.common_error, Toast.LENGTH_SHORT).show();
            }
        });

        root.addView(page);
        web.loadUrl(baseUrl + "/");
        registerBack();
    }

    private View buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackgroundColor(t.bg);
        box.setPadding(Ui.dp(this, 32), Ui.dp(this, 64),
                Ui.dp(this, 32), Ui.dp(this, 32));
        box.setVisibility(View.GONE);

        ImageView icon = Ui.icon(this, R.drawable.ic_warning, t.dim, 42);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(this, 42), Ui.dp(this, 42));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(icon, ilp);

        TextView title = Ui.text(this, getString(R.string.web_error), 18f, t.text, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 16);
        box.addView(title, tlp);

        errorText = Ui.text(this, "", 12.5f, t.muted, false);
        errorText.setGravity(Gravity.CENTER);
        errorText.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = Ui.dp(this, 8);
        box.addView(errorText, elp);

        TextView retry = Ui.button(this, t, getString(R.string.web_retry), true);
        retry.setOnClickListener(v -> {
            hideError();
            web.loadUrl(baseUrl + "/");
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 46));
        rlp.gravity = Gravity.CENTER_HORIZONTAL;
        rlp.topMargin = Ui.dp(this, 20);
        box.addView(retry, rlp);
        return box;
    }

    private boolean handleUrl(Uri uri) {
        if (uri == null) return true;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.US);
        if ("http".equals(scheme) || "https".equals(scheme)) {
            String authority = uri.getAuthority();
            if (authority != null && authority.equalsIgnoreCase(host)) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) { }
            return true;
        }
        if ("tel".equals(scheme) || "mailto".equals(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) { }
            return true;
        }
        return true;
    }

    private void showError(String message) {
        if (errorView == null) return;
        errorText.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        if (errorView != null) errorView.setVisibility(View.GONE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void registerBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                        if (web != null && web.canGoBack()) web.goBack();
                        else finish();
                    });
        }
    }

    private void applyInsets(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            v.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return insets;
            });
        } else {
            v.setFitsSystemWindows(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            try {
                web.stopLoading();
                web.destroy();
            } catch (Exception ignored) { }
        }
        super.onDestroy();
    }
}
