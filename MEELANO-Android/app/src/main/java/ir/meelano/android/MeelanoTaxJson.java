package ir.meelano.android;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON for the Moadian app, independent of org.json (which is only a stub in JVM unit tests).
 *
 * Writing follows the signed-bytes rules of the Moadian wire format: compact separators, null members omitted,
 * non-ASCII text left as UTF-8 (never \\uXXXX), numbers in plain notation. Parsing yields
 * {@link LinkedHashMap}, {@link ArrayList}, {@link String}, {@link BigDecimal}, {@link Boolean} or null.
 */
final class MeelanoTaxJson {
    private MeelanoTaxJson() {}

    static String write(Object v) {
        StringBuilder b = new StringBuilder(256);
        write(b, v);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof String) { b.append('"').append(MeelanoTaxCrypto.jsonEscape((String) v)).append('"'); return; }
        if (v instanceof BigDecimal) { b.append(plain((BigDecimal) v)); return; }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("عدد نامعتبر در صورتحساب");
            b.append(plain(BigDecimal.valueOf(d)));
            return;
        }
        if (v instanceof Number || v instanceof Boolean) { b.append(v.toString()); return; }
        if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (e.getValue() == null) continue;
                if (!first) b.append(',');
                first = false;
                b.append('"').append(MeelanoTaxCrypto.jsonEscape(e.getKey())).append("\":");
                write(b, e.getValue());
            }
            b.append('}');
            return;
        }
        if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) b.append(',');
                first = false;
                write(b, o);
            }
            b.append(']');
            return;
        }
        b.append('"').append(MeelanoTaxCrypto.jsonEscape(String.valueOf(v))).append('"');
    }

    static String plain(BigDecimal d) {
        if (d.signum() == 0) return "0";
        String s = d.stripTrailingZeros().toPlainString();
        return s;
    }

    // ------------------------------------------------------------------ parsing

    static Object parse(String text) {
        if (text == null) return null;
        P p = new P(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) throw new IllegalArgumentException("JSON: extra text at " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : null; }

    static Map<String, Object> obj(Map<String, Object> m, String key) { return m == null ? null : obj(m.get(key)); }

    static List<Object> arr(Map<String, Object> m, String key) { return m == null ? null : arr(m.get(key)); }

    static String str(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        if (v == null) return null;
        if (v instanceof BigDecimal) return plain((BigDecimal) v);
        return String.valueOf(v);
    }

    static long num(Map<String, Object> m, String key, long def) {
        if (m == null) return def;
        Object v = m.get(key);
        try {
            if (v instanceof BigDecimal) return ((BigDecimal) v).longValue();
            if (v instanceof Number) return ((Number) v).longValue();
            if (v instanceof String) return new BigDecimal(((String) v).trim()).longValue();
        } catch (Exception ignored) { }
        return def;
    }

    static BigDecimal dec(Map<String, Object> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        try {
            if (v instanceof BigDecimal) return (BigDecimal) v;
            if (v instanceof Number) return new BigDecimal(v.toString());
            if (v instanceof String) return new BigDecimal(((String) v).trim());
        } catch (Exception ignored) { }
        return null;
    }

    private static final class P {
        final String s;
        int i;

        P(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            if (i >= s.length()) throw new IllegalArgumentException("JSON: unexpected end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return a;
            }
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            throw new IllegalArgumentException("JSON: unterminated string");
        }

        BigDecimal number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw new IllegalArgumentException("JSON: unexpected '" + s.charAt(i) + "' at " + i);
            return new BigDecimal(s.substring(st, i));
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("JSON: expected '" + c + "' at " + i);
            i++;
        }
    }
}
