package ir.meelano.android;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Device-bound secrets of the Moadian app (the signing private key and the optional database password).
 * Values are encrypted with an AES-256-GCM key that lives in the Android KeyStore and never leaves the phone,
 * so a copy of the app's files is useless elsewhere.
 */
final class MeelanoTaxVault {
    private static final String ALIAS = "meelano_tax_vault_v1";

    private MeelanoTaxVault() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
            ks.deleteEntry(ALIAS);
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    static String seal(byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = c.getIV();
        byte[] ct = c.doFinal(data);
        return "v1:" + MeelanoTaxCrypto.b64(iv) + ":" + MeelanoTaxCrypto.b64(ct);
    }

    static byte[] open(String sealed) throws Exception {
        String[] p = sealed.split(":");
        if (p.length != 3 || !"v1".equals(p[0])) throw new IllegalStateException("قالب داده امن شناخته نشد.");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, MeelanoTaxCrypto.b64decode(p[1])));
        return c.doFinal(MeelanoTaxCrypto.b64decode(p[2]));
    }

    static void put(SharedPreferences prefs, String name, String value) throws Exception {
        if (value == null || value.isEmpty()) { prefs.edit().remove(name).apply(); return; }
        prefs.edit().putString(name, seal(value.getBytes(StandardCharsets.UTF_8))).apply();
    }

    /** The stored value, or null when missing or unreadable (e.g. after the KeyStore was reset). */
    static String get(SharedPreferences prefs, String name) {
        String s = prefs.getString(name, null);
        if (s == null || s.isEmpty()) return null;
        try {
            return new String(open(s), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
