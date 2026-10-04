package com.idp.kms;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM con IV aleatorio de 96 bits. Salida: iv || ciphertext+tag. */
public final class AesGcm {

    public static final int KEY_BYTES = 32;
    public static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {
    }

    public static byte[] newKey() {
        byte[] k = new byte[KEY_BYTES];
        RANDOM.nextBytes(k);
        return k;
    }

    public static byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null && aad.length > 0) {
                c.updateAAD(aad);
            }
            byte[] ct = c.doFinal(plaintext);
            byte[] out = new byte[IV_BYTES + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Fallo de cifrado AES-GCM", e);
        }
    }

    /** @throws IllegalArgumentException si el tag no valida (dato o AAD alterados, llave incorrecta) */
    public static byte[] decrypt(byte[] key, byte[] ivAndCiphertext, byte[] aad) {
        if (ivAndCiphertext == null || ivAndCiphertext.length < IV_BYTES + TAG_BITS / 8) {
            throw new IllegalArgumentException("Texto cifrado invalido");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, ivAndCiphertext, 0, IV_BYTES));
            if (aad != null && aad.length > 0) {
                c.updateAAD(aad);
            }
            return c.doFinal(ivAndCiphertext, IV_BYTES, ivAndCiphertext.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("No se pudo descifrar: autenticacion fallida", e);
        }
    }
}
