package app.opencodepair;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small builders for the platform widgets. Keeps the activities readable. */
public final class Ui {
    private Ui() {}

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        return round(c, color, radiusDp, 0, 0);
    }

    public static GradientDrawable round(Context c, int color, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0 && strokeDp > 0) d.setStroke(dp(c, strokeDp), stroke);
        return d;
    }

    public static Drawable ripple(Context c, Drawable content, int color) {
        return new RippleDrawable(ColorStateList.valueOf(color), content, null);
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(s == null ? "" : s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL);
        v.setIncludeFontPadding(false);
        return v;
    }

    /** Small uppercase section label used above lists and fields. */
    public static TextView label(Context c, CharSequence s, int color) {
        TextView v = text(c, s, 11f, color, true);
        v.setAllCaps(true);
        v.setLetterSpacing(0.08f);
        return v;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static View spacer(Context c, float dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, dp)));
        return v;
    }

    public static View divider(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    public static ImageView icon(Context c, int res, int tint, float sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    public static ImageButton iconButton(Context c, int res, int tint, int descRes, Theme t) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(res);
        b.setColorFilter(tint);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(c, 9), dp(c, 9), dp(c, 9), dp(c, 9));
        b.setBackground(ripple(c, null, t.ripple));
        b.setContentDescription(descRes == 0 ? "" : c.getString(descRes));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 42), dp(c, 42)));
        return b;
    }

    /**
     * A tappable text button. TextView is used instead of Button so the platform's all-caps and
     * inset background do not fight the design.
     */
    public static TextView button(Context c, Theme t, String label, boolean primary) {
        TextView b = text(c, label, 15f, primary ? t.ctaText : t.text, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        b.setMinHeight(dp(c, 44));
        b.setBackground(ripple(c,
                round(c, primary ? t.cta : t.surface, 12,
                        primary ? 0 : t.border, primary ? 0 : 1), t.ripple));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    public static TextView dangerButton(Context c, Theme t, String label) {
        TextView b = text(c, label, 14f, t.error, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        b.setBackground(ripple(c, round(c, 0, 10), t.ripple));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    public static EditText field(Context c, Theme t, String hint, int inputType, boolean multiline) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(t.dim);
        e.setTextColor(t.text);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        e.setBackground(round(c, t.field, 12, t.border, 1));
        e.setInputType(inputType);
        e.setSingleLine(!multiline);
        if (multiline) {
            e.setGravity(Gravity.TOP | Gravity.START);
            e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
            e.setMinLines(1);
            e.setMaxLines(6);
        } else {
            e.setGravity(Gravity.CENTER_VERTICAL);
            e.setPadding(dp(c, 14), 0, dp(c, 14), 0);
            e.setMinHeight(dp(c, 52));
        }
        return e;
    }

    public static void margins(View v, int l, int tp, int r, int b) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
        m.setMargins(l, tp, r, b);
        v.setLayoutParams(m);
    }

    public static String host(String baseUrl) {
        try {
            String h = Uri.parse(baseUrl).getAuthority();
            return h == null ? baseUrl : h;
        } catch (Exception e) {
            return baseUrl;
        }
    }

    public static String shortDir(String path) {
        if (path == null || path.isEmpty()) return "";
        String p = path.replace('\\', '/');
        while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        int at = p.lastIndexOf('/');
        return at >= 0 && at + 1 < p.length() ? p.substring(at + 1) : p;
    }

    public static String shortModel(String id) {
        if (id == null) return "";
        String s = id;
        if (s.endsWith("-free")) s = s.substring(0, s.length() - 5);
        if (s.length() > 22) s = s.substring(0, 21) + "\u2026";
        return s;
    }

    public static String timeAgo(long ms) {
        if (ms <= 0) return "";
        long diff = System.currentTimeMillis() - ms;
        if (diff < 0) diff = 0;
        long m = diff / 60000L;
        if (m < 1) return "now";
        if (m < 60) return m + "m ago";
        long h = m / 60;
        if (h < 24) return h + "h ago";
        long d = h / 24;
        if (d < 7) return d + "d ago";
        return new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(ms));
    }

    public static int dialogTheme(Theme t) {
        return t.dark
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    public static AlertDialog progressDialog(Context c, Theme t, String message) {
        LinearLayout box = row(c);
        box.setPadding(dp(c, 22), dp(c, 20), dp(c, 22), dp(c, 20));
        ProgressBar p = new ProgressBar(c);
        p.setIndeterminate(true);
        box.addView(p, new LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)));
        TextView m = text(c, message, 14f, t.text, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(c, 16));
        box.addView(m, lp);
        return new AlertDialog.Builder(c, dialogTheme(t))
                .setView(box)
                .setCancelable(false)
                .create();
    }

    public static void hideKeyboard(Activity a) {
        try {
            InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            View v = a.getCurrentFocus();
            if (imm != null && v != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Exception ignored) { }
    }

    public static void systemBars(Activity a, Theme t) {
        Window w = a.getWindow();
        try {
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
        } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(t.dark ? 0 : mask, mask);
                }
            } catch (Exception ignored) {
                // getInsetsController() dereferences the decor view on some platforms; it is
                // only safe once setContentView() has installed it.
            }
        } else {
            int flags = t.dark ? 0
                    : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    public static String ellipsize(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "\u2026";
    }

    public static String join(java.util.List<String> parts, String sep) {
        if (parts == null || parts.isEmpty()) return "";
        return TextUtils.join(sep, parts);
    }
}
