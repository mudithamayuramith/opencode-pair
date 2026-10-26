package app.opencodepair;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
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
import java.util.Comparator;
import java.util.List;

/**
 * The native shell: Sessions, Connections and Settings, with QR pairing kept as the primary way
 * to add a computer. Chat is a separate activity.
 */
@SuppressLint({"SetTextI18n", "GestureBackNavigation"})
public final class MainActivity extends Activity {
    private static final int TAB_SESSIONS = 0;
    private static final int TAB_CONNECTIONS = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int REQ_ADD = 40;
    private static final int REQ_CHAT = 41;

    private Theme t;
    private ConnectionStore store;
    private FrameLayout root;
    private FrameLayout content;
    private LinearLayout nav;
    private LinearLayout shell;
    private final ImageView[] navIcons = new ImageView[3];
    private final TextView[] navLabels = new TextView[3];

    private int tab = TAB_SESSIONS;
    private List<SessionInfo> sessions = new ArrayList<>();
    private String sessionsError = "";
    private boolean loadingSessions;
    private long lastSessionsLoad;
    private String renderedActiveId = "";
    private boolean everResumed;
    private long lastThemeDark = 0;
    private AlertDialog progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        t = Theme.get(this);
        lastThemeDark = t.dark ? 1 : 0;
        store = new ConnectionStore(this);
        store.migrateLegacy();
        if (savedInstanceState != null) {
            tab = savedInstanceState.getInt("tab", TAB_SESSIONS);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        setContentView(root);
        Ui.systemBars(this, t);
        applyInsets(root);

        shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new FrameLayout(this);
        shell.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        nav = buildNav();
        shell.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(shell);
        selectTab(tab, true);
        registerBack();
    }

    // ---------- Navigation ----------

    private LinearLayout buildNav() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(t.navBg);

