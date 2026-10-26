package app.opencodepair;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native chat. Polls the message list (the API has no lightweight delta stream) and renders the
 * parts the agent produces: text, reasoning and tool calls, plus permission prompts.
 */
@SuppressLint({"SetTextI18n", "GestureBackNavigation"})
public final class ChatActivity extends Activity {
    public static final String EXTRA_CONNECTION = "connection_id";
    public static final String EXTRA_SESSION = "session_id";
    public static final String EXTRA_TITLE = "session_title";
    public static final String EXTRA_MODEL = "model_id";

    private static final int REQ_PAIR = 70;

    private Theme t;
    private ConnectionStore.Connection conn;
    private String sessionId = "";
    private String sessionTitle = "";
    private String currentModelId = "";
    private String modelLabel = "";
    private String host = "";
    private ApiClient api;

    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout messagesCol;
    private LinearLayout permissionWrap;
    private LinearLayout permissionCard;
    private EditText input;
    private ImageButton send;
    private TextView modelChip;
    private TextView subtitle;
    private TextView status;

    private final LinkedHashMap<String, ChatMessage> messages = new LinkedHashMap<>();
    private final Map<String, View> messageViews = new HashMap<>();
    private final Map<String, String> messageSignatures = new HashMap<>();
    private final Set<String> expandedReasoning = new HashSet<>();
    private final Set<String> expandedTools = new HashSet<>();
    private View workingRow;
    private boolean forceFullRender;
    private boolean lastBusy;
    private String lastStructure = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable poller = new Runnable() {
        @Override public void run() {
            if (!destroyed && polling) poll();
        }
    };

    private boolean polling;
    private boolean destroyed;
    private boolean loading;
    private boolean initialLoaded;
    private boolean busy;
    private boolean networkError;
    private boolean expired;
    private String loadError = "";
    private String oldestCursor;
    private String lastSignature = "";
    private boolean loadingOlder;
    private List<ServerModel> models;
    private boolean modelsRequested;
    private boolean modelPinned;
    private PermissionRequest pendingPermission;
    private int permissionCount;
    private long lastPollAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        t = Theme.get(this);

