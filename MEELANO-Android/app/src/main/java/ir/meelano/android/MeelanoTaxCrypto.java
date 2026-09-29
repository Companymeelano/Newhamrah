package ir.meelano.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

/**
 * Cryptography of the Moadian (سامانه مودیان) v2 API, in plain Java so it is unit-testable on the JVM.
 *
 * <ul>
 *   <li>JWS (RS256) with the exact protected header of RC_TICS.IS_v1.6: {@code crit, sigT, x5c, alg} in that order.</li>
 *   <li>JWE (RSA-OAEP-256 + A256GCM) with an explicit OAEP SHA-256 / MGF1-SHA-256 parameter spec (the provider
 *       defaults of "OAEPWithSHA-256AndMGF1Padding" differ between Android and the JVM).</li>
 *   <li>The 22-character tax id (شماره منحصر به فرد مالیاتی) with its Verhoeff check digit.</li>
 *   <li>RSA-2048 key generation, a hand-built PKCS#10 CSR in the format the tax organisation requires, PEM and
 *       PKCS#12 (PFX) import, and certificate inspection.</li>
 * </ul>
 */
final class MeelanoTaxCrypto {
    private MeelanoTaxCrypto() {}

    // ------------------------------------------------------------------ base64

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final char[] B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    static String b64(byte[] data) { return encode(data, B64, true); }

    static String b64url(byte[] data) { return encode(data, B64URL, false); }

    static String b64url(String utf8) { return b64url(utf8.getBytes(StandardCharsets.UTF_8)); }

