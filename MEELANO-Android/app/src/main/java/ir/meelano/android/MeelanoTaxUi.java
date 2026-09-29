package ir.meelano.android;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * View toolkit of the Moadian app — theme «فیروزه نیشابور»: a light turquoise/teal palette with a warm gold accent,
 * matched to the launcher icon. Everything is built in code (like the rest of the project) with Vazirmatn and the
 * project's SVG (vector) icons.
 */
final class MeelanoTaxUi {
    // Palette (light).
    static final int BG = 0xFFF2F9F8;
    static final int SURFACE = 0xFFFFFFFF;
    static final int SURFACE_2 = 0xFFF7FBFB;
    static final int PRIMARY = 0xFF0B6A70;
    static final int PRIMARY_DARK = 0xFF074C51;
    static final int PRIMARY_SOFT = 0xFFDCEFEE;
    static final int GOLD = 0xFFB98A3C;
    static final int GOLD_SOFT = 0xFFF7EEDD;
    static final int TEXT = 0xFF12302F;
    static final int MUTED = 0xFF5D7674;
    static final int LINE = 0xFFD2E4E2;
    static final int OK = 0xFF1C7F52;
    static final int OK_SOFT = 0xFFE1F2E8;
    static final int WARN = 0xFF9A5B0C;
    static final int WARN_SOFT = 0xFFFBEFD9;
    static final int ERR = 0xFFB3352A;
    static final int ERR_SOFT = 0xFFFBE5E2;
    static final int IDLE = 0xFF45625F;
    static final int IDLE_SOFT = 0xFFE7F0EF;
    static final int WAIT = 0xFF1F5E9C;
    static final int WAIT_SOFT = 0xFFE2EEF9;

    static final int BTN_PRIMARY = 0, BTN_SECONDARY = 1, BTN_DANGER = 2, BTN_GHOST = 3, BTN_GOLD = 4;

    final Activity ctx;
    final float density;
    final Typeface regular;
    final Typeface bold;

    MeelanoTaxUi(Activity ctx) {
        this.ctx = ctx;
        this.density = ctx.getResources().getDisplayMetrics().density;
        Typeface r = Typeface.DEFAULT, b = Typeface.DEFAULT_BOLD;
        try { r = Typeface.createFromAsset(ctx.getAssets(), "fonts/Vazirmatn-Regular.ttf"); } catch (Exception ignored) { }
        try { b = Typeface.createFromAsset(ctx.getAssets(), "fonts/Vazirmatn-Bold.ttf"); } catch (Exception ignored) { }
        regular = r;
        bold = b;
    }

    int dp(float v) { return Math.round(v * density); }

    // ------------------------------------------------------------------ numbers