        View border = new View(this);
        border.setBackgroundColor(t.border);
        wrap.addView(border, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)));

        int[] icons = {R.drawable.ic_sessions, R.drawable.ic_connections, R.drawable.ic_settings};
        int[] labels = {R.string.tab_sessions, R.string.tab_connections, R.string.tab_settings};

        for (int i = 0; i < 3; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            item.setBackground(Ui.ripple(this, null, t.ripple));
            item.setOnClickListener(v -> selectTab(index, false));

            ImageView icon = Ui.icon(this, icons[i], t.dim, 22);
            item.addView(icon);
            navIcons[i] = icon;

            TextView label = Ui.text(this, getString(labels[i]), 10.5f, t.dim, false);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = Ui.dp(this, 3);
            item.addView(label, llp);
            navLabels[i] = label;

            row.addView(item);
        }

        wrap.addView(row);
        return wrap;
    }

    private void updateNav() {
        for (int i = 0; i < 3; i++) {
            boolean on = i == tab;
            int color = on ? t.text : t.dim;
            if (navIcons[i] != null) navIcons[i].setColorFilter(color);
            if (navLabels[i] != null) {
                navLabels[i].setTextColor(on ? t.text : t.dim);
                navLabels[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
    }

    private void selectTab(int which, boolean forceRefresh) {
        tab = which;
        rebuild();
        updateNav();
        if (which == TAB_SESSIONS) {
            refreshSessions(forceRefresh || sessions.isEmpty());
        }
    }

    private void rebuild() {
        if (content == null) return;
        content.removeAllViews();
        View page;
        if (tab == TAB_CONNECTIONS) page = connectionsPage();
        else if (tab == TAB_SETTINGS) page = settingsPage();
        else page = sessionsPage();
        content.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ConnectionStore.Connection active = store.active();
        renderedActiveId = active == null ? "" : active.id;
    }

    @Override
    protected void onResume() {
        super.onResume();
        Theme now = Theme.get(this);
        int dark = now.dark ? 1 : 0;
        if (dark != lastThemeDark) {
            recreate();
            return;
        }
        if (!everResumed) {
            everResumed = true;
            return;
        }
        ConnectionStore.Connection active = store.active();
        String id = active == null ? "" : active.id;
        if (!id.equals(renderedActiveId)) {
            selectTab(tab, true);
            return;
        }
        if (tab == TAB_SESSIONS) refreshSessions(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("tab", tab);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADD && resultCode == RESULT_OK) {
            sessions.clear();
            lastSessionsLoad = 0;
            selectTab(TAB_SESSIONS, true);
        } else if (requestCode == REQ_CHAT) {
            refreshSessions(true);
        }
    }

    // ---------- Sessions ----------

    private View sessionsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(t.bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 6));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = Ui.text(this, getString(R.string.sessions_title), 28f, t.text, true);
        title.setLetterSpacing(-0.02f);
        titles.addView(title);

        ConnectionStore.Connection active = store.active();
        LinearLayout chip = connectionChip(active);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = Ui.dp(this, 8);
        titles.addView(chip, chipLp);
        header.addView(titles);

        ImageButton refresh = Ui.iconButton(this, R.drawable.ic_refresh, t.muted, R.string.cd_refresh, t);
        refresh.setOnClickListener(v -> refreshSessions(true));
        header.addView(refresh);

        ImageButton add = Ui.iconButton(this, R.drawable.ic_add, t.text, R.string.cd_add, t);
        add.setOnClickListener(v -> {
            if (store.active() == null) startAdd();
            else newSession();
        });
        header.addView(add);

        page.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 28));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (store.active() == null) {
            body.addView(emptyState(R.drawable.ic_connections,
                    getString(R.string.sessions_no_connection_title),
                    getString(R.string.sessions_no_connection_body),
                    getString(R.string.connections_add), this::startAdd));
            return page;
        }

        if (loadingSessions && sessions.isEmpty()) {
            LinearLayout box = new LinearLayout(this);
            box.setGravity(Gravity.CENTER);
            box.setPadding(0, Ui.dp(this, 48), 0, 0);
            ProgressBar p = new ProgressBar(this);
            box.addView(p);
            body.addView(box);
            return page;
        }

        if (!sessionsError.isEmpty() && sessions.isEmpty()) {
            body.addView(errorCard(sessionsError, () -> refreshSessions(true)));
            return page;
        }

        if (!sessionsError.isEmpty()) {
            body.addView(errorCard(sessionsError, () -> refreshSessions(true)));
            body.addView(space(10));
        }

        if (sessions.isEmpty()) {
            body.addView(emptyState(R.drawable.ic_sessions,
                    getString(R.string.sessions_empty_title),
                    getString(R.string.sessions_empty_body),
                    getString(R.string.sessions_new), this::newSession));
            return page;
        }

        for (SessionInfo s : sessions) {
            body.addView(sessionCard(s));
            body.addView(space(10));
        }
        return page;
    }

    private LinearLayout connectionChip(ConnectionStore.Connection c) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        chip.setBackground(Ui.ripple(this,
                Ui.round(this, t.surface, 999, t.border, 1), t.ripple));
        chip.setClickable(true);
        chip.setFocusable(true);

        View dot = new View(this);
        dot.setBackground(Ui.round(this, c == null ? t.dim : t.ok, 999));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(this, 7), Ui.dp(this, 7));
        dlp.setMarginEnd(Ui.dp(this, 7));
        chip.addView(dot, dlp);

        TextView name = Ui.text(this,
                c == null ? getString(R.string.connections_add) : c.name,
                12.5f, t.text, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setMaxWidth(Ui.dp(this, 200));
        chip.addView(name);

        ImageView chevron = Ui.icon(this, R.drawable.ic_chevron, t.dim, 14);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                Ui.dp(this, 14), Ui.dp(this, 14));
        clp.setMarginStart(Ui.dp(this, 2));
        chip.addView(chevron, clp);

        chip.setOnClickListener(v -> {
            if (c == null) startAdd();
            else selectTab(TAB_CONNECTIONS, true);
        });
        return chip;
    }

    private View sessionCard(SessionInfo s) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.ripple(this,
                Ui.round(this, t.surface, 14, t.border, 1), t.ripple));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14),
                Ui.dp(this, 16), Ui.dp(this, 14));
        card.setClickable(true);
        card.setFocusable(true);

        TextView title = Ui.text(this, s.displayTitle(), 16f, t.text, true);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 1.2f);
        card.addView(title);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = Ui.dp(this, 9);
        card.addView(meta, metaLp);

        ImageView folder = Ui.icon(this, R.drawable.ic_folder, t.dim, 14);
        meta.addView(folder);

        TextView dir = Ui.text(this,
                s.directory == null || s.directory.isEmpty()
                        ? getString(R.string.sessions_default_folder)
                        : Ui.shortDir(s.directory),
                12f, t.muted, false);
        dir.setMaxLines(1);
        dir.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams dirLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dirLp.setMarginStart(Ui.dp(this, 6));
        meta.addView(dir, dirLp);

        TextView time = Ui.text(this, Ui.timeAgo(s.updated), 12f, t.muted, false);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timeLp.setMarginStart(Ui.dp(this, 8));
        meta.addView(time, timeLp);

        if (s.modelId != null && !s.modelId.isEmpty()) {
            TextView model = Ui.text(this, Ui.shortModel(s.modelId), 10.5f, t.muted, false);
            model.setBackground(Ui.round(this, t.field, 6));
            model.setPadding(Ui.dp(this, 6), Ui.dp(this, 3),
                    Ui.dp(this, 6), Ui.dp(this, 3));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.setMarginStart(Ui.dp(this, 8));
            meta.addView(model, mlp);
        }

        card.setOnClickListener(v -> openChat(s));
        card.setOnLongClickListener(v -> {
            showSessionMenu(s);
            return true;
        });
        return card;
    }

    private void showSessionMenu(SessionInfo s) {
        String[] items = {getString(R.string.common_open), getString(R.string.sessions_delete)};
        AlertDialog dialog = new AlertDialog.Builder(this, Ui.dialogTheme(t))
                .setTitle(s.displayTitle())
                .setItems(items, (d, which) -> {
                    if (which == 0) openChat(s);
                    else confirmDeleteSession(s);
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(t.muted);
    }

    private void confirmDeleteSession(SessionInfo s) {
        AlertDialog dialog = new AlertDialog.Builder(this, Ui.dialogTheme(t))
                .setTitle(R.string.sessions_delete)
                .setMessage(getString(R.string.sessions_delete_confirm, s.displayTitle()))
                .setPositiveButton(R.string.common_delete, (d, which) -> deleteSession(s))
                .setNegativeButton(R.string.common_cancel, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(t.error);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(t.muted);
    }

    private void deleteSession(SessionInfo s) {
        ConnectionStore.Connection c = store.active();
        if (c == null) return;
        ApiClient api = new ApiClient(c.baseUrl, c.token);
        Bg.run(() -> {
            api.deleteSession(s.id);
            return null;
        }, v -> {
            if (isFinishing() || isDestroyed()) return;
            sessions.remove(s);
            rebuild();
        }, e -> {
            if (isFinishing() || isDestroyed()) return;
            toast(e);
        });
    }

    private void refreshSessions(boolean force) {
        ConnectionStore.Connection c = store.active();
        if (c == null) {
            sessions.clear();
            sessionsError = "";
            loadingSessions = false;
            if (tab == TAB_SESSIONS) rebuild();
            return;
        }
        if (loadingSessions) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastSessionsLoad < 5000) return;

        loadingSessions = true;
        if (sessions.isEmpty() && tab == TAB_SESSIONS) rebuild();

        ApiClient api = new ApiClient(c.baseUrl, c.token);
        Bg.run(() -> api.sessions(60, null), list -> {
            if (isFinishing() || isDestroyed()) return;
            loadingSessions = false;
            lastSessionsLoad = System.currentTimeMillis();
            sessionsError = "";
            List<SessionInfo> top = new ArrayList<>();
            for (SessionInfo s : list) {
                if (!s.isSubagent()) top.add(s);
            }
            Collections.sort(top, new Comparator<SessionInfo>() {
                @Override public int compare(SessionInfo a, SessionInfo b) {
                    return Long.compare(b.updated, a.updated);
                }
            });
            sessions = top;
            if (tab == TAB_SESSIONS) rebuild();
        }, e -> {
            if (isFinishing() || isDestroyed()) return;
            loadingSessions = false;
            lastSessionsLoad = System.currentTimeMillis();
            if (e instanceof ApiClient.ApiException
                    && ((ApiClient.ApiException) e).status == 401) {
                sessionsError = getString(R.string.chat_expired) + " "
                        + getString(R.string.chat_expired_body);
            } else {
                String m = e.getMessage();
                sessionsError = m == null || m.isEmpty()
                        ? getString(R.string.sessions_error_title) : m;
            }
            if (tab == TAB_SESSIONS) rebuild();
        });
    }

    private void newSession() {
        ConnectionStore.Connection c = store.active();
        if (c == null) {
            startAdd();
            return;
        }
        ApiClient api = new ApiClient(c.baseUrl, c.token);
        Bg.run(api::projects, projects -> {
            if (isFinishing() || isDestroyed()) return;
            if (projects.isEmpty()) {
                new AlertDialog.Builder(this, Ui.dialogTheme(t))
                        .setTitle(R.string.sessions_new)
                        .setMessage(R.string.sessions_default_body)
                        .setPositiveButton(R.string.sessions_new,
                                (d, which) -> createSession(null))
                        .setNegativeButton(R.string.common_cancel, null)
                        .show();
                return;
            }
            int n = Math.min(projects.size(), 20);
            String[] labels = new String[n];
            for (int i = 0; i < n; i++) labels[i] = projects.get(i).canonical;
            AlertDialog dialog = new AlertDialog.Builder(this, Ui.dialogTheme(t))
                    .setTitle(R.string.sessions_pick_project)
                    .setItems(labels, (d, which) ->
                            createSession(projects.get(which).canonical))
                    .setNegativeButton(R.string.common_cancel, null)
                    .show();
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(t.muted);
        }, e -> {
            if (isFinishing() || isDestroyed()) return;
            toast(e);
        });
    }

    private void createSession(String directory) {
        ConnectionStore.Connection c = store.active();
        if (c == null) return;
        ApiClient api = new ApiClient(c.baseUrl, c.token);
        // Inherit the most recently used model. Without this the server falls back to its own
        // default, which can be a model the user has never selected (or one that is unavailable).
        SessionInfo seed = null;
        for (SessionInfo s : sessions) {
            if (s.modelId != null && !s.modelId.isEmpty()
                    && s.providerId != null && !s.providerId.isEmpty()) {
                seed = s;
                break;
            }
        }
        final SessionInfo model = seed;
        progress = Ui.progressDialog(this, t, getString(R.string.sessions_creating));
        progress.show();
        Bg.run(() -> api.createSession(directory,
                        model == null ? null : model.providerId,
                        model == null ? null : model.modelId,
                        model == null ? null : model.variant), s -> {
            if (progress != null && progress.isShowing()) progress.dismiss();
            if (isFinishing() || isDestroyed()) return;
            sessions.clear();
            lastSessionsLoad = 0;
            openChat(s);
        }, e -> {
            if (progress != null && progress.isShowing()) progress.dismiss();
            if (isFinishing() || isDestroyed()) return;
            toast(e);
        });
    }

    private void openChat(SessionInfo s) {
        ConnectionStore.Connection c = store.active();
        if (c == null) {
            startAdd();
            return;
        }
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_CONNECTION, c.id);
        i.putExtra(ChatActivity.EXTRA_SESSION, s.id);
        i.putExtra(ChatActivity.EXTRA_TITLE, s.displayTitle());
        i.putExtra(ChatActivity.EXTRA_MODEL, s.modelId);
        startActivityForResult(i, REQ_CHAT);
    }

    // ---------- Connections ----------

    private View connectionsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(t.bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 6));
        TextView title = Ui.text(this, getString(R.string.connections_title), 28f, t.text, true);
        title.setLetterSpacing(-0.02f);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);
        ImageButton add = Ui.iconButton(this, R.drawable.ic_add, t.text, R.string.cd_add, t);
        add.setOnClickListener(v -> startAdd());
        header.addView(add);
        page.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 28));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        List<ConnectionStore.Connection> all = store.list();
        if (all.isEmpty()) {
            body.addView(emptyState(R.drawable.ic_connections,
                    getString(R.string.connections_empty_title),
                    getString(R.string.connections_empty_body),
                    getString(R.string.connections_add), this::startAdd));
        } else {
            ConnectionStore.Connection active = store.active();
            String activeId = active == null ? "" : active.id;
            for (ConnectionStore.Connection c : all) {
                body.addView(connectionCard(c, c.id.equals(activeId)));
                body.addView(space(10));
            }
            TextView addBtn = Ui.button(this, t, getString(R.string.connections_add), false);
            addBtn.setOnClickListener(v -> startAdd());
            LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50));
            addLp.topMargin = Ui.dp(this, 4);
            body.addView(addBtn, addLp);
        }

        LinearLayout how = new LinearLayout(this);
        how.setOrientation(LinearLayout.VERTICAL);
        how.setBackground(Ui.round(this, t.surface, 14, t.border, 1));
        how.setPadding(Ui.dp(this, 16), Ui.dp(this, 16),
                Ui.dp(this, 16), Ui.dp(this, 16));
        TextView howTitle = Ui.label(this, getString(R.string.connections_how_title), t.muted);
        how.addView(howTitle);
        TextView howBody = Ui.text(this, getString(R.string.connections_how_body), 12.5f, t.muted, false);
        howBody.setLineSpacing(0, 1.42f);
        LinearLayout.LayoutParams howLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        howLp.topMargin = Ui.dp(this, 8);
        how.addView(howBody, howLp);
        LinearLayout.LayoutParams howWrap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        howWrap.topMargin = Ui.dp(this, 20);
        body.addView(how, howWrap);

        return page;
    }

    private View connectionCard(ConnectionStore.Connection c, boolean active) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.ripple(this,
                Ui.round(this, t.surface, 14, active ? t.text : t.border, active ? 1.5f : 1f),
                t.ripple));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14),
                Ui.dp(this, 16), Ui.dp(this, 14));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.text(this, c.name, 16f, t.text, true);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name);

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        if (active) {
            View dot = new View(this);
            dot.setBackground(Ui.round(this, t.ok, 999));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 6), Ui.dp(this, 6));
            dlp.setMarginEnd(Ui.dp(this, 5));
            status.addView(dot, dlp);
        }
        TextView state = Ui.text(this,
                getString(active ? R.string.connections_active : R.string.connections_tap_to_use),
                11.5f, active ? t.ok : t.dim, false);
        status.addView(state);
        top.addView(status);
        card.addView(top);

        TextView host = Ui.text(this, c.host(), 12.5f, t.muted, false);
        host.setMaxLines(1);
        host.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hostLp.topMargin = Ui.dp(this, 6);
        card.addView(host, hostLp);

        card.setOnClickListener(v -> activateConnection(c));
        card.setOnLongClickListener(v -> {
            showConnectionMenu(c);
            return true;
        });
        return card;
    }

    private void activateConnection(ConnectionStore.Connection c) {
        ConnectionStore.Connection prev = store.active();
        if (prev == null || !prev.id.equals(c.id)) {
            sessions.clear();
            lastSessionsLoad = 0;
        }
        store.setActive(c.id);
        selectTab(TAB_SESSIONS, true);
    }

    private void showConnectionMenu(ConnectionStore.Connection c) {
        String[] items = {
                getString(R.string.connections_rename),
                getString(R.string.connections_delete)
        };
        AlertDialog dialog = new AlertDialog.Builder(this, Ui.dialogTheme(t))
                .setTitle(c.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) renameConnection(c);
                    else confirmRemoveConnection(c);
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(t.muted);
    }

    private void renameConnection(ConnectionStore.Connection c) {
        EditText input = Ui.field(this, t, getString(R.string.connections_name_hint),
                android.text.InputType.TYPE_CLASS_TEXT, false);
        input.setText(c.name);
        input.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this, Ui.dialogTheme(t))
                .setTitle(R.string.connections_rename)
                .setView(box)
                .setPositiveButton(R.string.common_save, (d, which) -> {
                    store.rename(c.id, input.getText().toString());
                    rebuild();
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void confirmRemoveConnection(ConnectionStore.Connection c) {
        AlertDialog dialog = new AlertDialog.Builder(this, Ui.dialogTheme(t))
                .setTitle(R.string.connections_delete)
                .setMessage(getString(R.string.connections_delete_confirm, c.name))
                .setPositiveButton(R.string.common_remove, (d, which) -> {
                    if (c.id.equals(store.active() == null ? "" : store.active().id)) {
                        sessions.clear();
                        lastSessionsLoad = 0;
                    }
                    store.remove(c.id);
                    selectTab(tab, true);
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(t.error);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(t.muted);
    }

    // ---------- Settings ----------

    private View settingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(t.bg);

        LinearLayout header = new LinearLayout(this);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 6));
        TextView title = Ui.text(this, getString(R.string.settings_title), 28f, t.text, true);
        title.setLetterSpacing(-0.02f);
        header.addView(title);
        page.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 32));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(Ui.label(this, getString(R.string.settings_connection), t.muted));
        body.addView(space(8));

        ConnectionStore.Connection active = store.active();
        if (active == null) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.round(this, t.surface, 14, t.border, 1));
            card.setPadding(Ui.dp(this, 16), Ui.dp(this, 16),
                    Ui.dp(this, 16), Ui.dp(this, 16));
            TextView none = Ui.text(this, getString(R.string.settings_no_connection), 15f, t.text, true);
            card.addView(none);
            TextView hint = Ui.text(this, getString(R.string.connections_empty_body), 12.5f, t.muted, false);
            hint.setLineSpacing(0, 1.4f);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = Ui.dp(this, 6);
            card.addView(hint, hlp);
            body.addView(card);
            TextView pair = Ui.button(this, t, getString(R.string.settings_pair), true);
            pair.setOnClickListener(v -> startAdd());
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50));
            plp.topMargin = Ui.dp(this, 12);
            body.addView(pair, plp);
        } else {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.round(this, t.surface, 14, t.border, 1));
            card.setPadding(Ui.dp(this, 16), Ui.dp(this, 16),
                    Ui.dp(this, 16), Ui.dp(this, 16));
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = Ui.text(this, active.name, 15f, t.text, true);
            name.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(name);
            View dot = new View(this);
            dot.setBackground(Ui.round(this, t.ok, 999));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 6), Ui.dp(this, 6));
            dlp.setMarginEnd(Ui.dp(this, 5));
            top.addView(dot, dlp);
            top.addView(Ui.text(this, getString(R.string.connections_active),
                    11.5f, t.ok, false));
            card.addView(top);
            TextView host = Ui.text(this, active.host(), 12.5f, t.muted, false);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = Ui.dp(this, 6);
            card.addView(host, hlp);
            body.addView(card);

            TextView pair = Ui.button(this, t, getString(R.string.settings_pair_new), false);
            pair.setOnClickListener(v -> startAdd());
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
            plp.topMargin = Ui.dp(this, 12);
            body.addView(pair, plp);

            TextView web = Ui.button(this, t, getString(R.string.settings_web), false);
            web.setOnClickListener(v -> openWeb());
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
            wlp.topMargin = Ui.dp(this, 8);
            body.addView(web, wlp);

            TextView remove = Ui.dangerButton(this, t, getString(R.string.settings_remove));
            remove.setOnClickListener(v -> confirmRemoveConnection(active));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Ui.dp(this, 8);
            body.addView(remove, rlp);
        }

        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.topMargin = Ui.dp(this, 26);
        body.addView(Ui.label(this, getString(R.string.settings_appearance), t.muted), sectionLp);
        body.addView(space(8));
        body.addView(themeSegments());

        LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aboutLp.topMargin = Ui.dp(this, 26);
        body.addView(Ui.label(this, getString(R.string.settings_about), t.muted), aboutLp);
        body.addView(space(8));
        body.addView(aboutCard());

        return page;
    }

    private View themeSegments() {
        String mode = Theme.mode(this);
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(Ui.round(this, t.field, 12, t.border, 1));
        seg.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));

        String[][] options = {
                {Theme.MODE_SYSTEM, getString(R.string.settings_system)},
                {Theme.MODE_LIGHT, getString(R.string.settings_light)},
                {Theme.MODE_DARK, getString(R.string.settings_dark)}
        };
        for (String[] option : options) {
            boolean on = option[0].equals(mode);
            TextView b = Ui.text(this, option[1], 13f, on ? t.ctaText : t.muted, on);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            b.setBackground(Ui.round(this, on ? t.cta : 0, 9));
            b.setClickable(true);
            b.setFocusable(true);
            b.setOnClickListener(v -> {
                Theme.setMode(this, option[0]);
                recreate();
            });
            seg.addView(b, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return seg;
    }

    private View aboutCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, t.surface, 14, t.border, 1));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 16),
                Ui.dp(this, 16), Ui.dp(this, 16));

        TextView name = Ui.text(this, getString(R.string.app_name), 15f, t.text, true);
        card.addView(name);
        String version = "1.0";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        TextView ver = Ui.text(this, getString(R.string.settings_version, version),
                12f, t.muted, false);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = Ui.dp(this, 4);
        card.addView(ver, vlp);

        TextView body = Ui.text(this, getString(R.string.settings_about_body), 12.5f, t.muted, false);
        body.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 10);
        card.addView(body, blp);

        TextView disclaimer = Ui.text(this, getString(R.string.settings_disclaimer),
                11f, t.dim, false);
        disclaimer.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(this, 12);
        card.addView(disclaimer, dlp);
        return card;
    }

    // ---------- Shared helpers ----------

    private View emptyState(int iconRes, String title, String body,
                            String buttonLabel, Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 44),
                Ui.dp(this, 16), Ui.dp(this, 24));

        ImageView icon = Ui.icon(this, iconRes, t.dim, 46);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(this, 46), Ui.dp(this, 46));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(icon, ilp);

        TextView t1 = Ui.text(this, title, 19f, t.text, true);
        t1.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 18);
        box.addView(t1, tlp);

        TextView t2 = Ui.text(this, body, 13f, t.muted, false);
        t2.setGravity(Gravity.CENTER);
        t2.setLineSpacing(0, 1.45f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        blp.leftMargin = Ui.dp(this, 8);
        blp.rightMargin = Ui.dp(this, 8);
        box.addView(t2, blp);

        TextView button = Ui.button(this, t, buttonLabel, true);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
        btnLp.gravity = Gravity.CENTER_HORIZONTAL;
        btnLp.topMargin = Ui.dp(this, 22);
        box.addView(button, btnLp);
        return box;
    }

    private View errorCard(String message, Runnable retry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, t.surface, 14, t.border, 1));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 16),
                Ui.dp(this, 16), Ui.dp(this, 16));

        TextView title = Ui.text(this, getString(R.string.sessions_error_title), 15f, t.text, true);
        card.addView(title);
        TextView body = Ui.text(this, message, 12.5f, t.muted, false);
        body.setLineSpacing(0, 1.4f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 6);
        card.addView(body, blp);

        TextView retryBtn = Ui.button(this, t, getString(R.string.common_retry), false);
        retryBtn.setOnClickListener(v -> retry.run());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 42));
        rlp.topMargin = Ui.dp(this, 12);
        card.addView(retryBtn, rlp);
        return card;
    }

    private View space(int dp) {
        return Ui.spacer(this, dp);
    }

    private void startAdd() {
        startActivityForResult(new Intent(this, AddConnectionActivity.class), REQ_ADD);
    }

    private void openWeb() {
        ConnectionStore.Connection c = store.active();
        if (c == null) {
            startAdd();
            return;
        }
        ConnectionStore.setWebCookie(this, c.baseUrl, c.token);
        Intent i = new Intent(this, WebActivity.class);
        i.putExtra(WebActivity.EXTRA_CONNECTION, c.id);
        startActivity(i);
    }

    private void toast(Exception e) {
        String msg = e == null ? null : e.getMessage();
        if (msg == null || msg.isEmpty()) msg = getString(R.string.common_error);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void registerBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    private void handleBack() {
        if (tab != TAB_SESSIONS) {
            selectTab(TAB_SESSIONS, false);
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBack();
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
        if (progress != null && progress.isShowing()) progress.dismiss();
        super.onDestroy();
    }
}