    private static String encode(byte[] d, char[] table, boolean pad) {
        StringBuilder b = new StringBuilder((d.length + 2) / 3 * 4);
        int i = 0;
        for (; i + 2 < d.length; i += 3) {
            int n = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8) | (d[i + 2] & 0xff);
            b.append(table[(n >>> 18) & 63]).append(table[(n >>> 12) & 63]).append(table[(n >>> 6) & 63]).append(table[n & 63]);
        }
        int rest = d.length - i;
        if (rest == 1) {
            int n = (d[i] & 0xff) << 16;
            b.append(table[(n >>> 18) & 63]).append(table[(n >>> 12) & 63]);
            if (pad) b.append("==");
        } else if (rest == 2) {
            int n = ((d[i] & 0xff) << 16) | ((d[i + 1] & 0xff) << 8);
            b.append(table[(n >>> 18) & 63]).append(table[(n >>> 12) & 63]).append(table[(n >>> 6) & 63]);
            if (pad) b.append('=');
        }
        return b.toString();
    }

    /** Decodes standard or URL-safe base64, with or without padding; whitespace is ignored. */
    static byte[] b64decode(String text) {
        if (text == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() * 3 / 4);
        int buf = 0, bits = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+' || c == '-') v = 62;
            else if (c == '/' || c == '_') v = 63;
            else if (c == '=') break;
            else if (Character.isWhitespace(c)) continue;
            else throw new IllegalArgumentException("کاراکتر نامعتبر در base64");
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >>> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }

    static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder b = new StringBuilder(d.length * 2);
            for (byte x : d) b.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String utf8) { return sha256Hex(utf8.getBytes(StandardCharsets.UTF_8)); }

    // ------------------------------------------------------------------ JWS

    /** sigT: UTC, {@code yyyy-MM-dd'T'HH:mm:ss'Z'}. */
    static String sigTime(long epochMillis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(epochMillis));
    }

    /** The protected header, byte-for-byte as in the RC_TICS example: crit, sigT, x5c, alg. */
    static String jwsHeader(byte[] certDer, String sigT) {
        return "{\"crit\":[\"sigT\"],\"sigT\":\"" + sigT + "\",\"x5c\":[\"" + b64(certDer) + "\"],\"alg\":\"RS256\"}";
    }

    static String signJws(PrivateKey key, byte[] certDer, byte[] payload, long epochMillis) throws Exception {
        String input = b64url(jwsHeader(certDer, sigTime(epochMillis))) + "." + b64url(payload);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64url(s.sign());
    }

    static boolean verifyJws(PublicKey key, String compact) throws Exception {
        String[] p = compact.split("\\.");
        if (p.length != 3) return false;
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initVerify(key);
        s.update((p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII));
        return s.verify(b64decode(p[2]));
    }

    /** The auth payload {"nonce","clientId"}; the nonce is a UUID plus millis and never needs escaping. */
    static String authPayload(String nonce, String memoryId) {
        return "{\"nonce\":\"" + jsonEscape(nonce) + "\",\"clientId\":\"" + jsonEscape(memoryId) + "\"}";
    }

    static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ JWE

    private static OAEPParameterSpec oaep256() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }

    static String jweHeader(String kid) {
        return "{\"alg\":\"RSA-OAEP-256\",\"enc\":\"A256GCM\",\"kid\":\"" + jsonEscape(kid) + "\"}";
    }

    static String encryptJwe(PublicKey serverKey, String kid, byte[] plaintext) throws Exception {
        SecureRandom rnd = new SecureRandom();
        byte[] cek = new byte[32];
        byte[] iv = new byte[12];
        rnd.nextBytes(cek);
        rnd.nextBytes(iv);
        return encryptJwe(serverKey, kid, plaintext, cek, iv);
    }

    static String encryptJwe(PublicKey serverKey, String kid, byte[] plaintext, byte[] cek, byte[] iv) throws Exception {
        String prot = b64url(jweHeader(kid));
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE, serverKey, oaep256());
        byte[] wrapped = rsa.doFinal(cek);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, iv));
        aes.updateAAD(prot.getBytes(StandardCharsets.US_ASCII));
        byte[] out = aes.doFinal(plaintext);
        int ctLen = out.length - 16;
        byte[] ct = new byte[ctLen];
        byte[] tag = new byte[16];
        System.arraycopy(out, 0, ct, 0, ctLen);
        System.arraycopy(out, ctLen, tag, 0, 16);
        return prot + "." + b64url(wrapped) + "." + b64url(iv) + "." + b64url(ct) + "." + b64url(tag);
    }

    /** Decryption is only needed by tests (and a self-check); the server does it in production. */
    static byte[] decryptJwe(PrivateKey key, String compact) throws Exception {
        String[] p = compact.split("\\.", -1);
        if (p.length != 5) throw new IllegalArgumentException("JWE must have five parts");
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.DECRYPT_MODE, key, oaep256());
        byte[] cek = rsa.doFinal(b64decode(p[1]));
        byte[] iv = b64decode(p[2]);
        byte[] ct = b64decode(p[3]);
        byte[] tag = b64decode(p[4]);
        byte[] all = new byte[ct.length + tag.length];
        System.arraycopy(ct, 0, all, 0, ct.length);
        System.arraycopy(tag, 0, all, ct.length, tag.length);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, iv));
        aes.updateAAD(p[0].getBytes(StandardCharsets.US_ASCII));
        return aes.doFinal(all);
    }

    /** server-information returns the key as base64 SubjectPublicKeyInfo (DER). */
    static PublicKey publicKeyFromBase64(String b64Spki) throws Exception {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(b64decode(b64Spki)));
    }

    // ------------------------------------------------------------------ tax id

    private static final int[][] VD = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
    private static final int[][] VP = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};
    private static final int[] VINV = {0, 4, 3, 2, 1, 5, 6, 7, 8, 9};

    static char verhoeff(String digits) {
        int c = 0;
        int len = digits.length();
        for (int i = 0; i < len; i++) {
            int d = digits.charAt(len - 1 - i) - '0';
            if (d < 0 || d > 9) throw new IllegalArgumentException("Verhoeff input must be digits");
            c = VD[c][VP[(i + 1) % 8][d]];
        }
        return (char) ('0' + VINV[c]);
    }

    static boolean verhoeffValid(String digitsWithCheck) {
        int c = 0;
        int len = digitsWithCheck.length();
        for (int i = 0; i < len; i++) {
            int d = digitsWithCheck.charAt(len - 1 - i) - '0';
            if (d < 0 || d > 9) return false;
            c = VD[c][VP[i % 8][d]];
        }
        return c == 0;
    }

    /** Digits pass through; letters become their character code (A → 65). */
    static String decimalise(String memoryId) {
        StringBuilder b = new StringBuilder();
        for (char ch : memoryId.toUpperCase(Locale.US).toCharArray()) {
            if (ch >= '0' && ch <= '9') b.append(ch);
            else b.append((int) ch);
        }
        return b.toString();
    }

    static final long MAX_SERIAL = 0xFFFFFFFFFFL;

    static long dayRange(long epochMillis) { return Math.floorDiv(epochMillis, 86_400_000L); }

    static String taxId(String memoryId, long serial, long epochMillis) {
        String mid = memoryId == null ? "" : memoryId.trim().toUpperCase(Locale.US);
        if (!mid.matches("[0-9A-Z]{6}")) throw new IllegalArgumentException("شناسه یکتای حافظه مالیاتی باید ۶ حرف/رقم انگلیسی باشد.");
        if (serial < 0 || serial > MAX_SERIAL) throw new IllegalArgumentException("سریال صورتحساب خارج از محدوده است.");
        long days = dayRange(epochMillis);
        String control = decimalise(mid) + String.format(Locale.US, "%06d", days) + String.format(Locale.US, "%012d", serial);
        return (mid + String.format(Locale.US, "%05X", days) + String.format(Locale.US, "%010X", serial) + verhoeff(control)).toUpperCase(Locale.US);
    }

    static String inno(long serial) { return String.format(Locale.US, "%010X", serial); }

    /** Structural check of a 22-character tax id (memory id, day, serial and check digit). */
    static boolean taxIdValid(String taxId) {
        if (taxId == null) return false;
        String t = taxId.trim().toUpperCase(Locale.US);
        if (!t.matches("[0-9A-Z]{6}[0-9A-F]{15}[0-9]")) return false;
        try {
            long days = Long.parseLong(t.substring(6, 11), 16);
            long serial = Long.parseLong(t.substring(11, 21), 16);
            String control = decimalise(t.substring(0, 6)) + String.format(Locale.US, "%06d", days) + String.format(Locale.US, "%012d", serial);
            return verhoeff(control) == t.charAt(21);
        } catch (Exception e) {
            return false;
        }
    }

    /** Issue day (epoch day) encoded inside a tax id, or -1. */
    static long taxIdDay(String taxId) {
        try { return Long.parseLong(taxId.trim().substring(6, 11), 16); } catch (Exception e) { return -1; }
    }

    // ------------------------------------------------------------------ keys, PEM, PFX

    static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, new SecureRandom());
        return g.generateKeyPair();
    }

    static String pem(String type, byte[] der) {
        String b = b64(der);
        StringBuilder s = new StringBuilder("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < b.length(); i += 64) s.append(b, i, Math.min(b.length(), i + 64)).append('\n');
        return s.append("-----END ").append(type).append("-----\n").toString();
    }

    /** First PEM block of the given type (or any type when {@code type} is null); null when absent. */
    static byte[] pemBlock(String text, String type) {
        if (text == null) return null;
        int from = 0;
        while (true) {
            int b = text.indexOf("-----BEGIN ", from);
            if (b < 0) return null;
            int e1 = text.indexOf("-----", b + 11);
            if (e1 < 0) return null;
            String t = text.substring(b + 11, e1).trim();
            int end = text.indexOf("-----END " + t + "-----", e1);
            if (end < 0) return null;
            if (type == null || type.equals(t)) return b64decode(text.substring(e1 + 5, end));
            from = end + 1;
        }
    }

    static List<String> pemTypes(String text) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (text != null) {
            int b = text.indexOf("-----BEGIN ", from);
            if (b < 0) break;
            int e1 = text.indexOf("-----", b + 11);
            if (e1 < 0) break;
            out.add(text.substring(b + 11, e1).trim());
            from = e1 + 5;
        }
        return out;
    }

    /** Private key from PEM text: PKCS#8 ("PRIVATE KEY") or PKCS#1 ("RSA PRIVATE KEY"). Encrypted PEM is rejected. */
    static PrivateKey privateKeyFromPem(String text) throws Exception {
        if (text == null) throw new IllegalArgumentException("متن کلید خالی است.");
        if (text.contains("ENCRYPTED")) throw new IllegalArgumentException("کلید رمزدار است؛ کلید بدون رمز (PKCS#8) یا فایل PFX را وارد کنید.");
        byte[] der = pemBlock(text, "PRIVATE KEY");
        if (der == null) {
            byte[] pkcs1 = pemBlock(text, "RSA PRIVATE KEY");
            if (pkcs1 == null) {
                String trimmed = text.trim();
                if (trimmed.matches("[A-Za-z0-9+/=\\s_-]+")) der = b64decode(trimmed);
                else throw new IllegalArgumentException("کلید خصوصی در متن پیدا نشد.");
            } else {
                der = pkcs1ToPkcs8(pkcs1);
            }
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        byte[] algId = seq(concat(oid("1.2.840.113549.1.1.1"), new byte[]{0x05, 0x00}));
        return seq(concat(new byte[]{0x02, 0x01, 0x00}, algId, tlv(0x04, pkcs1)));
    }

    static X509Certificate certificateFromBytes(byte[] data) throws Exception {
        CertificateFactory f = CertificateFactory.getInstance("X.509");
        return (X509Certificate) f.generateCertificate(new ByteArrayInputStream(data));
    }

    /** Certificate from PEM text, a bare base64 blob or DER bytes. */
    static X509Certificate certificateFromText(String text) throws Exception {
        if (text == null) throw new IllegalArgumentException("متن گواهی خالی است.");
        byte[] der = pemBlock(text, "CERTIFICATE");
        if (der == null) der = b64decode(text.trim());
        return certificateFromBytes(der);
    }

    static PrivateKey privateKeyFromDer(byte[] pkcs8) throws Exception {
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    /** Result of importing a PKCS#12 file. */
    static final class Pfx {
        PrivateKey key;
        X509Certificate cert;
    }

    static Pfx importPfx(byte[] data, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        char[] pw = password == null ? new char[0] : password.toCharArray();
        ks.load(new ByteArrayInputStream(data), pw);
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String a = aliases.nextElement();
            if (!ks.isKeyEntry(a)) continue;
            Key k = ks.getKey(a, pw);
            if (!(k instanceof PrivateKey)) continue;
            Certificate c = ks.getCertificate(a);
            Pfx p = new Pfx();
            p.key = (PrivateKey) k;
            if (c instanceof X509Certificate) p.cert = (X509Certificate) c;
            return p;
        }
        throw new IllegalArgumentException("در فایل PFX کلید خصوصی پیدا نشد.");
    }

    /** RSA public key derived from a private CRT key (so a key can be matched to a certificate). */
    static PublicKey publicFromPrivate(PrivateKey key) throws Exception {
        if (key instanceof RSAPrivateCrtKey) {
            RSAPrivateCrtKey k = (RSAPrivateCrtKey) key;
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(k.getModulus(), k.getPublicExponent()));
        }
        throw new IllegalArgumentException("نوع کلید خصوصی پشتیبانی نمی‌شود.");
    }

    static boolean keyMatchesCertificate(PrivateKey key, X509Certificate cert) {
        try {
            PublicKey pub = cert.getPublicKey();
            if (!(pub instanceof RSAPublicKey)) return false;
            BigInteger m = ((RSAPublicKey) pub).getModulus();
            if (key instanceof RSAPrivateCrtKey) return ((RSAPrivateCrtKey) key).getModulus().equals(m);
            byte[] probe = "meelano-key-check".getBytes(StandardCharsets.US_ASCII);
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(probe);
            byte[] sig = s.sign();
            Signature v = Signature.getInstance("SHA256withRSA");
            v.initVerify(pub);
            v.update(probe);
            return v.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }

    /** Subject attributes by short name (CN, SERIALNUMBER, O, OU, C, GIVENNAME, SURNAME). */
    static Map<String, String> subject(X509Certificate cert) {
        return parseDn(cert.getSubjectX500Principal());
    }

    static Map<String, String> issuer(X509Certificate cert) {
        return parseDn(cert.getIssuerX500Principal());
    }

    private static Map<String, String> parseDn(X500Principal p) {
        Map<String, String> oidMap = new HashMap<>();
        oidMap.put("2.5.4.5", "SERIALNUMBER");
        oidMap.put("2.5.4.42", "GIVENNAME");
        oidMap.put("2.5.4.4", "SURNAME");
        String name = p.getName(X500Principal.RFC2253, oidMap);
        Map<String, String> out = new HashMap<>();
        for (String part : splitDn(name)) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim().toUpperCase(Locale.US);
            String v = unescapeDn(part.substring(eq + 1).trim());
            if (!out.containsKey(k)) out.put(k, v);
        }
        return out;
    }

    private static List<String> splitDn(String dn) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (esc) { cur.append('\\').append(c); esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == ',' || c == '+') { parts.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(c);
        }
        if (cur.length() > 0) parts.add(cur.toString());
        return parts;
    }

    private static String unescapeDn(String v) {
        if (v.startsWith("#")) {
            try {
                byte[] der = hex(v.substring(1));
                int[] hl = headerLen(der, 0);
                return new String(der, hl[0], hl[1], der[0] == 0x1e ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_8);
            } catch (Exception e) { return v; }
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) { b.append(v.charAt(++i)); continue; }
            b.append(c);
        }
        return b.toString();
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    /** [contentOffset, contentLength] of the DER element starting at {@code off}. */
    static int[] headerLen(byte[] der, int off) {
        int len = der[off + 1] & 0xff;
        int start = off + 2;
        if (len > 0x80) {
            int n = len & 0x7f;
            len = 0;
            for (int i = 0; i < n; i++) len = (len << 8) | (der[start + i] & 0xff);
            start += n;
        }
        return new int[]{start, len};
    }

    // ------------------------------------------------------------------ CSR

    /** Distinguished-name parts of the CSR, in the order the tax organisation's guide lists them. */
    static final class CsrSubject {
        /** true = حقوقی (company, CN "EnglishName [Stamp]"); false = حقیقی (individual, CN "First Last [Sign]"). */
        boolean company;
        String commonName;
        /** شناسه ملی (company, 11 digits) or کد ملی (individual, 10 digits). */
        String serialNumber;
        String organization = "Non-Governmental";
        String organizationalUnit;
        String givenName;
        String surname;
        String country = "IR";
    }

    static String defaultCommonName(boolean company, String englishName) {
        String n = englishName == null ? "" : englishName.trim();
        return n + (company ? " [Stamp]" : " [Sign]");
    }

    static byte[] buildCsr(KeyPair pair, CsrSubject s) throws Exception {
        ByteArrayOutputStream rdns = new ByteArrayOutputStream();
        rdns.write(rdn("2.5.4.3", 0x0c, s.commonName));
        rdns.write(rdn("2.5.4.5", 0x13, s.serialNumber));
        if (!s.company) {
            if (notEmpty(s.givenName)) rdns.write(rdn("2.5.4.42", 0x0c, s.givenName));
            if (notEmpty(s.surname)) rdns.write(rdn("2.5.4.4", 0x0c, s.surname));
        }
        if (notEmpty(s.organization)) rdns.write(rdn("2.5.4.10", 0x0c, s.organization));
        if (notEmpty(s.organizationalUnit)) rdns.write(rdn("2.5.4.11", 0x0c, s.organizationalUnit));
        rdns.write(rdn("2.5.4.6", 0x13, s.country == null ? "IR" : s.country));
        byte[] name = seq(rdns.toByteArray());
        byte[] info = seq(concat(new byte[]{0x02, 0x01, 0x00}, name, pair.getPublic().getEncoded(), new byte[]{(byte) 0xa0, 0x00}));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(pair.getPrivate());
        sig.update(info);
        byte[] signature = sig.sign();
        byte[] alg = seq(concat(oid("1.2.840.113549.1.1.11"), new byte[]{0x05, 0x00}));
        byte[] bits = tlv(0x03, concat(new byte[]{0x00}, signature));
        return seq(concat(info, alg, bits));
    }

    /** Verifies the self-signature of a CSR (used by tests and as a safety check before showing it). */
    static boolean verifyCsr(byte[] csr, PublicKey pub) throws Exception {
        int[] outer = headerLen(csr, 0);
        int infoOff = outer[0];
        int[] info = headerLen(csr, infoOff);
        int infoEnd = info[0] + info[1];
        byte[] infoBytes = new byte[infoEnd - infoOff];
        System.arraycopy(csr, infoOff, infoBytes, 0, infoBytes.length);
        int[] alg = headerLen(csr, infoEnd);
        int bitsOff = alg[0] + alg[1];
        int[] bits = headerLen(csr, bitsOff);
        byte[] sig = new byte[bits[1] - 1];
        System.arraycopy(csr, bits[0] + 1, sig, 0, sig.length);
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(pub);
        v.update(infoBytes);
        return v.verify(sig);
    }

    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    private static byte[] rdn(String oid, int stringTag, String value) {
        byte[] v = tlv(stringTag, (value == null ? "" : value.trim()).getBytes(StandardCharsets.UTF_8));
        return tlv(0x31, seq(concat(oid(oid), v)));
    }

    static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[10];
            int n = 0;
            tmp[n++] = (byte) (v & 0x7f);
            v >>>= 7;
            while (v > 0) { tmp[n++] = (byte) (0x80 | (v & 0x7f)); v >>>= 7; }
            for (int j = n - 1; j >= 0; j--) b.write(tmp[j]);
        }
        return tlv(0x06, b.toByteArray());
    }

    static byte[] seq(byte[] content) { return tlv(0x30, content); }

    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(content.length + 6);
        b.write(tag);
        int len = content.length;
        if (len < 0x80) b.write(len);
        else if (len < 0x100) { b.write(0x81); b.write(len); }
        else if (len < 0x10000) { b.write(0x82); b.write(len >> 8); b.write(len); }
        else { b.write(0x83); b.write(len >> 16); b.write(len >> 8); b.write(len); }
        b.write(content, 0, content.length);
        return b.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p, 0, p.length);
        return b.toByteArray();
    }

    // ------------------------------------------------------------------ passphrase protection (key backup in the DB)

    /** "mtk1:<alg>:<iterations>:<salt>:<iv>:<ciphertext+tag>" — AES-256-GCM under a PBKDF2 key. */
    static String sealWithPassphrase(byte[] data, String passphrase) throws Exception {
        if (passphrase == null || passphrase.length() < 6) throw new IllegalArgumentException("رمز پشتیبان باید حداقل ۶ کاراکتر باشد.");
        SecureRandom rnd = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        rnd.nextBytes(salt);
        rnd.nextBytes(iv);
        String alg = pbkdf2Available("PBKDF2WithHmacSHA256") ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1";
        int iterations = 120_000;
        byte[] k = pbkdf2(alg, passphrase, salt, iterations);
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = aes.doFinal(data);
        return "mtk1:" + alg + ":" + iterations + ":" + b64(salt) + ":" + b64(iv) + ":" + b64(ct);
    }

    static byte[] openWithPassphrase(String sealed, String passphrase) throws Exception {
        String[] p = sealed == null ? new String[0] : sealed.trim().split(":");
        if (p.length != 6 || !"mtk1".equals(p[0])) throw new IllegalArgumentException("قالب پشتیبان کلید شناخته نشد.");
        byte[] k = pbkdf2(p[1], passphrase == null ? "" : passphrase, b64decode(p[3]), Integer.parseInt(p[2]));
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new GCMParameterSpec(128, b64decode(p[4])));
        try {
            return aes.doFinal(b64decode(p[5]));
        } catch (javax.crypto.AEADBadTagException e) {
            throw new IllegalArgumentException("رمز پشتیبان کلید اشتباه است.");
        }
    }

    private static boolean pbkdf2Available(String alg) {
        try { javax.crypto.SecretKeyFactory.getInstance(alg); return true; } catch (Exception e) { return false; }
    }

    private static byte[] pbkdf2(String alg, String pass, byte[] salt, int iterations) throws Exception {
        javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance(alg);
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(pass.toCharArray(), salt, iterations, 256);
        return f.generateSecret(spec).getEncoded();
    }

    /** Human summary of a certificate for the settings page. */
    static List<String> describeCertificate(X509Certificate c) {
        List<String> out = new ArrayList<>();
        Map<String, String> s = subject(c);
        Map<String, String> i = issuer(c);
        out.add("نام: " + nz(s.get("CN")));
        out.add("شناسه/کد ملی گواهی: " + nz(s.get("SERIALNUMBER")));
        out.add("صادرکننده: " + nz(i.get("CN")));
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        out.add("اعتبار از " + f.format(c.getNotBefore()) + " تا " + f.format(c.getNotAfter()));
        return Collections.unmodifiableList(out);
    }

    static boolean selfSigned(X509Certificate c) {
        return c.getSubjectX500Principal().equals(c.getIssuerX500Principal());
    }

    private static String nz(String s) { return s == null || s.isEmpty() ? "—" : s; }
}