    /** Western digits → Persian digits (for display only). */
    static String fa(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) b.append(c >= '0' && c <= '9' ? (char) ('۰' + (c - '0')) : c);
        return b.toString();
    }

    /** Rial amount with 3-digit grouping in Persian digits. */
    static String money(long v) { return fa(String.format(Locale.US, "%,d", v)).replace(',', '٬'); }

    static String rial(long v) { return money(v) + " ریال"; }

    // ------------------------------------------------------------------ drawables

    GradientDrawable round(int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return g;
    }

    Drawable pressable(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    Drawable tinted(int res, int color) {
        Drawable d;
        try { d = ctx.getResources().getDrawable(res, ctx.getTheme()).mutate(); } catch (Exception e) { return null; }
        d.setTint(color);
        return d;
    }

    // ------------------------------------------------------------------ layout

    LinearLayout v() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout h() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w); }

    LinearLayout.LayoutParams margins(int w, int h, float start, float top, float end, float bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(end), dp(top), dp(start), dp(bottom));
        p.setMarginStart(dp(start));
        p.setMarginEnd(dp(end));
        return p;
    }

    LinearLayout.LayoutParams full(float top, float bottom) {
        return margins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, top, 0, bottom);
    }

    View space(float heightDp) {
        View s = new View(ctx);
        s.setLayoutParams(lp(1, dp(heightDp)));
        return s;
    }

    View divider() {
        View d = new View(ctx);
        d.setBackgroundColor(LINE);
        d.setLayoutParams(full(8, 8));
        d.getLayoutParams().height = Math.max(1, dp(1));
        return d;
    }

    ScrollView scroll(View child) {
        ScrollView s = new ScrollView(ctx);
        s.setFillViewport(true);
        s.setClipToPadding(false);
        s.addView(child, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    /** A page column with the standard padding. */
    LinearLayout page() {
        LinearLayout l = v();
        l.setPadding(dp(16), dp(12), dp(16), dp(96));
        return l;
    }

    LinearLayout card() {
        LinearLayout c = v();
        c.setBackground(round(SURFACE, 18, LINE, 1));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setElevation(dp(1));
        c.setLayoutParams(full(0, 12));
        return c;
    }

    LinearLayout softCard(int fill, int stroke) {
        LinearLayout c = v();
        c.setBackground(round(fill, 16, stroke, 1));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setLayoutParams(full(0, 12));
        return c;
    }

    // ------------------------------------------------------------------ text

    TextView text(CharSequence s, float sp, int color, boolean isBold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(isBold ? bold : regular);
        t.setLineSpacing(0, 1.18f);
        t.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    TextView title(String s) { return text(s, 17, TEXT, true); }

    TextView heading(String s) {
        TextView t = text(s, 14.5f, PRIMARY_DARK, true);
        t.setLayoutParams(full(4, 8));
        return t;
    }

    TextView body(String s) { return text(s, 13.5f, TEXT, false); }

    TextView caption(String s) { return text(s, 12, MUTED, false); }

    /** Left-to-right value (tax ids, codes, JSON) that stays readable inside RTL text. */
    TextView ltr(String s, float sp, int color, boolean isBold) {
        TextView t = text(s, sp, color, isBold);
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setTextIsSelectable(true);
        return t;
    }

    ImageView icon(int res, int color, float sizeDp) {
        ImageView i = new ImageView(ctx);
        i.setImageDrawable(tinted(res, color));
        i.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return i;
    }

    /** Label/value row. */
    LinearLayout row(String label, CharSequence value) {
        LinearLayout r = h();
        r.setPadding(0, dp(4), 0, dp(4));
        TextView l = caption(label);
        r.addView(l, weight(1));
        TextView v = text(value == null || value.length() == 0 ? "—" : value, 13.5f, TEXT, true);
        v.setGravity(Gravity.END);
        v.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        r.addView(v, weight(1.4f));
        return r;
    }

    /** Label/value row whose value is a left-to-right code. */
    LinearLayout rowLtr(String label, String value) {
        LinearLayout r = h();
        r.setPadding(0, dp(4), 0, dp(4));
        r.addView(caption(label), weight(1));
        TextView v = ltr(value == null || value.isEmpty() ? "—" : value, 13, TEXT, true);
        v.setGravity(Gravity.END);
        r.addView(v, weight(1.6f));
        return r;
    }

    // ------------------------------------------------------------------ controls

    int[] tone(String key) {
        switch (key == null ? "" : key) {
            case "ok": return new int[]{OK, OK_SOFT};
            case "warn": return new int[]{WARN, WARN_SOFT};
            case "err": return new int[]{ERR, ERR_SOFT};
            case "wait": return new int[]{WAIT, WAIT_SOFT};
            case "gold": return new int[]{GOLD, GOLD_SOFT};
            case "primary": return new int[]{PRIMARY, PRIMARY_SOFT};
            case "off": return new int[]{MUTED, 0xFFEDEFEF};
            default: return new int[]{IDLE, IDLE_SOFT};
        }
    }

    int toneIcon(String key) {
        switch (key == null ? "" : key) {
            case "ok": return R.drawable.mi_task_alt;
            case "warn": return R.drawable.mi_warning;
            case "err": return R.drawable.mi_error;
            case "wait": return R.drawable.mi_hourglass_top;
            case "off": return R.drawable.mi_block;
            default: return R.drawable.mi_pending;
        }
    }

    /** Status pill with an icon. */
    LinearLayout badge(String label, String toneKey) {
        int[] t = tone(toneKey);
        LinearLayout b = h();
        b.setBackground(round(t[1], 20, 0, 0));
        b.setPadding(dp(9), dp(3), dp(9), dp(3));
        b.addView(icon(toneIcon(toneKey), t[0], 14));
        TextView tv = text(label, 11.5f, t[0], true);
        tv.setPadding(dp(4), 0, 0, 0);
        tv.setSingleLine(true);
        b.addView(tv);
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(p);
        return b;
    }

    /** Button with an SVG icon. */
    LinearLayout button(String label, int iconRes, int kind, View.OnClickListener click) {
        int fill, fg, stroke = 0;
        switch (kind) {
            case BTN_SECONDARY: fill = PRIMARY_SOFT; fg = PRIMARY_DARK; break;
            case BTN_DANGER: fill = ERR_SOFT; fg = ERR; break;
            case BTN_GHOST: fill = SURFACE; fg = PRIMARY; stroke = LINE; break;
            case BTN_GOLD: fill = GOLD; fg = Color.WHITE; break;
            default: fill = PRIMARY; fg = Color.WHITE; break;
        }
        LinearLayout b = h();
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(dp(46));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(pressable(round(fill, 14, stroke, stroke == 0 ? 0 : 1), kind == BTN_PRIMARY || kind == BTN_GOLD ? 0x33FFFFFF : 0x220B6A70));
        if (iconRes != 0) b.addView(icon(iconRes, fg, 19));
        TextView t = text(label, 13.5f, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(iconRes != 0 ? dp(7) : 0, 0, 0, 0);
        b.addView(t);
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(label);
        b.setOnClickListener(click);
        b.setLayoutParams(full(4, 4));
        return b;
    }

    /** Two buttons side by side. */
    LinearLayout pair(View a, View b) {
        LinearLayout r = h();
        LinearLayout.LayoutParams pa = weight(1);
        pa.setMarginEnd(dp(6));
        LinearLayout.LayoutParams pb = weight(1);
        pb.setMarginStart(dp(6));
        r.addView(a, pa);
        r.addView(b, pb);
        r.setLayoutParams(full(2, 2));
        return r;
    }

    TextView chip(String label, boolean selected, View.OnClickListener click) {
        TextView c = text(label, 12.5f, selected ? Color.WHITE : PRIMARY_DARK, true);
        c.setSingleLine(true);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(13), dp(7), dp(13), dp(7));
        c.setBackground(pressable(round(selected ? PRIMARY : SURFACE, 20, selected ? PRIMARY : LINE, 1), 0x220B6A70));
        c.setOnClickListener(click);
        LinearLayout.LayoutParams p = lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMarginEnd(dp(6));
        p.bottomMargin = dp(6);
        c.setLayoutParams(p);
        return c;
    }

    /** Horizontally scrolling chip row. */
    View chipRow(View... chips) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = h();
        for (View c : chips) r.addView(c);
        hs.addView(r);
        hs.setLayoutParams(full(2, 6));
        return hs;
    }

    EditText input(String hint, String value, int inputType) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setInputType(inputType == 0 ? InputType.TYPE_CLASS_TEXT : inputType);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setTypeface(regular);
        e.setTextColor(TEXT);
        e.setHintTextColor(0xFF93A9A7);
        e.setBackground(round(SURFACE_2, 12, LINE, 1));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        e.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        return e;
    }

    EditText ltrInput(String hint, String value, int inputType) {
        EditText e = input(hint, value, inputType);
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        e.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return e;
    }

    /** Labelled field. */
    LinearLayout field(String label, View input, String help) {
        LinearLayout f = v();
        TextView l = text(label, 12.5f, PRIMARY_DARK, true);
        l.setPadding(0, 0, 0, dp(4));
        f.addView(l);
        f.addView(input, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (help != null && !help.isEmpty()) {
            TextView hv = caption(help);
            hv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            hv.setPadding(0, dp(3), 0, 0);
            f.addView(hv);
        }
        f.setLayoutParams(full(4, 8));
        return f;
    }

    CheckBox check(String label, boolean checked) {
        CheckBox c = new CheckBox(ctx);
        c.setText(label);
        c.setChecked(checked);
        c.setTypeface(regular);
        c.setTextColor(TEXT);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        c.setButtonTintList(ColorStateList.valueOf(PRIMARY));
        return c;
    }

    /** Issue line (error or warning) with its icon. */
    LinearLayout issue(String text, boolean error) {
        LinearLayout r = h();
        r.setGravity(Gravity.TOP);
        r.setPadding(0, dp(3), 0, dp(3));
        ImageView i = icon(error ? R.drawable.mi_error : R.drawable.mi_warning, error ? ERR : WARN, 17);
        r.addView(i);
        TextView t = text(text, 12.5f, error ? ERR : WARN, false);
        t.setPadding(dp(6), 0, 0, 0);
        r.addView(t, weight(1));
        return r;
    }

    /** Checklist line for readiness. */
    LinearLayout checkLine(String label, String detail, boolean ok, boolean optional) {
        LinearLayout r = h();
        r.setPadding(0, dp(5), 0, dp(5));
        r.addView(icon(ok ? R.drawable.mi_check_circle : (optional ? R.drawable.mi_radio_button_unchecked : R.drawable.mi_cancel), ok ? OK : (optional ? MUTED : ERR), 20));
        LinearLayout col = v();
        col.setPadding(dp(8), 0, 0, 0);
        col.addView(text(label, 13.5f, TEXT, true));
        if (detail != null && !detail.isEmpty()) col.addView(caption(detail));
        r.addView(col, weight(1));
        return r;
    }

    /** Stat tile. */
    LinearLayout stat(String label, String value, int iconRes, String toneKey, View.OnClickListener click) {
        int[] t = tone(toneKey);
        LinearLayout c = v();
        c.setBackground(pressable(round(SURFACE, 16, LINE, 1), 0x220B6A70));
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout top = h();
        LinearLayout ib = h();
        ib.setGravity(Gravity.CENTER);
        ib.setBackground(round(t[1], 12, 0, 0));
        ib.addView(icon(iconRes, t[0], 20));
        top.addView(ib, lp(dp(36), dp(36)));
        c.addView(top);
        TextView v = text(value, 20, TEXT, true);
        v.setPadding(0, dp(8), 0, 0);
        c.addView(v);
        c.addView(caption(label));
        if (click != null) { c.setClickable(true); c.setOnClickListener(click); }
        return c;
    }

    // ------------------------------------------------------------------ dialogs

    /** Themed bottom-style dialog with a custom body. Returns it (already shown). */
    /** Positive action of a form dialog: return false to keep the dialog open (e.g. invalid input). */
    interface Check { boolean ok(); }

    Dialog sheet(String titleText, View bodyView, String positive, Runnable onPositive, String negative) {
        return form(titleText, bodyView, positive, onPositive == null ? null : () -> { onPositive.run(); return true; }, negative);
    }

    /** Like {@link #sheet} but the dialog closes only when {@code onPositive} returns true. */
    Dialog form(String titleText, View bodyView, String positive, Check onPositive, String negative) {
        Dialog d = new Dialog(ctx);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = v();
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setBackground(round(SURFACE, 22, 0, 0));
        box.setPadding(dp(18), dp(16), dp(18), dp(12));
        TextView t = title(titleText);
        t.setPadding(0, 0, 0, dp(8));
        box.addView(t);
        if (bodyView != null) {
            ScrollView sv = new ScrollView(ctx);
            sv.addView(bodyView);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            box.addView(sv, p);
        }
        LinearLayout actions = h();
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(10), 0, 0);
        if (negative != null) {
            TextView n = text(negative, 14, MUTED, true);
            n.setPadding(dp(14), dp(10), dp(14), dp(10));
            n.setBackground(pressable(new ColorDrawable(Color.TRANSPARENT), 0x220B6A70));
            n.setOnClickListener(x -> d.dismiss());
            actions.addView(n);
        }
        if (positive != null) {
            TextView p = text(positive, 14, Color.WHITE, true);
            p.setPadding(dp(18), dp(10), dp(18), dp(10));
            p.setBackground(pressable(round(PRIMARY, 12, 0, 0), 0x33FFFFFF));
            p.setOnClickListener(x -> {
                if (onPositive == null) { d.dismiss(); return; }
                boolean close;
                try { close = onPositive.ok(); } catch (RuntimeException e) { d.dismiss(); throw e; }
                if (close) d.dismiss();
            });
            actions.addView(p);
        }
        box.addView(actions);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams a = w.getAttributes();
            a.width = Math.min(ctx.getResources().getDisplayMetrics().widthPixels - dp(24), dp(560));
            a.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            w.setAttributes(a);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        // Cap the height so long bodies scroll instead of pushing the buttons off-screen.
        box.post(() -> {
            int max = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.86f);
            if (box.getHeight() > max && w != null) {
                WindowManager.LayoutParams a = w.getAttributes();
                a.height = max;
                w.setAttributes(a);
            }
        });
        d.show();
        return d;
    }

    Dialog message(String titleText, String msg) {
        TextView t = body(msg);
        t.setTextIsSelectable(true);
        return sheet(titleText, t, "باشه", null, null);
    }

    Dialog confirm(String titleText, String msg, String yes, Runnable onYes) {
        TextView t = body(msg);
        return sheet(titleText, t, yes, onYes, "انصراف");
    }

    static String ellipsize(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    static boolean empty(String s) { return TextUtils.isEmpty(s == null ? null : s.trim()); }
}
