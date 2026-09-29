package ir.meelano.android;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Accurate Persian search for product and customer names coming from Atiran.
 *
 * Atiran stores names typed on many keyboards: Arabic «ي/ك», Persian «ی/ک», «آ/أ/إ», half-spaces, non-breaking
 * spaces, tatweel, dots and dashes between words («پسته.اکبری»، «آب-نبات»), Persian and Latin digits.
 * Both the name and the query are folded to the same form, then every word of the query must appear (in any
 * order). Words written together or apart («آبنبات» / «آب نبات») also match. Results are ranked: exact code,
 * code prefix, name starting with the query, the whole phrase, then all words.
 */
final class MeelanoSearch {
    private MeelanoSearch() { }

    /** Folds letters, digits and separators; the result is lower-case, single-spaced and trimmed. */
    static String norm(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder b = new StringBuilder(s.length());
        boolean space = true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            char o;
            switch (ch) {
                case 'ي': case 'ى': case 'ئ': case 'ۍ': case 'ې': o = 'ی'; break;
                case 'ك': case 'ڪ': o = 'ک'; break;
                case 'ة': case 'ۀ': case 'ە': o = 'ه'; break;
                case 'أ': case 'إ': case 'آ': case 'ٱ': o = 'ا'; break;
                case 'ؤ': o = 'و'; break;
                case 'ـ': continue; // tatweel
                default:
                    if (ch >= '۰' && ch <= '۹') o = (char) ('0' + (ch - '۰'));
                    else if (ch >= '٠' && ch <= '٩') o = (char) ('0' + (ch - '٠'));
                    else if ((ch >= '\u064B' && ch <= '\u065F') || ch == '\u0670') continue; // harakat
                    else if (ch == '\u200E' || ch == '\u200F' || ch == '\u202A' || ch == '\u202B' || ch == '\u202C' || ch == '\uFEFF') continue;
                    else if (Character.isLetterOrDigit(ch)) o = Character.toLowerCase(ch);
                    else o = ' '; // spaces, half-space (U+200C), NBSP, . - / ( ) ، ؛ …
            }
            if (o == ' ') { if (!space) { b.append(' '); space = true; } }
            else { b.append(o); space = false; }
        }
        int n = b.length();
        if (n > 0 && b.charAt(n - 1) == ' ') b.setLength(n - 1);
        return b.toString();
    }

    static String compact(String normalized) { return normalized == null ? "" : normalized.replace(" ", ""); }

    static List<String> tokens(String normalizedQuery) {
        List<String> out = new ArrayList<>();
        if (normalizedQuery == null) return out;
        for (String t : normalizedQuery.split(" ")) if (!t.isEmpty() && !out.contains(t)) out.add(t);
        return out;
    }

    /** A prepared query: build once, test against many rows. */
    static final class Query {
        final String text, flat;
        final List<String> words;
        final boolean digits;

        Query(String raw) {
            text = norm(raw);
            flat = compact(text);
            words = tokens(text);
            boolean d = !flat.isEmpty();
            for (int i = 0; i < flat.length() && d; i++) d = flat.charAt(i) >= '0' && flat.charAt(i) <= '9';
            digits = d;
        }

        boolean isEmpty() { return text.isEmpty(); }

        /**
         * Rank of a match (lower is better), or -1 when the row does not match.
         * @param name  product / customer name
         * @param code  own code (exact and prefix matches rank first)
         * @param other extra searchable text (barcode, group, unit, phone, address …)
         */
        int rank(String name, String code, String other) {
            if (text.isEmpty()) return 50;
            String c = compact(norm(code));
            if (!c.isEmpty()) {
                if (c.equals(flat)) return 0;
                if (digits && c.startsWith(flat)) return 1;
            }
            String n = norm(name);
            String o = norm(other);
            if (n.startsWith(text)) return 2;
            if (n.contains(text)) return (n.contains(" " + text) ? 3 : 4);
            String hay = n + " " + c + " " + o;
            boolean all = true;
            for (String w : words) if (!hay.contains(w)) { all = false; break; }
            if (all) return 5;
            // Written together / apart: «آبنبات» ↔ «آب نبات», «پسته اکبری» ↔ «پستهاکبری».
            String hayFlat = compact(hay);
            if (flat.length() >= 2 && hayFlat.contains(flat)) return 6;
            boolean allFlat = words.size() > 1;
            for (String w : words) if (!hayFlat.contains(w)) { allFlat = false; break; }
            if (allFlat) return 7;
            if (digits && !c.isEmpty() && c.contains(flat)) return 8;
            return -1;
        }
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.US); }
}
