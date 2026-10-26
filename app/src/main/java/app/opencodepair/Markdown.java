package app.opencodepair;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deliberately small Markdown renderer: fenced code, headings, lists, quotes and the inline
 * bold / italic / code / link forms that show up in agent replies. Everything else is shown as
 * plain text, which is far better than showing the raw markup.
 */
public final class Markdown {
    public static final int B_TEXT = 0;
    public static final int B_CODE = 1;
    public static final int B_HEADING = 2;
    public static final int B_BULLET = 3;
    public static final int B_NUMBER = 4;
    public static final int B_QUOTE = 5;

    public static final class Block {
        public int type;
        public String text = "";
        public String lang = "";
        public String marker = "";
        public int level = 1;
    }

    private static final Pattern INLINE = Pattern.compile(
            "(`([^`]+)`)"
                    + "|(\\*\\*([^*]+)\\*\\*)"
                    + "|(__([^_]+)__)"
                    + "|(\\[([^\\]]+)\\]\\(([^)]+)\\))"
                    + "|(\\*([^*]+)\\*)"
                    + "|(_([^_]+)_)");

    private Markdown() {}

    public static List<Block> parse(String md) {
        List<Block> out = new ArrayList<>();
        if (md == null) return out;
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder para = new StringBuilder();
        StringBuilder code = null;
        String codeLang = "";
        boolean inCode = false;

        for (String raw : lines) {
            String line = raw == null ? "" : raw;
            String trimmed = line.trim();

            if (inCode) {
                if (trimmed.startsWith("```")) {
                    Block b = new Block();
                    b.type = B_CODE;
                    b.text = code.toString();
                    b.lang = codeLang;
                    out.add(b);
                    inCode = false;
                    code = null;
                    codeLang = "";
                } else {
                    code.append(line).append('\n');
                }
                continue;
            }

            if (trimmed.startsWith("```")) {
                flushPara(out, para);
                inCode = true;
                code = new StringBuilder();
                codeLang = trimmed.substring(3).trim();
                continue;
            }
            if (trimmed.isEmpty()) {
                flushPara(out, para);
                continue;
            }
            if (trimmed.matches("^#{1,6}\\s+.*")) {
                flushPara(out, para);
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                Block b = new Block();
                b.type = B_HEADING;
                b.level = Math.max(1, level);
                b.text = trimmed.substring(level).trim();
                out.add(b);
                continue;
            }
            if (trimmed.matches("^[-*+]\\s+.*")) {
                flushPara(out, para);
                Block b = new Block();
                b.type = B_BULLET;
                b.text = trimmed.substring(1).trim();
                out.add(b);
                continue;
            }
            if (trimmed.matches("^\\d+[.)]\\s+.*")) {
                flushPara(out, para);
                int sp = trimmed.indexOf(' ');
                Block b = new Block();
                b.type = B_NUMBER;
                b.marker = sp > 0 ? trimmed.substring(0, sp) : "1.";
                b.text = sp > 0 ? trimmed.substring(sp + 1).trim() : trimmed;
                out.add(b);
                continue;
            }
            if (trimmed.startsWith(">")) {
                flushPara(out, para);
                Block b = new Block();
                b.type = B_QUOTE;
                b.text = trimmed.substring(1).trim();
                out.add(b);
                continue;
            }
            if (trimmed.matches("^([-*_])(\\s*\\1){2,}\\s*$")) {
                flushPara(out, para);
                continue;
            }
            if (para.length() > 0) para.append(' ');
            para.append(trimmed);
        }
        flushPara(out, para);
        if (inCode && code != null) {
            Block b = new Block();
            b.type = B_CODE;
            b.text = code.toString();
            b.lang = codeLang;
            out.add(b);
        }
        return out;
    }

    private static void flushPara(List<Block> out, StringBuilder para) {
        if (para.length() == 0) return;
        String s = para.toString().trim();
        para.setLength(0);
        if (s.isEmpty()) return;
        Block b = new Block();
        b.type = B_TEXT;
        b.text = s;
        out.add(b);
    }

    public static void renderInto(Context c, LinearLayout parent, String md, Theme t, boolean compact) {
        for (Block b : parse(md)) {
            switch (b.type) {
                case B_CODE: addCode(c, parent, b, t); break;
                case B_HEADING: addHeading(c, parent, b, t, compact); break;
                case B_BULLET: addListRow(c, parent, "\u2022", b.text, t, compact); break;
                case B_NUMBER: addListRow(c, parent, b.marker, b.text, t, compact); break;
                case B_QUOTE: addQuote(c, parent, b.text, t, compact); break;
                default: addText(c, parent, b.text, t, compact); break;
            }
        }
    }

