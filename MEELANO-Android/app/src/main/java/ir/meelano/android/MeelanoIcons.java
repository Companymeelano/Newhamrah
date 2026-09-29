package ir.meelano.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One icon language for the whole app.
 *
 * The UI code historically decorates labels with Unicode glyphs and emoji (✓, ⟳, 🛒 …) whose look
 * depends on the phone's fonts.  Instead of rewriting hundreds of call sites, this class overlays
 * every known glyph with a vector icon drawn from the SVG set in {@code icons-svg/} (Material
 * Symbols Rounded, converted to {@code res/drawable/mi_*.xml}).  The glyph characters stay in the
 * text, so {@code getText().toString()} keeps working for code that reads labels back; only the
 * rendering changes.  Icons take the text colour and size of the TextView they live in.
 */
final class MeelanoIcons {
    private MeelanoIcons() {}

    /** Glyph → drawable, replaced wherever it appears. */
    private static final Map<String, Integer> ANYWHERE = new HashMap<>();
    /** Glyph → drawable, replaced only when it is the whole label or a leading glyph followed by a space. */
    private static final Map<String, Integer> LEADING = new HashMap<>();
    /** Glyphs that express a state; their icon is never changed by the words that follow. */
    private static final Set<String> STATUS = new HashSet<>();
    /** Label keyword → drawable, used to pick a more specific icon for action-style leading glyphs. */
    private static final String[][] KEYWORDS = {
            {"ارسال", "send"}, {"حذف", "delete"}, {"ویرایش", "edit"}, {"جستجو", "search"}, {"تماس", "call"},
            {"بروزرسان", "refresh"}, {"تازه", "refresh"}, {"تنظیم", "settings"}, {"خروج", "logout"},
            {"بازگشت", "arrow_forward"}, {"ذخیره", "save"}, {"افزودن", "add"}, {"PDF", "picture_as_pdf"},
            {"چاپ", "print"}, {"اشتراک", "share"}, {"بی‌صدا", "notifications_off"}, {"سکوت", "notifications_off"},
            {"مودم", "wifi"}
    };
    private static final Map<String, Integer> BY_NAME = new HashMap<>();

