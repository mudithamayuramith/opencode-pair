package app.opencodepair;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * The small set of colours the app draws with. The visual language follows the opencode-mobile
 * client: near-black text on a light grey canvas, white cards, hairline borders and an inverted
 * (white on black) call to action in dark mode.
 */
public final class Theme {
    public static final String PREFS = "opencode_pair";
    public static final String KEY_MODE = "theme_mode";
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";

    public final boolean dark;

    public final int bg;
    public final int surface;
    public final int surfaceAlt;
    public final int field;
    public final int text;
    public final int muted;
    public final int dim;
    public final int border;
    public final int cta;
    public final int ctaText;
    public final int accent;
    public final int link;
    public final int ok;
    public final int warn;
    public final int error;
    public final int codeBg;
    public final int codeText;
    public final int bubbleUser;
    public final int bubbleUserText;
    public final int navBg;
    public final int ripple;
    public final int scrim;

    private Theme(boolean dark) {
        this.dark = dark;
        if (dark) {
            bg = 0xFF0A0A0A;
            surface = 0xFF141414;
            surfaceAlt = 0xFF1C1C1C;
            field = 0xFF1A1A1A;
            text = 0xFFFFFFFF;
            muted = 0xFF8A8A8A;
            dim = 0xFF666666;
            border = 0xFF262626;
            cta = 0xFFFFFFFF;
            ctaText = 0xFF0A0A0A;
            accent = 0xFFFFFFFF;
            link = 0xFF60A5FA;
            ok = 0xFF22C55E;
            warn = 0xFFF59E0B;
            error = 0xFFEF4444;
            codeBg = 0xFF1A1A1A;
            codeText = 0xFFE5E5E5;
            bubbleUser = 0xFFFFFFFF;
            bubbleUserText = 0xFF0A0A0A;
            navBg = 0xFF0A0A0A;
            ripple = 0x33FFFFFF;
            scrim = 0xB3000000;
        } else {
            bg = 0xFFF5F5F5;
            surface = 0xFFFFFFFF;
            surfaceAlt = 0xFFEFEFEF;
            field = 0xFFF5F5F5;
            text = 0xFF0A0A0A;
            muted = 0xFF666666;
            dim = 0xFF8C8C8C;
            border = 0xFFE5E5E5;
            cta = 0xFF0A0A0A;
            ctaText = 0xFFFFFFFF;
            accent = 0xFF0A0A0A;
            link = 0xFF2563EB;
            ok = 0xFF16A34A;
            warn = 0xFFD97706;
            error = 0xFFDC2626;
            codeBg = 0xFF1A1A1A;
            codeText = 0xFFE5E5E5;
            bubbleUser = 0xFF0A0A0A;
            bubbleUserText = 0xFFFFFFFF;
            navBg = 0xFFFFFFFF;
            ripple = 0x1F000000;
            scrim = 0x99000000;
        }
    }

    public static Theme get(Context c) {
        String mode = prefs(c).getString(KEY_MODE, MODE_SYSTEM);
        boolean dark = MODE_DARK.equals(mode)
                || (!MODE_LIGHT.equals(mode) && isSystemDark(c));
        return new Theme(dark);
    }

    public static String mode(Context c) {
        return prefs(c).getString(KEY_MODE, MODE_SYSTEM);
    }

    public static void setMode(Context c, String mode) {
        prefs(c).edit().putString(KEY_MODE, mode).apply();
    }

    public static boolean isSystemDark(Context c) {
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
