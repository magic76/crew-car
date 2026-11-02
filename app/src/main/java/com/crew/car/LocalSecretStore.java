package com.crew.car;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores small app secrets as AES-GCM ciphertext, with the key kept in Android Keystore. */
final class LocalSecretStore {
    private static final String PREFS = "crew_car_secrets";
    private static final String GEMINI_KEY = "gemini_api_key_enc";
    private static final String KEY_ALIAS = "crew_car_local_secret_v1";
    private static final int IV_BYTES = 12;
    private static final byte[] AAD = "CrewCar:LocalSecretStore:v1".getBytes(StandardCharsets.UTF_8);

    private LocalSecretStore() {}

    static String readGeminiKey(Context context) {
        String encoded = preferences(context).getString(GEMINI_KEY, null);
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
            if (packed.length <= IV_BYTES + 16) return discardUnreadable(context);
            byte[] iv = Arrays.copyOfRange(packed, 0, IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(packed, IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return discardUnreadable(context);
        }
    }

    static boolean saveGeminiKey(Context context, String value) {
        String secret = value == null ? "" : value.trim();
        if (secret.isEmpty()) {
            preferences(context).edit().remove(GEMINI_KEY).apply();
            return true;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKey(true));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] packed = ByteBuffer.allocate(cipher.getIV().length + encrypted.length)
                    .put(cipher.getIV()).put(encrypted).array();
            preferences(context).edit()
                    .putString(GEMINI_KEY, Base64.encodeToString(packed, Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String discardUnreadable(Context context) {
        preferences(context).edit().remove(GEMINI_KEY).apply();
        return "";
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getKey(boolean create) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS))
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (!create) throw new IllegalStateException("Local encryption key unavailable");

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