    static {
        name("send", R.drawable.mi_send); name("delete", R.drawable.mi_delete); name("edit", R.drawable.mi_edit);
        name("search", R.drawable.mi_search); name("call", R.drawable.mi_call); name("refresh", R.drawable.mi_refresh);
        name("settings", R.drawable.mi_settings); name("logout", R.drawable.mi_logout); name("arrow_forward", R.drawable.mi_arrow_forward);
        name("save", R.drawable.mi_save); name("add", R.drawable.mi_add); name("picture_as_pdf", R.drawable.mi_picture_as_pdf);
        name("print", R.drawable.mi_print); name("share", R.drawable.mi_share); name("notifications_off", R.drawable.mi_notifications_off);
        name("wifi", R.drawable.mi_wifi);

        any("✓", R.drawable.mi_check); any("✔", R.drawable.mi_check); any("✅", R.drawable.mi_check_circle);
        any("❌", R.drawable.mi_cancel); any("✕", R.drawable.mi_close); any("✖", R.drawable.mi_close);
        any("✦", R.drawable.mi_star_shine); any("✨", R.drawable.mi_star_shine); any("◆", R.drawable.mi_diamond);
        any("◇", R.drawable.mi_inbox); any("★", R.drawable.mi_star_fill); any("🎉", R.drawable.mi_celebration);
        any("😊", R.drawable.mi_sentiment_satisfied); any("↗", R.drawable.mi_trending_up); any("↙", R.drawable.mi_shopping_bag);
        any("↘", R.drawable.mi_login); any("⎋", R.drawable.mi_logout); any("⇧", R.drawable.mi_upload); any("↩", R.drawable.mi_undo);
        any("♙", R.drawable.mi_person); any("👤", R.drawable.mi_person); any("👥", R.drawable.mi_group);
        any("🧑", R.drawable.mi_badge); any("💼", R.drawable.mi_badge); any("🪪", R.drawable.mi_badge);
        any("♛", R.drawable.mi_admin_panel_settings); any("◈", R.drawable.mi_inventory_2); any("◍", R.drawable.mi_inventory_2);
        any("◼", R.drawable.mi_category); any("▦", R.drawable.mi_grid_view); any("▣", R.drawable.mi_widgets);
        any("▤", R.drawable.mi_receipt_long); any("📋", R.drawable.mi_assignment); any("◷", R.drawable.mi_schedule);
        any("⏱", R.drawable.mi_timer); any("⟳", R.drawable.mi_refresh); any("⇅", R.drawable.mi_sync); any("🔄", R.drawable.mi_sync);
        any("↺", R.drawable.mi_restart_alt); any("⇄", R.drawable.mi_compare_arrows); any("⌕", R.drawable.mi_search);
        any("⚙", R.drawable.mi_settings); any("◎", R.drawable.mi_track_changes); any("◉", R.drawable.mi_account_balance);
        any("⌁", R.drawable.mi_bolt); any("🔐", R.drawable.mi_lock); any("🛒", R.drawable.mi_shopping_cart);
        any("⊕", R.drawable.mi_add_shopping_cart); any("🏬", R.drawable.mi_store); any("🏷", R.drawable.mi_sell);
        any("₿", R.drawable.mi_sell); any("☘", R.drawable.mi_beach_access); any("◬", R.drawable.mi_shield);
        any("◌", R.drawable.mi_radio_button_unchecked); any("○", R.drawable.mi_radio_button_unchecked); any("◐", R.drawable.mi_contrast);
        any("⌖", R.drawable.mi_my_location); any("⌾", R.drawable.mi_pin_drop); any("📍", R.drawable.mi_location_on);
        any("□", R.drawable.mi_draft); any("✉", R.drawable.mi_mail); any("💬", R.drawable.mi_chat);
        any("☷", R.drawable.mi_account_balance_wallet); any("💵", R.drawable.mi_payments); any("🔒", R.drawable.mi_lock); any("🧾", R.drawable.mi_request_quote); any("☰", R.drawable.mi_menu); any("☎", R.drawable.mi_call);
        any("🔊", R.drawable.mi_volume_up); any("♪", R.drawable.mi_mic); any("🎙", R.drawable.mi_mic); any("▧", R.drawable.mi_image);
        any("▶", R.drawable.mi_play_circle); any("📷", R.drawable.mi_photo_camera); any("⊘", R.drawable.mi_block);
        any("⚠", R.drawable.mi_warning); any("△", R.drawable.mi_warning); any("▮", R.drawable.mi_bar_chart); any("📊", R.drawable.mi_bar_chart);
        any("✺", R.drawable.mi_palette); any("✎", R.drawable.mi_edit); any("⌂", R.drawable.mi_home); any("☀", R.drawable.mi_light_mode);
        any("☾", R.drawable.mi_dark_mode); any("🌅", R.drawable.mi_wb_twilight); any("🌙", R.drawable.mi_bedtime);
        any("🏅", R.drawable.mi_military_tech); any("💡", R.drawable.mi_lightbulb); any("⌘", R.drawable.mi_keyboard_command_key);
        any("⋯", R.drawable.mi_more_horiz);
        any("🚗", R.drawable.mi_directions_car); any("🏁", R.drawable.mi_flag); any("☝", R.drawable.mi_fingerprint);
        any("⧗", R.drawable.mi_assignment_late); any("⌛", R.drawable.mi_work_history);
        any("🚚", R.drawable.mi_local_shipping); any("✍", R.drawable.mi_edit); any("📞", R.drawable.mi_call); any("🗺", R.drawable.mi_location_on);
        any("⇆", R.drawable.mi_swap_horiz); any("📦", R.drawable.mi_inventory_2); any("💳", R.drawable.mi_payments); any("⊗", R.drawable.mi_cancel); any("⇪", R.drawable.mi_share);

        lead("×", R.drawable.mi_close); lead("−", R.drawable.mi_remove); lead("+", R.drawable.mi_add); lead("＋", R.drawable.mi_add);
        lead("›", R.drawable.mi_chevron_left); lead("●", R.drawable.mi_fiber_manual_record_fill); lead("▲", R.drawable.mi_trending_up);
        lead("▼", R.drawable.mi_trending_down); lead("!", R.drawable.mi_priority_high); lead("٪", R.drawable.mi_percent);

        for (String g : new String[]{"✓", "✔", "✅", "❌", "⚠", "△", "!", "●", "◌", "○", "◐", "★", "⏱", "◷", "▲", "▼", "×", "−", "+", "＋", "›", "٪",
                "☀", "☾", "🌅", "🌙", "🎉", "😊", "🏅"}) STATUS.add(g);
    }

    private static void any(String g, int res) { ANYWHERE.put(g, res); }
    private static void lead(String g, int res) { LEADING.put(g, res); }
    private static void name(String n, int res) { BY_NAME.put(n, res); }

    /** Vector drawable for a glyph (or 0). Useful for ImageViews that want the same icon as a label. */
    static int iconFor(String glyph) {
        if (glyph == null) return 0;
        Integer r = ANYWHERE.get(glyph);
        if (r == null) r = LEADING.get(glyph);
        return r == null ? 0 : r;
    }