        String connectionId = getIntent().getStringExtra(EXTRA_CONNECTION);
        conn = new ConnectionStore(this).byId(connectionId);
        sessionId = getIntent().getStringExtra(EXTRA_SESSION);
        sessionTitle = getIntent().getStringExtra(EXTRA_TITLE);
        currentModelId = getIntent().getStringExtra(EXTRA_MODEL);
        if (conn == null || sessionId == null || sessionId.isEmpty()) {
            Toast.makeText(this, R.string.chat_session_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (sessionTitle == null || sessionTitle.isEmpty()) sessionTitle = "Session";
        modelLabel = Ui.shortModel(currentModelId);
        host = Ui.host(conn.baseUrl);
        api = new ApiClient(conn.baseUrl, conn.token);

        root = new FrameLayout(this);
        root.setBackgroundColor(t.bg);
        setContentView(root);
        Ui.systemBars(this, t);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));
        page.addView(Ui.divider(this, t.border));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(t.bg);
        messagesCol = new LinearLayout(this);
        messagesCol.setOrientation(LinearLayout.VERTICAL);
        messagesCol.setPadding(Ui.dp(this, 16), Ui.dp(this, 6),
                Ui.dp(this, 16), Ui.dp(this, 14));
        scroll.addView(messagesCol, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        permissionWrap = new LinearLayout(this);
        permissionWrap.setOrientation(LinearLayout.VERTICAL);
        permissionWrap.setBackgroundColor(t.surface);
        permissionWrap.setVisibility(View.GONE);
        permissionWrap.addView(Ui.divider(this, t.border));
        permissionCard = new LinearLayout(this);
        permissionCard.setOrientation(LinearLayout.VERTICAL);
        permissionCard.setPadding(Ui.dp(this, 14), Ui.dp(this, 12),
                Ui.dp(this, 14), Ui.dp(this, 12));
        permissionWrap.addView(permissionCard);
        page.addView(permissionWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(Ui.divider(this, t.border));
        page.addView(buildComposer(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(page);
        applyInsets(root);
        registerBack();

        loading = true;
        initialLoaded = false;
        renderMessages();
        loadModels();
        loadInitial();
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(t.bg);
        bar.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);

        ImageButton back = Ui.iconButton(this, R.drawable.ic_back, t.text, R.string.cd_back, t);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView title = Ui.text(this, sessionTitle, 15.5f, t.text, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(title);

        subtitle = Ui.text(this, subtitleText(), 11f, t.muted, false);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(subtitle);
        bar.addView(titles);

        ImageButton more = Ui.iconButton(this, R.drawable.ic_more, t.text, R.string.cd_more, t);
        more.setOnClickListener(this::showMoreMenu);
        bar.addView(more);
        return bar;
    }

    private View buildComposer() {
        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setBackgroundColor(t.surface);
        composer.setPadding(Ui.dp(this, 12), Ui.dp(this, 8),
                Ui.dp(this, 12), Ui.dp(this, 10));

        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(Ui.ripple(this, Ui.round(this, t.field, 999), t.ripple));
        chip.setPadding(Ui.dp(this, 10), Ui.dp(this, 5),
                Ui.dp(this, 8), Ui.dp(this, 5));
        chip.setClickable(true);
        chip.setFocusable(true);

        modelChip = Ui.text(this, modelText(), 11.5f, t.muted, true);
        modelChip.setMaxLines(1);
        modelChip.setEllipsize(TextUtils.TruncateAt.END);
        chip.addView(modelChip);
        ImageView chevron = Ui.icon(this, R.drawable.ic_chevron, t.dim, 12);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                Ui.dp(this, 12), Ui.dp(this, 12));
        clp.setMarginStart(Ui.dp(this, 2));
        chip.addView(chevron, clp);
        chip.setOnClickListener(v -> pickModel());
        modelRow.addView(chip);

        modelRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        status = Ui.text(this, "", 11f, t.muted, false);
        status.setVisibility(View.GONE);
        modelRow.addView(status);
        composer.addView(modelRow);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Ui.dp(this, 8);

        input = Ui.field(this, t, getString(R.string.chat_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE, true);
        input.setMinHeight(Ui.dp(this, 44));
        input.setMaxHeight(Ui.dp(this, 132));
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 11),
                Ui.dp(this, 14), Ui.dp(this, 11));
        inputRow.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        send = new ImageButton(this);
        send.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        send.setPadding(Ui.dp(this, 11), Ui.dp(this, 11),
                Ui.dp(this, 11), Ui.dp(this, 11));
        send.setOnClickListener(v -> {
            if (busy) interrupt();
            else send();
        });
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 44), Ui.dp(this, 44));
        sendLp.setMarginStart(Ui.dp(this, 8));
        inputRow.addView(send, sendLp);
        composer.addView(inputRow, rowLp);
        updateComposer();
        return composer;
    }

    // ---------- Data ----------

    private void loadInitial() {
        loading = true;
        boolean firstLoad = messages.isEmpty();
        if (firstLoad) initialLoaded = false;
        loadError = "";
        expired = false;
        forceFullRender = true;
        if (firstLoad) renderMessages();
        Bg.run(() -> api.messages(sessionId, 60, "desc", null), page -> {
            if (destroyed) return;
            loading = false;
            initialLoaded = true;
            networkError = false;
            messages.clear();
            List<ChatMessage> list = new ArrayList<>(page.data);
            Collections.reverse(list);
            for (ChatMessage m : list) messages.put(m.id, m);
            oldestCursor = page.nextCursor;
            busy = computeBusy();
            forceFullRender = true;
            lastSignature = "";
            renderMessages();
            if (polling) schedule(300);
        }, e -> {
            if (destroyed) return;
            loading = false;
            initialLoaded = true;
            if (e instanceof ApiClient.ApiException
                    && ((ApiClient.ApiException) e).status == 401) {
                expired = true;
                busy = false;
                loadError = getString(R.string.chat_expired) + "\n"
                        + getString(R.string.chat_expired_body);
            } else {
                String m = e.getMessage();
                loadError = m == null || m.isEmpty() ? getString(R.string.common_error) : m;
                networkError = !messages.isEmpty();
            }
            renderMessages();
            if (polling && !expired) schedule(6000);
        });
    }

    private void poll() {
        if (destroyed || !polling || loading || expired) return;
        loading = true;
        lastPollAt = System.currentTimeMillis();
        Bg.run(() -> {
            ApiClient.MessagePage page = api.messages(sessionId, 30, "desc", null);
            Set<String> active = null;
            try { active = api.activeSessions(); } catch (Exception ignored) { }
            List<PermissionRequest> perms = null;
            try { perms = api.permissions(sessionId); } catch (Exception ignored) { }
            return new Poll(page, active, perms);
        }, this::onPoll, this::onPollError);
    }

    private void onPoll(Poll r) {
        if (destroyed) return;
        loading = false;
        networkError = false;
        loadError = "";

        List<ChatMessage> incoming = new ArrayList<>(r.page.data);
        Collections.reverse(incoming);
        // If the latest page does not overlap anything already on screen, a burst of messages
        // (or a long pause) pushed the transcript past the 30-message window. Reload the latest
        // 60 instead of silently leaving a hole in the conversation.
        if (r.page.nextCursor != null && pollMissedTail(incoming)) {
            loadInitial();
            return;
        }
        // If the first load failed and a later poll is the first thing to succeed, this page is
        // also the only source for the "load older" cursor.
        if (messages.isEmpty()) oldestCursor = r.page.nextCursor;
        for (ChatMessage m : incoming) {
            ChatMessage old = messages.get(m.id);
            if (old == null || !old.signature().equals(m.signature())) {
                messages.put(m.id, m);
            }
        }
        dropOptimistic(incoming);

        boolean active = r.active != null && r.active.contains(sessionId);
        busy = active || computeBusy();
        updateCurrentModel();

        if (r.permissions != null) updatePermissions(r.permissions);
        else updatePermissionVisibility();

        String sig = signature();
        if (!sig.equals(lastSignature)) renderMessages();
        else updateComposer();
        schedule(busy ? 1300 : 3600);
    }

    private void onPollError(Exception e) {
        if (destroyed) return;
        loading = false;
        if (e instanceof ApiClient.ApiException
                && ((ApiClient.ApiException) e).status == 401) {
            expired = true;
            busy = false;
            polling = false;
            loadError = getString(R.string.chat_expired) + "\n"
                    + getString(R.string.chat_expired_body);
            renderMessages();
            return;
        }
        networkError = true;
        if (messages.isEmpty()) {
            String m = e.getMessage();
            loadError = m == null || m.isEmpty() ? getString(R.string.common_error) : m;
            initialLoaded = true;
            renderMessages();
        } else {
            updateComposer();
        }
        schedule(6000);
    }

    private boolean pollMissedTail(List<ChatMessage> incoming) {
        if (incoming.isEmpty() || messages.isEmpty()) return false;
        String tailId = null;
        for (ChatMessage m : messages.values()) {
            if (!m.optimistic) tailId = m.id;
        }
        if (tailId == null) return false;
        for (ChatMessage m : incoming) {
            if (tailId.equals(m.id)) return false;
        }
        return true;
    }

    private void dropOptimistic(List<ChatMessage> incoming) {
        List<String> remove = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (ChatMessage m : messages.values()) {
            if (!m.optimistic) continue;
            boolean matched = false;
            for (ChatMessage s : incoming) {
                if (s.optimistic) continue;
                if (s.kind == ChatMessage.TYPE_USER && s.text.equals(m.text)
                        && s.created >= m.created - 5000) {
                    matched = true;
                    break;
                }
            }
            if (matched || now - m.created > 30000) remove.add(m.id);
        }
        for (String id : remove) messages.remove(id);
    }

    private boolean computeBusy() {
        ChatMessage last = null;
        for (ChatMessage m : messages.values()) last = m;
        if (last == null) return false;
        if (last.kind == ChatMessage.TYPE_USER) return true;
        if (last.kind == ChatMessage.TYPE_ASSISTANT) return last.completed == 0 || last.isBusy();
        if (last.kind == ChatMessage.TYPE_IDLE) return false;
        return last.isBusy();
    }

    private boolean hasStreamingAssistant() {
        for (ChatMessage m : messages.values()) {
            if (m.kind == ChatMessage.TYPE_ASSISTANT && m.completed == 0) return true;
        }
        return false;
    }

    private void updateCurrentModel() {
        // Once the user picks a model, older assistant turns must not drag the chip back to the
        // model they used. The next turn uses the pinned model.
        if (modelPinned) return;
        ChatMessage last = null;
        for (ChatMessage m : messages.values()) {
            if (m.kind == ChatMessage.TYPE_ASSISTANT && m.modelId != null && !m.modelId.isEmpty()) {
                last = m;
            }
        }
        if (last == null) return;
        if (!last.modelId.equals(currentModelId)) {
            currentModelId = last.modelId;
            modelLabel = displayModel(currentModelId);
            updateComposer();
        }
    }

    private String signature() {
        StringBuilder sb = new StringBuilder();
        sb.append(loading).append('|').append(initialLoaded).append('|')
                .append(busy).append('|').append(oldestCursor).append('|')
                .append(loadError).append('|');
        for (ChatMessage m : messages.values()) sb.append(m.signature()).append(';');
        return sb.toString();
    }

    private void schedule(long delay) {
        ui.removeCallbacks(poller);
        if (polling && !destroyed) ui.postDelayed(poller, delay);
    }

    // ---------- Rendering ----------

    private void renderMessages() {
        if (messagesCol == null) return;
        int oldY = scroll.getScrollY();
        boolean wasBottom = isAtBottom();

        String structure = structureSignature();
        boolean busyChanged = busy != lastBusy;
        if (forceFullRender || busyChanged || !structure.equals(lastStructure)
                || messagesCol.getChildCount() == 0) {
            rebuildMessages();
        } else {
            updateMessageViews();
        }
        forceFullRender = false;
        lastBusy = busy;
        lastStructure = structure;

        lastSignature = signature();
        updateComposer();
        ui.post(() -> {
            if (scroll == null) return;
            if (wasBottom) scroll.fullScroll(View.FOCUS_DOWN);
            else scroll.scrollTo(0, oldY);
        });
    }

    /** Global state that changes which children the message column contains, not their content. */
    private String structureSignature() {
        return (loading && !initialLoaded) + "|" + initialLoaded + "|"
                + messages.isEmpty() + "|" + (loadError == null ? "" : loadError) + "|"
                + (oldestCursor == null ? "" : oldestCursor);
    }

    private void rebuildMessages() {
        messagesCol.removeAllViews();
        messageViews.clear();
        messageSignatures.clear();
        workingRow = null;

        if (!loadError.isEmpty() && messages.isEmpty()) {
            addErrorState();
            return;
        }
        if (loading && !initialLoaded) {
            addLoadingState();
            return;
        }
        if (messages.isEmpty()) {
            addEmptyState();
            return;
        }
        if (oldestCursor != null && !oldestCursor.isEmpty()) addLoadOlder();
        for (ChatMessage m : messages.values()) {
            View v = renderMessage(m);
            if (v != null) {
                messagesCol.addView(v);
                messageViews.put(m.id, v);
            }
            messageSignatures.put(m.id, m.signature());
        }
        if (busy && !hasStreamingAssistant()) addWorkingRow();
        if (expired) addErrorState();
    }

    /**
     * Polls normally change only the tail of the conversation. Replace just the views whose
     * message payload changed instead of inflating the whole transcript on every tick.
     */
    private void updateMessageViews() {
        List<String> gone = new ArrayList<>();
        for (String id : messageViews.keySet()) {
            if (!messages.containsKey(id)) gone.add(id);
        }
        for (String id : gone) {
            View v = messageViews.remove(id);
            if (v != null) messagesCol.removeView(v);
            messageSignatures.remove(id);
        }

        for (ChatMessage m : messages.values()) {
            View existing = messageViews.get(m.id);
            String sig = m.signature();
            if (existing != null && sig.equals(messageSignatures.get(m.id))) continue;
            if (existing == null && sig.equals(messageSignatures.get(m.id))) continue;

            int index = existing == null ? -1 : messagesCol.indexOfChild(existing);
            ViewGroup.LayoutParams lp = existing == null ? null : existing.getLayoutParams();
            if (existing != null) messagesCol.removeViewAt(index);

            View v = renderMessage(m);
            if (v != null) {
                if (v.getLayoutParams() == null && lp != null) v.setLayoutParams(lp);
                if (index < 0) {
                    int at = messagesCol.getChildCount();
                    if (workingRow != null && workingRow.getParent() == messagesCol) {
                        at = messagesCol.indexOfChild(workingRow);
                    }
                    messagesCol.addView(v, at);
                } else {
                    messagesCol.addView(v, index);
                }
                messageViews.put(m.id, v);
            } else {
                messageViews.remove(m.id);
            }
            messageSignatures.put(m.id, sig);
        }

        if (busy && !hasStreamingAssistant()) {
            if (workingRow == null) addWorkingRow();
        } else if (workingRow != null) {
            messagesCol.removeView(workingRow);
            workingRow = null;
        }
    }

    private View renderMessage(ChatMessage m) {
        switch (m.kind) {
            case ChatMessage.TYPE_USER: return renderUser(m);
            case ChatMessage.TYPE_ASSISTANT: return renderAssistant(m);
            case ChatMessage.TYPE_SHELL: return renderShell(m);
            case ChatMessage.TYPE_COMPACTION: return renderCompaction();
            case ChatMessage.TYPE_IDLE: return renderIdle(m);
            default: return null;
        }
    }

    private View renderUser(ChatMessage m) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = Ui.dp(this, 16);

        TextView bubble = Ui.text(this, "", 15f, t.text, false);
        bubble.setText(Markdown.inline(this, m.text, t));
        bubble.setLineSpacing(0, 1.36f);
        bubble.setTextIsSelectable(true);
        bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.82f));
        bubble.setBackground(Ui.round(this, t.surfaceAlt, 18, t.border, 1));
        bubble.setPadding(Ui.dp(this, 14), Ui.dp(this, 10),
                Ui.dp(this, 14), Ui.dp(this, 10));
        row.addView(bubble);
        row.setLayoutParams(rowLp);
        return row;
    }

    private View renderAssistant(ChatMessage m) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blockLp.topMargin = Ui.dp(this, 18);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView who = Ui.label(this, "Assistant", t.dim);
        head.addView(who);
        String model = m.modelId == null || m.modelId.isEmpty() ? "" : Ui.shortModel(m.modelId);
        if (!model.isEmpty()) {
            TextView chip = Ui.text(this, model, 10f, t.dim, false);
            chip.setBackground(Ui.round(this, t.field, 6));
            chip.setPadding(Ui.dp(this, 6), Ui.dp(this, 2),
                    Ui.dp(this, 6), Ui.dp(this, 2));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMarginStart(Ui.dp(this, 8));
            head.addView(chip, clp);
        }
        block.addView(head);

        boolean any = false;
        int partIndex = 0;
        for (ChatMessage.Part p : m.parts) {
            if (p.kind == ChatMessage.PART_TEXT && p.text != null && !p.text.trim().isEmpty()) {
                Markdown.renderInto(this, block, p.text, t, false);
                any = true;
            } else if (p.kind == ChatMessage.PART_REASONING
                    && p.text != null && !p.text.trim().isEmpty()) {
                renderReasoning(block, m.id, partIndex, p.text);
                any = true;
            } else if (p.kind == ChatMessage.PART_TOOL) {
                renderTool(block, m.id, partIndex, p);
                any = true;
            }
            partIndex++;
        }

        if (m.retryAttempt > 0 && m.completed == 0 && busy) {
            LinearLayout retry = new LinearLayout(this);
            retry.setOrientation(LinearLayout.VERTICAL);
            retry.setBackground(Ui.round(this, t.surface, 10, t.border, 1));
            retry.setPadding(Ui.dp(this, 12), Ui.dp(this, 9),
                    Ui.dp(this, 12), Ui.dp(this, 9));
            retry.addView(Ui.label(this, getString(R.string.chat_retrying), t.muted));
            String message = m.retryError == null || m.retryError.isEmpty()
                    ? getString(R.string.chat_working)
                    : getString(R.string.chat_retry_attempt, m.retryAttempt, m.retryError);
            TextView body = Ui.text(this, message, 12f, t.muted, false);
            body.setLineSpacing(0, 1.35f);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = Ui.dp(this, 4);
            retry.addView(body, blp);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = Ui.dp(this, 8);
            block.addView(retry, rlp);
            any = true;
        }

        if (!any && m.completed == 0 && busy) {
            LinearLayout working = new LinearLayout(this);
            working.setOrientation(LinearLayout.HORIZONTAL);
            working.setGravity(Gravity.CENTER_VERTICAL);
            working.setPadding(0, Ui.dp(this, 8), 0, 0);
            ProgressBar bar = new ProgressBar(this);
            working.addView(bar, new LinearLayout.LayoutParams(
                    Ui.dp(this, 16), Ui.dp(this, 16)));
            TextView label = Ui.text(this, getString(R.string.chat_working), 12f, t.muted, false);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.setMarginStart(Ui.dp(this, 8));
            working.addView(label, llp);
            block.addView(working);
        }

        if (m.error != null && !m.error.isEmpty()) {
            block.addView(errorNotice(m.error));
        }
        block.setLayoutParams(blockLp);
        return block;
    }

    private void renderReasoning(LinearLayout parent, String messageId, int partIndex, String text) {
        final String key = messageId + ":r:" + partIndex;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, t.field, 10));
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), Ui.dp(this, 10));
        card.setClickable(true);
        card.setFocusable(true);

        final TextView label = Ui.label(this, getString(R.string.chat_thinking), t.muted);
        card.addView(label);

        final TextView body = Ui.text(this, Ui.ellipsize(text, 12000), 12.5f, t.muted, false);
        body.setTypeface(null, Typeface.ITALIC);
        body.setLineSpacing(0, 1.36f);
        body.setTextIsSelectable(true);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 5);
        card.addView(body, blp);

        final boolean[] open = {expandedReasoning.contains(key)};
        if (text.length() < 260) open[0] = true;
        applyReasoningState(label, body, open[0]);
        card.setOnClickListener(v -> {
            open[0] = !open[0];
            if (open[0]) expandedReasoning.add(key);
            else expandedReasoning.remove(key);
            applyReasoningState(label, body, open[0]);
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        parent.addView(card, lp);
    }

    private void applyReasoningState(TextView label, TextView body, boolean open) {
        if (open) {
            label.setText(getString(R.string.chat_thinking_collapse));
            body.setMaxLines(Integer.MAX_VALUE);
            body.setEllipsize(null);
        } else {
            label.setText(getString(R.string.chat_thinking));
            body.setMaxLines(3);
            body.setEllipsize(TextUtils.TruncateAt.END);
        }
    }

    private void renderTool(LinearLayout parent, String messageId, int partIndex,
                            ChatMessage.Part part) {
        final String key = messageId + ":t:" + partIndex;
        final boolean expanded = expandedTools.contains(key);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, t.surface, 12, t.border, 1));
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), Ui.dp(this, 10));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = Ui.icon(this, R.drawable.ic_tool, t.muted, 15);
        head.addView(icon);
        TextView name = Ui.text(this, prettyTool(part.toolName), 13f, t.text, true);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.setMarginStart(Ui.dp(this, 8));
        head.addView(name, nlp);

        String st = part.toolStatus == null ? "" : part.toolStatus;
        TextView state = Ui.text(this, toolStatusLabel(st), 9.5f, toolStatusColor(st), true);
        state.setLetterSpacing(0.06f);
        state.setBackground(Ui.round(this, t.field, 6));
        state.setPadding(Ui.dp(this, 6), Ui.dp(this, 2),
                Ui.dp(this, 6), Ui.dp(this, 2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMarginStart(Ui.dp(this, 8));
        head.addView(state, slp);

        if (part.toolRunning) {
            ProgressBar bar = new ProgressBar(this);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 14), Ui.dp(this, 14));
            blp.setMarginStart(Ui.dp(this, 8));
            head.addView(bar, blp);
        }

        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        ImageView chevron = Ui.icon(this, R.drawable.ic_chevron, t.dim, 14);
        head.addView(chevron);
        card.addView(head);

        String summary = toolSummary(part);
        if (!summary.isEmpty()) {
            TextView sum = Ui.text(this, summary, 12f, t.muted, false);
            sum.setMaxLines(2);
            sum.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sumLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sumLp.topMargin = Ui.dp(this, 6);
            card.addView(sum, sumLp);
        }

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (expanded) fillToolDetails(details, part);
        card.addView(details);

        card.setOnClickListener(v -> {
            boolean now = details.getVisibility() != View.VISIBLE;
            if (now) {
                if (details.getChildCount() == 0) fillToolDetails(details, part);
                details.setVisibility(View.VISIBLE);
                expandedTools.add(key);
            } else {
                details.setVisibility(View.GONE);
                expandedTools.remove(key);
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        parent.addView(card, lp);
    }

    private void fillToolDetails(LinearLayout details, ChatMessage.Part part) {
        if (part.toolInput != null && !part.toolInput.trim().isEmpty()) {
            details.addView(sectionLabel(getString(R.string.chat_tool_input)));
            details.addView(codeBox(part.toolInput, 4000));
        }
        if (part.toolOutput != null && !part.toolOutput.trim().isEmpty()) {
            details.addView(sectionLabel(getString(R.string.chat_tool_output)));
            details.addView(codeBox(part.toolOutput, 8000));
        }
        if (part.toolError != null && !part.toolError.trim().isEmpty()) {
            TextView err = Ui.text(this, part.toolError, 12f, t.error, false);
            err.setLineSpacing(0, 1.35f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 8);
            details.addView(err, lp);
        }
    }

    private TextView sectionLabel(String text) {
        TextView label = Ui.label(this, text, t.dim);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        lp.bottomMargin = Ui.dp(this, 5);
        label.setLayoutParams(lp);
        return label;
    }

    private View codeBox(String text, int maxChars) {
        TextView v = Ui.text(this, Ui.ellipsize(text, maxChars), 11.5f, t.codeText, false);
        v.setTypeface(Typeface.MONOSPACE);
        v.setLineSpacing(0, 1.3f);
        v.setTextIsSelectable(true);
        v.setBackground(Ui.round(this, t.codeBg, 8));
        v.setPadding(Ui.dp(this, 10), Ui.dp(this, 8),
                Ui.dp(this, 10), Ui.dp(this, 8));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 6);
        box.setLayoutParams(lp);
        return box;
    }

    private View errorNotice(String message) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(this, t.surface, 10, t.error, 1));
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), Ui.dp(this, 10));
        TextView label = Ui.label(this, "Error", t.error);
        box.addView(label);
        TextView body = Ui.text(this, message, 12f, t.error, false);
        body.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 4);
        box.addView(body, lp);
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrap.topMargin = Ui.dp(this, 8);
        box.setLayoutParams(wrap);
        return box;
    }

    private View renderCompaction() {
        TextView v = Ui.text(this, "Context compacted", 11.5f, t.dim, false);
        v.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        lp.bottomMargin = Ui.dp(this, 4);
        v.setLayoutParams(lp);
        return v;
    }

    private View renderIdle(ChatMessage m) {
        if (m.outcome == null || m.outcome.isEmpty()) return null;
        if (m.outcome.contains("fail") || m.outcome.contains("error")) {
            String msg = m.error == null || m.error.isEmpty() ? m.outcome : m.error;
            View notice = errorNotice(msg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 14);
            notice.setLayoutParams(lp);
            return notice;
        }
        return null;
    }

    private View renderShell(ChatMessage m) {
        if (m.text == null || m.text.trim().isEmpty()) return null;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, t.surface, 12, t.border, 1));
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10),
                Ui.dp(this, 12), Ui.dp(this, 10));
        TextView label = Ui.label(this, "Shell", t.dim);
        card.addView(label);
        card.addView(codeBox(m.text, 3000));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        card.setLayoutParams(lp);
        return card;
    }

    private void addWorkingRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        ProgressBar bar = new ProgressBar(this);
        row.addView(bar, new LinearLayout.LayoutParams(
                Ui.dp(this, 16), Ui.dp(this, 16)));
        TextView label = Ui.text(this, getString(R.string.chat_working), 12f, t.muted, false);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.setMarginStart(Ui.dp(this, 8));
        row.addView(label, llp);
        messagesCol.addView(row);
        workingRow = row;
    }

    private void addLoadOlder() {
        TextView v = Ui.text(this, getString(R.string.chat_load_older), 12f, t.muted, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(Ui.dp(this, 10), Ui.dp(this, 10),
                Ui.dp(this, 10), Ui.dp(this, 10));
        v.setBackground(Ui.ripple(this, Ui.round(this, t.surface, 10, t.border, 1), t.ripple));
        v.setOnClickListener(x -> loadOlder());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 12);
        messagesCol.addView(v, lp);
    }

    private void addLoadingState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, Ui.dp(this, 56), 0, 0);
        ProgressBar bar = new ProgressBar(this);
        box.addView(bar);
        TextView label = Ui.text(this, getString(R.string.chat_loading), 12f, t.muted, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        box.addView(label, lp);
        messagesCol.addView(box);
    }

    private void addEmptyState() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 72),
                Ui.dp(this, 16), Ui.dp(this, 24));
        ImageView icon = Ui.icon(this, R.drawable.ic_sessions, t.dim, 40);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(this, 40), Ui.dp(this, 40));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(icon, ilp);
        TextView title = Ui.text(this, getString(R.string.chat_empty_title), 18f, t.text, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 16);
        box.addView(title, tlp);
        TextView body = Ui.text(this, getString(R.string.chat_empty_body), 12.5f, t.muted, false);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0, 1.45f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        box.addView(body, blp);
        messagesCol.addView(box);
    }

    private void addErrorState() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(this, t.surface, 14, t.border, 1));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 16),
                Ui.dp(this, 16), Ui.dp(this, 16));
        TextView title = Ui.text(this,
                expired ? getString(R.string.chat_expired) : getString(R.string.common_error),
                16f, t.text, true);
        card.addView(title);
        TextView body = Ui.text(this, loadError, 12.5f, t.muted, false);
        body.setLineSpacing(0, 1.45f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 6);
        card.addView(body, blp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        TextView retry = Ui.button(this, t,
                getString(R.string.common_retry), !expired);
        retry.setOnClickListener(v -> loadInitial());
        buttons.addView(retry, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 42)));
        if (expired) {
            TextView pair = Ui.button(this, t, getString(R.string.chat_pair_again), true);
            pair.setOnClickListener(v -> startPairAgain());
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 42));
            plp.setMarginStart(Ui.dp(this, 8));
            buttons.addView(pair, plp);
        }
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = Ui.dp(this, 14);
        card.addView(buttons, btnLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 40);
        messagesCol.addView(card, lp);
    }

    // ---------- Composer ----------

    private void updateComposer() {
        if (send == null) return;
        boolean stop = busy;
        // The transcript is still loading for the first time: sending now would race the initial
        // page and briefly hide the new message behind the loading state.
        boolean notReady = loading && !initialLoaded;
        send.setImageResource(stop ? R.drawable.ic_stop : R.drawable.ic_send);
        send.setColorFilter(stop ? Color.WHITE : t.ctaText);
        send.setBackground(Ui.ripple(this,
                Ui.round(this, stop ? t.error : t.cta, 999), t.ripple));
        send.setEnabled(!expired && !notReady);
        send.setContentDescription(getString(stop ? R.string.cd_stop : R.string.cd_send));
        input.setEnabled(!expired && !notReady);

        if (expired) {
            status.setText(R.string.chat_expired);
            status.setVisibility(View.VISIBLE);
        } else if (networkError) {
            status.setText(R.string.chat_offline);
            status.setVisibility(View.VISIBLE);
        } else if (busy) {
            status.setText(R.string.chat_working);
            status.setVisibility(View.VISIBLE);
        } else {
            status.setVisibility(View.GONE);
        }
        if (modelChip != null) modelChip.setText(modelText());
        if (subtitle != null) subtitle.setText(subtitleText());
    }

    private String modelText() {
        String s = displayModel(currentModelId);
        return s == null || s.isEmpty() ? getString(R.string.chat_model) : s;
    }

    private String subtitleText() {
        if (modelLabel == null || modelLabel.isEmpty()) return host;
        return modelLabel + "  ·  " + host;
    }

    private String displayModel(String id) {
        if (id == null || id.isEmpty()) return "";
        if (models != null) {
            for (ServerModel m : models) {
                if (m.id.equals(id)) return m.label();
            }
        }
        return Ui.shortModel(id);
    }

    private void send() {
        if (expired) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        ChatMessage optimistic = ChatMessage.optimistic(
                "local_" + System.nanoTime(), text);
        messages.put(optimistic.id, optimistic);
        busy = true;
        renderMessages();
        updateComposer();
        Bg.run(() -> {
            api.prompt(sessionId, text);
            return null;
        }, v -> {
            if (destroyed) return;
            busy = true;
            updateComposer();
            schedule(500);
        }, e -> {
            if (destroyed) return;
            messages.remove(optimistic.id);
            input.setText(text);
            busy = false;
            renderMessages();
            toast(e);
        });
    }

    private void interrupt() {
        Bg.run(() -> {
            api.interrupt(sessionId);
            return null;
        }, v -> {
            if (destroyed) return;
            Toast.makeText(this, R.string.chat_interrupted, Toast.LENGTH_SHORT).show();
            schedule(400);
        }, e -> {
            if (destroyed) return;
            toast(e);
        });
    }

    private void loadOlder() {
        if (loadingOlder || oldestCursor == null || oldestCursor.isEmpty()) return;
        loadingOlder = true;
        int oldY = scroll.getScrollY();
        Bg.run(() -> api.messages(sessionId, 50, "desc", oldestCursor), page -> {
            if (destroyed) return;
            loadingOlder = false;
            List<ChatMessage> older = new ArrayList<>(page.data);
            Collections.reverse(older);
            LinkedHashMap<String, ChatMessage> merged = new LinkedHashMap<>();
            for (ChatMessage m : older) {
                if (!messages.containsKey(m.id)) merged.put(m.id, m);
            }
            merged.putAll(messages);
            messages.clear();
            messages.putAll(merged);
            oldestCursor = page.nextCursor;
            forceFullRender = true;
            lastSignature = "";
            renderMessages();
            // Restore the reading position once the prepended children have been measured. A
            // plain post() usually runs before the layout pass and would see the old height.
            messagesCol.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                                     int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    messagesCol.removeOnLayoutChangeListener(this);
                    int delta = bottom - oldBottom;
                    scroll.scrollTo(0, oldY + Math.max(0, delta));
                }
            });
        }, e -> {
            if (destroyed) return;
            loadingOlder = false;
            toast(e);
        });
    }

    private void loadModels() {
        if (modelsRequested) return;
        modelsRequested = true;
        Bg.run(api::models, list -> {
            if (destroyed) return;
            models = list;
            modelLabel = displayModel(currentModelId);
            updateComposer();
        }, e -> { });
    }

    private void pickModel() {
        if (models == null || models.isEmpty()) {
            Toast.makeText(this, R.string.chat_models_loading, Toast.LENGTH_SHORT).show();
            return;
        }
        List<ServerModel> choices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (ServerModel m : models) {
            if (!m.enabled) continue;
            choices.add(m);
            String prefix = m.id.equals(currentModelId) ? "\u2713  " : "";
            labels.add(prefix + m.label() + "  ·  " + m.providerId);
        }
        if (choices.isEmpty()) return;
        AlertDialog dialog = new AlertDialog.Builder(this, Ui.dialogTheme(t))
                .setTitle(R.string.chat_model)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    ServerModel m = choices.get(which);
                    applyModel(m);
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(t.muted);
    }

    private void applyModel(ServerModel m) {
        Bg.run(() -> {
            api.setModel(sessionId, m.providerId, m.id, null);
            return null;
        }, v -> {
            if (destroyed) return;
            modelPinned = true;
            currentModelId = m.id;
            modelLabel = m.label();
            updateComposer();
            Toast.makeText(this, R.string.chat_model_changed, Toast.LENGTH_SHORT).show();
        }, e -> {
            if (destroyed) return;
            toast(e);
        });
    }

    // ---------- Permissions ----------

    private void updatePermissions(List<PermissionRequest> list) {
        if (list.isEmpty()) {
            pendingPermission = null;
            permissionCount = 0;
            permissionWrap.setVisibility(View.GONE);
            return;
        }
        permissionCount = list.size();
        PermissionRequest first = list.get(0);
        if (pendingPermission != null && pendingPermission.id.equals(first.id)
                && permissionCard.getChildCount() > 0) {
            permissionWrap.setVisibility(View.VISIBLE);
            return;
        }
        pendingPermission = first;
        buildPermissionCard(first);
        permissionWrap.setVisibility(View.VISIBLE);
    }

    private void updatePermissionVisibility() {
        permissionWrap.setVisibility(
                pendingPermission == null ? View.GONE : View.VISIBLE);
    }

    private void buildPermissionCard(PermissionRequest p) {
        permissionCard.removeAllViews();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, getString(R.string.chat_permission_title), 13f, t.text, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);
        if (permissionCount > 1) {
            head.addView(Ui.text(this,
                    getString(R.string.chat_permission_counts, 1, permissionCount),
                    10.5f, t.dim, false));
        }
        permissionCard.addView(head);

        String body = p.action;
        if (p.resources != null && !p.resources.isEmpty()) {
            body += "\n" + TextUtils.join(", ", p.resources);
        }
        if (p.message != null && !p.message.isEmpty()) {
            body += "\n" + p.message;
        }
        TextView desc = Ui.text(this, body, 12f, t.muted, false);
        desc.setLineSpacing(0, 1.35f);
        desc.setMaxLines(4);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(this, 5);
        permissionCard.addView(desc, dlp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 10);

        TextView allow = Ui.button(this, t, getString(R.string.chat_permission_allow), true);
        allow.setTextSize(12.5f);
        allow.setOnClickListener(v -> replyPermission("once"));
        buttons.addView(allow, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1f));

        TextView always = Ui.button(this, t, getString(R.string.chat_permission_always), false);
        always.setTextSize(12.5f);
        always.setOnClickListener(v -> replyPermission("always"));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1f);
        alp.setMarginStart(Ui.dp(this, 6));
        buttons.addView(always, alp);

        TextView reject = Ui.dangerButton(this, t, getString(R.string.chat_permission_reject));
        reject.setTextSize(12.5f);
        reject.setOnClickListener(v -> replyPermission("reject"));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1f);
        rlp.setMarginStart(Ui.dp(this, 6));
        buttons.addView(reject, rlp);

        permissionCard.addView(buttons, blp);
    }

    private void replyPermission(String decision) {
        PermissionRequest p = pendingPermission;
        if (p == null) return;
        pendingPermission = null;
        permissionWrap.setVisibility(View.GONE);
        Bg.run(() -> {
            api.replyPermission(sessionId, p.id, decision);
            return null;
        }, v -> {
            if (destroyed) return;
            schedule(400);
        }, e -> {
            if (destroyed) return;
            toast(e);
            schedule(800);
        });
    }

    // ---------- Misc ----------

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, getString(R.string.chat_model));
        menu.getMenu().add(0, 2, 1, getString(R.string.chat_reload));
        menu.getMenu().add(0, 3, 2, getString(R.string.chat_open_web));
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                pickModel();
                return true;
            }
            if (item.getItemId() == 2) {
                messages.clear();
                oldestCursor = null;
                lastSignature = "";
                loadInitial();
                return true;
            }
            if (item.getItemId() == 3) {
                openWeb();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void openWeb() {
        ConnectionStore.setWebCookie(this, conn.baseUrl, conn.token);
        Intent i = new Intent(this, WebActivity.class);
        i.putExtra(WebActivity.EXTRA_CONNECTION, conn.id);
        startActivity(i);
    }

    private void startPairAgain() {
        startActivityForResult(new Intent(this, AddConnectionActivity.class), REQ_PAIR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PAIR && resultCode == RESULT_OK) finish();
    }

    private String prettyTool(String name) {
        if (name == null || name.isEmpty()) return "Tool";
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private String toolStatusLabel(String status) {
        if ("completed".equals(status)) return "DONE";
        if ("error".equals(status)) return "ERROR";
        if ("running".equals(status)) return "RUNNING";
        if ("streaming".equals(status)) return "STREAMING";
        return status == null ? "" : status.toUpperCase(java.util.Locale.US);
    }

    private int toolStatusColor(String status) {
        if ("completed".equals(status)) return t.ok;
        if ("error".equals(status)) return t.error;
        if ("running".equals(status) || "streaming".equals(status)) return t.warn;
        return t.muted;
    }

    private String toolSummary(ChatMessage.Part part) {
        String raw = part.toolInput;
        if (raw == null || raw.trim().isEmpty()) return "";
        String trimmed = raw.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(trimmed);
                String[] keys = {"command", "filePath", "path", "pattern", "query",
                        "description", "url", "task", "prompt"};
                for (String key : keys) {
                    String v = o.optString(key, "");
                    if (!v.isEmpty()) return Ui.ellipsize(firstLine(v), 180);
                }
            } catch (Exception ignored) { }
        }
        if (!trimmed.startsWith("{")) return Ui.ellipsize(firstLine(trimmed), 180);
        return "";
    }

    private String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private boolean isAtBottom() {
        if (scroll == null) return true;
        View child = scroll.getChildAt(0);
        if (child == null) return true;
        return scroll.getScrollY() + scroll.getHeight() >= child.getHeight() - Ui.dp(this, 72);
    }

    private void toast(Exception e) {
        String msg = e == null ? null : e.getMessage();
        if (msg == null || msg.isEmpty()) msg = getString(R.string.common_error);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        if (initialLoaded && !loading && !expired) {
            long now = System.currentTimeMillis();
            if (lastPollAt > 0 && now - lastPollAt > 8000) loadInitial();
            else schedule(120);
        }
    }

    @Override
    protected void onPause() {
        polling = false;
        ui.removeCallbacks(poller);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        polling = false;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void registerBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::finish);
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void applyInsets(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            v.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                ui.post(() -> {
                    if (scroll != null && isAtBottom()) scroll.fullScroll(View.FOCUS_DOWN);
                });
                return insets;
            });
        } else {
            v.setFitsSystemWindows(true);
        }
    }

    private static final class Poll {
        final ApiClient.MessagePage page;
        final Set<String> active;
        final List<PermissionRequest> permissions;

        Poll(ApiClient.MessagePage page, Set<String> active, List<PermissionRequest> permissions) {
            this.page = page;
            this.active = active;
            this.permissions = permissions;
        }
    }
}
