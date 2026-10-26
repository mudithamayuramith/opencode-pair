package app.opencodepair;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pair a computer. QR is the primary path (and the reason this app exists); manual address plus
 * password is the fallback for a server that was started with a password of its own.
 */
@SuppressLint("GestureBackNavigation")
public final class AddConnectionActivity extends Activity {
    private static final int REQ_SCAN = 31;

    private Theme t;
    private ConnectionStore store;
    private FrameLayout root;
    private FrameLayout overlay;
    private TextView overlayText;
    private TextView error;
    private EditText address;
    private EditText password;
    private EditText name;
    private boolean busy;
    private boolean pairing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        t = Theme.get(this);
        store = new ConnectionStore(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        setContentView(root);
        Ui.systemBars(this, t);
        applyInsets(root);

        LinearLayout page = Ui.col(this);
        page.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(topBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout content = Ui.col(this);
        content.setPadding(Ui.dp(this, 24), Ui.dp(this, 10), Ui.dp(this, 24), Ui.dp(this, 36));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        buildContent(content);
        root.addView(page);
        buildOverlay();
        registerBack();
    }

    private View topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 12), 0);
        ImageButton back = Ui.iconButton(this, R.drawable.ic_back, t.text, R.string.cd_back, t);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = Ui.text(this, getString(R.string.add_title), 16f, t.text, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Ui.dp(this, 4));
        bar.addView(title, lp);
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        return bar;
    }

    private void buildContent(LinearLayout content) {
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(Ui.round(this, t.cta, 18));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        logo.setColorFilter(t.ctaText);
        hero.addView(logo, new FrameLayout.LayoutParams(
                Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64));
        content.addView(hero, hlp);

        TextView heading = Ui.text(this, getString(R.string.add_heading), 26f, t.text, true);
        heading.setLetterSpacing(-0.02f);
        heading.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.topMargin = Ui.dp(this, 20);
        content.addView(heading, headLp);

        TextView subtitle = Ui.text(this, getString(R.string.add_subtitle), 13.5f, t.muted, false);
        subtitle.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(this, 8);
        content.addView(subtitle, subLp);

        TextView scan = Ui.button(this, t, getString(R.string.add_scan), true);
        scan.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_qr, 0, 0, 0);
        scan.setCompoundDrawablePadding(Ui.dp(this, 10));
        try {
            scan.setCompoundDrawableTintList(ColorStateList.valueOf(t.ctaText));
        } catch (Exception ignored) { }
        scan.setOnClickListener(v -> startScan());
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 54));
        scanLp.topMargin = Ui.dp(this, 24);
        content.addView(scan, scanLp);

        LinearLayout orRow = Ui.row(this);
        View line1 = new View(this);
        line1.setBackgroundColor(t.border);
        orRow.addView(line1, new LinearLayout.LayoutParams(0, Math.max(1, Ui.dp(this, 1)), 1f));
        TextView or = Ui.text(this, getString(R.string.add_or), 11.5f, t.muted, false);
        or.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        orRow.addView(or);
        View line2 = new View(this);
        line2.setBackgroundColor(t.border);
        orRow.addView(line2, new LinearLayout.LayoutParams(0, Math.max(1, Ui.dp(this, 1)), 1f));
        LinearLayout.LayoutParams orLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        orLp.topMargin = Ui.dp(this, 24);
        orLp.bottomMargin = Ui.dp(this, 6);
        content.addView(orRow, orLp);

        address = Ui.field(this, t, getString(R.string.add_address_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        addField(content, R.string.add_address_label, address, 16);

        password = Ui.field(this, t, getString(R.string.add_password_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false);
        addField(content, R.string.add_password_label, password, 16);

        name = Ui.field(this, t, getString(R.string.add_name_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME, false);
        addField(content, R.string.add_name_label, name, 16);

        error = Ui.text(this, "", 12.5f, t.error, false);
        error.setVisibility(View.GONE);
        error.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errLp.topMargin = Ui.dp(this, 14);
        content.addView(error, errLp);

        TextView connect = Ui.button(this, t, getString(R.string.add_connect), true);
        connect.setOnClickListener(v -> manualConnect());
        LinearLayout.LayoutParams connectLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        connectLp.topMargin = Ui.dp(this, 16);
        content.addView(connect, connectLp);

        TextView hint = Ui.text(this, getString(R.string.add_hint), 11.5f, t.dim, false);
        hint.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = Ui.dp(this, 14);
        content.addView(hint, hintLp);

        LinearLayout setup = Ui.col(this);
        setup.setBackground(Ui.round(this, t.surface, 14, t.border, 1));
        setup.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));

        TextView setupTitle = Ui.label(this, getString(R.string.add_setup_title), t.muted);
        setup.addView(setupTitle);

        TextView cmd = Ui.text(this, "opencode pair", 13f, t.codeText, false);
        cmd.setTypeface(Typeface.MONOSPACE);
        cmd.setBackground(Ui.round(this, t.codeBg, 8));
        cmd.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        LinearLayout.LayoutParams cmdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cmdLp.topMargin = Ui.dp(this, 10);
        setup.addView(cmd, cmdLp);

        TextView setupBody = Ui.text(this, getString(R.string.add_setup_body), 12.5f, t.muted, false);
        setupBody.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = Ui.dp(this, 10);
        setup.addView(setupBody, bodyLp);

        LinearLayout.LayoutParams setupLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        setupLp.topMargin = Ui.dp(this, 28);
        content.addView(setup, setupLp);
    }

    private void addField(LinearLayout parent, int labelRes, EditText field, int topDp) {
        TextView label = Ui.label(this, getString(labelRes), t.muted);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Ui.dp(this, topDp);
        llp.bottomMargin = Ui.dp(this, 8);
        parent.addView(label, llp);
        parent.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void buildOverlay() {
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(t.scrim);
        overlay.setClickable(true);
        overlay.setOnClickListener(v -> { });
        overlay.setVisibility(View.GONE);

        LinearLayout card = Ui.col(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.round(this, t.surface, 16));
        card.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));

        ProgressBar p = new ProgressBar(this);
        p.setIndeterminate(true);
        card.addView(p, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));

        overlayText = Ui.text(this, getString(R.string.add_connecting), 13.5f, t.text, false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 14);
        card.addView(overlayText, tlp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                Ui.dp(this, 230), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(card, clp);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void startScan() {
        if (busy) return;
        startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SCAN || resultCode != RESULT_OK || data == null) return;
        String text = data.getStringExtra(ScanActivity.EXTRA_TEXT);
        List<String> urls = PairPayload.parse(text);
        if (urls.isEmpty()) {
            showError(getString(R.string.add_error_no_pairing));
            return;
        }
        pair(urls);
    }

    private void pair(List<String> urls) {
        if (busy) return;

        List<String> remote = new ArrayList<>();
        List<String> loopback = new ArrayList<>();
        for (String url : urls) {
            String base = ConnectionStore.origin(url);
            if (base.isEmpty()) continue;
            (PairPayload.isLoopback(base) ? loopback : remote).add(url);
        }

        // `opencode pair` puts the localhost link first, and that is the one encoded in its QR
        // code. A phone can never reach it, so try the addresses we already know for this
        // computer (the code is valid on every address of the same server), then ask for one.
        List<String> candidates = new ArrayList<>(remote);
        for (String url : loopback) {
            for (ConnectionStore.Connection c : store.list()) {
                String base = ConnectionStore.origin(c.baseUrl);
                if (base.isEmpty() || PairPayload.isLoopback(base)) continue;
                String rewritten = PairPayload.withBase(url, base);
                if (rewritten != null && !candidates.contains(rewritten)) candidates.add(rewritten);
            }
        }

        if (candidates.isEmpty()) {
            if (!loopback.isEmpty()) promptForComputerAddress(loopback.get(0));
            else showError(getString(R.string.add_error_no_pairing));
            return;
        }

        // Only offer the address prompt when the scan had no reachable link at all. If the
        // printed LAN/tunnel links were tried and failed, the network error is more useful.
        String fallback = remote.isEmpty() && !loopback.isEmpty() ? loopback.get(0) : null;
        startPairing(candidates, fallback);
    }

    private void startPairing(List<String> candidates, String loopbackFallback) {
        busy = true;
        pairing = true;
        showError(null);
        showOverlay(getString(R.string.add_pairing));
        Bg.run(() -> {
            Exception last = null;
            for (String url : candidates) {
                try {
                    String token = ApiClient.redeem(url);
                    // Do not discard the token if a follow-up probe fails. Redeeming proved the
                    // address is reachable and the code valid, and codes are single-use.
                    return new PairResult(ConnectionStore.origin(url), token);
                } catch (Exception e) {
                    last = e;
                }
            }
            if (last != null) throw last;
            throw new java.io.IOException("No pairing link.");
        }, this::onPaired, e -> {
            if (isFinishing() || isDestroyed()) return;
            busy = false;
            hideOverlay();
            if (loopbackFallback != null) promptForComputerAddress(loopbackFallback);
            else showError(messageFor(e));
        });
    }

    /**
     * A QR scan from `opencode pair` only contains the loopback link, which a phone cannot use.
     * Let the user paste the LAN/tunnel link (or just the computer's address) once; the scanned
     * one-time code is kept and redeemed against that address.
     */
    private void promptForComputerAddress(String loopbackUrl) {
        if (isFinishing() || isDestroyed() || busy) return;

        EditText input = Ui.field(this, t, getString(R.string.add_loopback_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        ConnectionStore.Connection active = store.active();
        if (active != null && !PairPayload.isLoopback(active.baseUrl)) {
            input.setText(active.baseUrl);
        } else {
            String typed = address.getText().toString().trim();
            if (!typed.isEmpty()) input.setText(typed);
        }
        input.setSelectAllOnFocus(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this, Ui.dialogTheme(t))
                .setTitle(R.string.add_loopback_title)
                .setMessage(R.string.add_loopback_body)
                .setView(box)
                .setPositiveButton(R.string.add_connect, (d, which) -> {
                    String raw = input.getText().toString().trim();
                    if (raw.isEmpty()) {
                        showError(getString(R.string.add_error_address));
                        return;
                    }
                    if (raw.startsWith("{") || PairPayload.isPairingLink(raw)) {
                        List<String> parsed = PairPayload.parse(raw);
                        if (parsed.isEmpty()) {
                            showError(getString(R.string.add_error_no_pairing));
                            return;
                        }
                        pair(parsed);
                        return;
                    }
                    String base = ConnectionStore.origin(raw);
                    if (base.isEmpty()) {
                        showError(getString(R.string.add_error_address));
                        return;
                    }
                    if (PairPayload.isLoopback(base)) {
                        showError(getString(R.string.add_error_loopback));
                        return;
                    }
                    String url = PairPayload.withBase(loopbackUrl, base);
                    if (url == null) {
                        showError(getString(R.string.add_error_no_pairing));
                        return;
                    }
                    pair(Collections.singletonList(url));
                })
                .setNegativeButton(R.string.common_cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(t.muted);
    }

    private void manualConnect() {
        if (busy) return;
        String raw = address.getText().toString().trim();
        if (raw.isEmpty()) {
            showError(getString(R.string.add_error_address));
            return;
        }
        if (raw.startsWith("{") || PairPayload.isPairingLink(raw)) {
            List<String> urls = PairPayload.parse(raw);
            if (urls.isEmpty()) {
                showError(getString(R.string.add_error_no_pairing));
                return;
            }
            pair(urls);
            return;
        }
        String base = ConnectionStore.origin(raw);
        if (base.isEmpty()) {
            showError(getString(R.string.add_error_address));
            return;
        }
        if (PairPayload.isLoopback(base)) {
            showError(getString(R.string.add_error_loopback));
            return;
        }
        final String token = password.getText().toString().trim();
        busy = true;
        pairing = false;
        showError(null);
        showOverlay(getString(R.string.add_connecting));
        Bg.run(() -> {
            new ApiClient(base, token).check();
            return base;
        }, ok -> onManualPaired(base, token), this::onFailed);
    }

    private void onPaired(PairResult result) {
        if (isFinishing() || isDestroyed()) return;
        busy = false;
        hideOverlay();
        ConnectionStore.Connection c = new ConnectionStore.Connection();
        c.baseUrl = result.base;
        c.token = result.token;
        String n = name.getText().toString().trim();
        c.name = n.isEmpty() ? Ui.host(result.base) : n;
        c.lastUsed = System.currentTimeMillis();
        store.upsert(c);
        store.setActive(c.id);
        ConnectionStore.setWebCookie(this, result.base, result.token);
        Toast.makeText(this, R.string.add_connected, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void onManualPaired(String base, String token) {
        if (isFinishing() || isDestroyed()) return;
        busy = false;
        hideOverlay();
        ConnectionStore.Connection c = new ConnectionStore.Connection();
        c.baseUrl = base;
        c.token = token;
        String n = name.getText().toString().trim();
        c.name = n.isEmpty() ? Ui.host(base) : n;
        c.lastUsed = System.currentTimeMillis();
        store.upsert(c);
        store.setActive(c.id);
        ConnectionStore.setWebCookie(this, base, token);
        Toast.makeText(this, R.string.add_connected, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void onFailed(Exception e) {
        if (isFinishing() || isDestroyed()) return;
        busy = false;
        hideOverlay();
        showError(messageFor(e));
    }

    private String messageFor(Exception e) {
        if (e instanceof ApiClient.ApiException) {
            int status = ((ApiClient.ApiException) e).status;
            if (pairing && (status == 302 || status == 401 || status == 403
                    || status == 404 || status == 410)) {
                return getString(R.string.add_error_expired);
            }
            if (!pairing && status == 401) return getString(R.string.add_error_password);
            String m = e.getMessage();
            if (m != null && !m.isEmpty()) return m;
        }
        if (e instanceof java.net.UnknownHostException
                || e instanceof java.net.ConnectException
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.NoRouteToHostException) {
            return getString(R.string.add_error_network);
        }
        String m = e == null ? null : e.getMessage();
        if (m != null && !m.isEmpty()) return m;
        return getString(R.string.common_error);
    }

    private void showError(String message) {
        if (message == null || message.isEmpty()) {
            error.setVisibility(View.GONE);
            return;
        }
        error.setText(message);
        error.setVisibility(View.VISIBLE);
    }

    private void showOverlay(String message) {
        overlayText.setText(message);
        overlay.setVisibility(View.VISIBLE);
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
    }

    private void registerBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                        if (!busy) finish();
                    });
        }
    }

    @Override
    public void onBackPressed() {
        if (!busy) super.onBackPressed();
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

    private static final class PairResult {
        final String base;
        final String token;
        PairResult(String base, String token) {
            this.base = base;
            this.token = token;
        }
    }
}