    /** Returns true when the text contains at least one glyph that {@link #iconize} would replace. */
    static boolean hasGlyph(CharSequence text) {
        if (text == null || text.length() == 0) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            int n = Character.charCount(cp);
            if (cp >= 0x2000 || cp == '+' || cp == '!' || cp == 0x066A || cp == 0x00D7) {
                String g = new String(Character.toChars(cp));
                if (ANYWHERE.containsKey(g) || (LEADING.containsKey(g) && leadingAt(text, i, n))) return true;
            }
            i += n;
        }
        return false;
    }

    private static boolean leadingAt(CharSequence t, int start, int len) {
        for (int i = 0; i < start; i++) if (!Character.isWhitespace(t.charAt(i)) && t.charAt(i) != '\u200f' && t.charAt(i) != '\u200e') return false;
        int after = start + len;
        if (after >= t.length()) return true;
        String rest = t.subSequence(after, t.length()).toString().trim();
        if (rest.isEmpty()) return true;
        return Character.isWhitespace(t.charAt(after));
    }

    /** Builds a copy of {@code text} in which every known glyph is drawn as a vector icon. */
    static CharSequence iconize(Context ctx, CharSequence text) {
        if (ctx == null || !hasGlyph(text)) return text;
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        boolean first = true;
        for (int i = 0; i < sb.length(); ) {
            int cp = Character.codePointAt(sb, i);
            int n = Character.charCount(cp);
            String g = new String(Character.toChars(cp));
            int res = 0;
            Integer a = ANYWHERE.get(g);
            if (a != null) res = a;
            else if (LEADING.containsKey(g) && leadingAt(sb, i, n)) res = LEADING.get(g);
            if (res != 0) {
                int end = i + n;
                // Swallow emoji presentation selectors and ZWJ sequences (🧑‍💼) so one icon covers the whole emoji.
                while (end < sb.length()) {
                    char c = sb.charAt(end);
                    if (c == '\uFE0F' || c == '\uFE0E') { end++; continue; }
                    if (c == '\u200D' && end + 1 < sb.length()) { int nx = Character.codePointAt(sb, end + 1); end += 1 + Character.charCount(nx); continue; }
                    break;
                }
                if (first && !STATUS.contains(g)) {
                    int k = keywordIcon(sb.subSequence(end, sb.length()).toString());
                    if (k != 0) res = k;
                }
                first = false;
                if (sb.getSpans(i, end, IconSpan.class).length == 0) sb.setSpan(new IconSpan(ctx, res), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                i = end;
                continue;
            }
            if (!Character.isWhitespace(cp)) first = false;
            i += n;
        }
        return sb;
    }

    private static int keywordIcon(String label) {
        if (label == null) return 0;
        String t = label.trim();
        if (t.isEmpty()) return 0;
        // Only look at the first words: "⟳ بروزرسانی کالا" → refresh, not an unrelated word later on.
        if (t.length() > 24) t = t.substring(0, 24);
        for (String[] kv : KEYWORDS) if (t.contains(kv[0])) { Integer r = BY_NAME.get(kv[1]); if (r != null) return r; }
        return 0;
    }

    /** Applies {@link #iconize} to one TextView (EditTexts are left alone so typing is never disturbed). */
    static void iconize(TextView tv) {
        if (tv == null || tv instanceof EditText) return;
        CharSequence t = tv.getText();
        if (t == null || t.length() == 0) return;
        Object seen = tv.getTag(R.id.meelano_icon_scan);
        if (seen == t) return;
        if (t instanceof Spanned && ((Spanned) t).getSpans(0, t.length(), IconSpan.class).length > 0) { tv.setTag(R.id.meelano_icon_scan, t); return; }
        if (hasGlyph(t)) {
            try { tv.setText(iconize(tv.getContext(), t)); } catch (Exception ignored) { }
        }
        tv.setTag(R.id.meelano_icon_scan, tv.getText());
    }

    /** Walks a view tree and iconizes every label. Cheap enough to run after each layout pass. */
    static void iconizeTree(View v) {
        if (v == null) return;
        if (v instanceof TextView) { iconize((TextView) v); return; }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) iconizeTree(g.getChildAt(i));
        }
    }

    /** Draws a vector icon in place of the glyph, tinted with the current text colour. */
    static final class IconSpan extends ReplacementSpan {
        private static final float SCALE = 1.22f;
        private final Drawable icon;

        IconSpan(Context ctx, int res) {
            Drawable d = null;
            try { d = ctx.getResources().getDrawable(res, ctx.getTheme()).mutate(); } catch (Exception ignored) { }
            icon = d;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                Paint.FontMetricsInt p = paint.getFontMetricsInt();
                fm.ascent = p.ascent; fm.descent = p.descent; fm.top = p.top; fm.bottom = p.bottom; fm.leading = p.leading;
            }
            if (icon == null) return Math.round(paint.measureText(text, start, end));
            return Math.round(paint.getTextSize() * SCALE);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            if (icon == null) { canvas.drawText(text, start, end, x, y, paint); return; }
            int size = Math.round(paint.getTextSize() * SCALE);
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            int centerY = y + (fm.ascent + fm.descent) / 2;
            int t = centerY - size / 2;
            icon.setBounds(0, 0, size, size);
            icon.setTint(paint.getColor());
            icon.setAlpha(paint.getAlpha());
            canvas.save();
            canvas.translate(x, t);
            icon.draw(canvas);
            canvas.restore();
        }
    }
}
