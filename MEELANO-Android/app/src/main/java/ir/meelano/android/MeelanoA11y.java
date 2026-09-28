package ir.meelano.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Screen-reader (TalkBack) clean-up for the whole app.
 *
 * The UI uses many decorative symbols (✓ ★ ◆ › …) inside text. TalkBack would read them aloud as
 * "check mark", "black star" and so on. While a screen reader is on, this pass:
 *  - hides views whose text is only symbols (pure decoration), unless they are clickable;
 *  - gives other text views a spoken label without the symbols.
 * It re-runs (debounced) after every layout, so labels follow text changes. It does nothing when no
 * screen reader is active, so there is no cost for normal use.
 */
final class MeelanoA11y {
    private MeelanoA11y() { }

    private static final long DEBOUNCE_MS = 350L;

    static void install(final View root) {
        if (root == null) return;
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] scheduled = {false};
        final Runnable pass = () -> {
            scheduled[0] = false;
            if (screenReaderOn(root.getContext())) apply(root);
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (scheduled[0]) return;
                if (!screenReaderOn(root.getContext())) return;
                scheduled[0] = true;
                handler.postDelayed(pass, DEBOUNCE_MS);
            }
        });
    }

    static boolean screenReaderOn(Context c) {
        try {
            AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            return am != null && am.isEnabled() && am.isTouchExplorationEnabled();
        } catch (Exception ignored) {
            return false;
        }
    }

    static void apply(View v) {
        if (v == null) return;
        if (v instanceof TextView && !(v instanceof EditText)) label((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }

    private static void label(TextView t) {
        CharSequence cs = t.getText();
        String text = cs == null ? "" : cs.toString();
        CharSequence existing = t.getContentDescription();
        Object ours = t.getTag(R.id.meelano_a11y_label);
        // Only touch descriptions we set ourselves; a description set by the screen code wins.
        boolean ownDescription = existing == null || (ours != null && ours.toString().contentEquals(existing));
        if (!ownDescription) return;
        String spoken = spokenText(text);
        if (spoken.isEmpty()) {
            if (!t.isClickable()) {
                t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                t.setContentDescription(null);
                t.setTag(R.id.meelano_a11y_label, null);
            }
            return;
        }
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (spoken.equals(text.trim())) { t.setContentDescription(null); t.setTag(R.id.meelano_a11y_label, null); }
        else { t.setContentDescription(spoken); t.setTag(R.id.meelano_a11y_label, spoken); }
    }

    /** Text with decorative symbols removed; empty when nothing readable is left. */
    static String spokenText(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        boolean readable = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            boolean decorative = type == Character.OTHER_SYMBOL || type == Character.MATH_SYMBOL && cp != '+' && cp != '-' && cp != '='
                    || cp == '›' || cp == '‹' || cp == '•' || cp == '·' || cp == 0xFE0F || cp == 0x200D;
            if (decorative) { b.append(' '); continue; }
            if (Character.isLetterOrDigit(cp)) readable = true;
            b.appendCodePoint(cp);
        }
        if (!readable) return "";
        return b.toString().replaceAll("\\s+", " ").trim();
    }
}