    private static void addText(Context c, LinearLayout parent, String text, Theme t, boolean compact) {
        TextView v = Ui.text(c, "", compact ? 14.5f : 15f, t.text, false);
        v.setText(inline(c, text, t));
        v.setLineSpacing(0, compact ? 1.28f : 1.38f);
        v.setTextIsSelectable(true);
        if (text.contains("](")) {
            v.setMovementMethod(LinkMovementMethod.getInstance());
            v.setHighlightColor(Color.TRANSPARENT);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, compact ? 3 : 7);
        parent.addView(v, lp);
    }

    private static void addHeading(Context c, LinearLayout parent, Block b, Theme t, boolean compact) {
        float size = b.level <= 1 ? 19f : b.level == 2 ? 17f : 15.5f;
        if (compact) size -= 1f;
        TextView v = Ui.text(c, "", size, t.text, true);
        v.setText(inline(c, b.text, t));
        v.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 8);
        lp.bottomMargin = Ui.dp(c, 4);
        parent.addView(v, lp);
    }

    private static void addListRow(Context c, LinearLayout parent, String marker, String text, Theme t, boolean compact) {
        LinearLayout row = Ui.row(c);
        TextView m = Ui.text(c, marker, compact ? 14.5f : 15f, t.muted, false);
        m.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(c, 22), LinearLayout.LayoutParams.WRAP_CONTENT);
        row.addView(m, mlp);

        TextView v = Ui.text(c, "", compact ? 14.5f : 15f, t.text, false);
        v.setText(inline(c, text, t));
        v.setLineSpacing(0, 1.32f);
        v.setTextIsSelectable(true);
        row.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, compact ? 2 : 4);
        parent.addView(row, lp);
    }

    private static void addQuote(Context c, LinearLayout parent, String text, Theme t, boolean compact) {
        LinearLayout row = Ui.row(c);
        View bar = new View(c);
        bar.setBackgroundColor(t.border);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(c, 3), LinearLayout.LayoutParams.MATCH_PARENT);
        blp.bottomMargin = Ui.dp(c, 4);
        row.addView(bar, blp);

        TextView v = Ui.text(c, "", compact ? 14.5f : 15f, t.muted, false);
        v.setText(inline(c, text, t));
        v.setTypeface(null, Typeface.ITALIC);
        v.setLineSpacing(0, 1.32f);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        vlp.setMarginStart(Ui.dp(c, 10));
        row.addView(v, vlp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 6);
        parent.addView(row, lp);
    }

    private static void addCode(Context c, LinearLayout parent, Block b, Theme t) {
        LinearLayout box = Ui.col(c);
        box.setBackground(Ui.round(c, t.codeBg, 10));
        box.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 12));

        LinearLayout head = Ui.row(c);
        String lang = b.lang == null ? "" : b.lang.trim();
        if (!lang.isEmpty()) {
            TextView l = Ui.text(c, lang.toUpperCase(java.util.Locale.US), 9.5f, 0xFF9CA3AF, true);
            l.setLetterSpacing(0.08f);
            head.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            head.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        TextView copy = Ui.text(c, "COPY", 9.5f, 0xFF9CA3AF, true);
        copy.setLetterSpacing(0.08f);
        copy.setPadding(Ui.dp(c, 8), Ui.dp(c, 4), 0, Ui.dp(c, 4));
        final String codeText = b.text;
        copy.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("code", codeText));
                Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) { }
        });
        head.addView(copy);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = Ui.dp(c, 6);
        box.addView(head, hlp);

        TextView v = Ui.text(c, b.text, 12.5f, t.codeText, false);
        v.setTypeface(Typeface.MONOSPACE);
        v.setLineSpacing(0, 1.3f);
        v.setTextIsSelectable(true);
        box.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 4);
        lp.bottomMargin = Ui.dp(c, 10);
        parent.addView(box, lp);
    }

    public static CharSequence inline(Context c, String s, Theme t) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (s == null) return out;
        Matcher m = INLINE.matcher(s);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.append(s, last, m.start());
            int start = out.length();
            if (m.group(2) != null) {
                out.append(m.group(2));
                int end = out.length();
                out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(t.codeBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(t.codeText), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(4) != null || m.group(6) != null) {
                String text = m.group(4) != null ? m.group(4) : m.group(6);
                out.append(text);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(8) != null) {
                String text = m.group(8);
                final String url = m.group(9);
                out.append(text);
                int end = out.length();
                out.setSpan(new ClickableSpan() {
                    @Override public void onClick(View widget) {
                        try {
                            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        } catch (Exception ignored) { }
                    }
                    @Override public void updateDrawState(TextPaint ds) {
                        ds.setColor(t.link);
                        ds.setUnderlineText(true);
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                String text = m.group(11) != null ? m.group(11) : m.group(13);
                out.append(text);
                out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        if (last < s.length()) out.append(s, last, s.length());
        return out;
    }
}
