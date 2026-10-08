package com.idp.kms.aws;

import com.idp.kms.AadContext;
import com.idp.kms.KeyService;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Fake en memoria con criptografia real (AES-GCM con el contexto como AAD, Ed25519 del JDK). */
class FakeAwsKmsApi implements AwsKmsApi {

    private static final int IV_BYTES = 12;

    private static final class Key {
        final SecretKey aes;
        final KeyPair ed25519;
        boolean disabled;
        int deletionWindowDays;

        Key(SecretKey aes, KeyPair ed25519) {
            this.aes = aes;
            this.ed25519 = ed25519;
        }
    }

    private final Map<String, Key> keys = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    final List<String> calls = new ArrayList<>();
    volatile boolean failing;
    /** Si es true, DisableKey falla; sirve para probar que el bloqueo en proceso no depende del proveedor. */
    volatile boolean failDisable;

    private synchronized Key key(String alias) {
        calls.add(alias);
        if (failing) {
            throw new IllegalStateException("caido con datos sensibles: secreto-123");
        }
        Key k = keys.get(alias);
        if (k == null) {
            throw new KeyService.KeyNotFoundException("Llave inexistente en el KMS");
        }
        if (k.disabled) {
            throw new KeyService.KeyDisabledException("KEK deshabilitada");
        }
        return k;
    }

    int deletionWindowDays(String alias) {
        return keys.get(alias).deletionWindowDays;
    }

    boolean isDisabled(String alias) {
        return keys.get(alias).disabled;
    }

    @Override
    public byte[] encrypt(String alias, byte[] plaintext, Map<String, String> encryptionContext) {
        Key k = key(alias);
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, k.aes, new GCMParameterSpec(128, iv));
            cipher.updateAAD(AadContext.canonical(encryptionContext));
            byte[] ct = cipher.doFinal(plaintext);
            byte[] out = new byte[IV_BYTES + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] decrypt(String alias, byte[] ciphertext, Map<String, String> encryptionContext) {
        Key k = key(alias);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, k.aes, new GCMParameterSpec(128, ciphertext, 0, IV_BYTES));
            cipher.updateAAD(AadContext.canonical(encryptionContext));
            return cipher.doFinal(ciphertext, IV_BYTES, ciphertext.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("InvalidCiphertext", e);
        }
    }

    @Override
    public byte[] sign(String alias, byte[] message) {
        Key k = key(alias);
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(k.ed25519.getPrivate());
            s.update(message);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean verify(String alias, byte[] message, byte[] signature) {
        Key k = key(alias);
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(k.ed25519.getPublic());
            s.update(message);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    @Override
    public byte[] publicKey(String alias) {
        return key(alias).ed25519.getPublic().getEncoded();
    }

    @Override
    public synchronized void disableAndScheduleDeletion(String alias, int pendingWindowInDays) {
        if (failDisable) {
            throw new IllegalStateException("DisableKey fallo");
        }
        Key k;
        try {
            k = key(alias);
        } catch (KeyService.KeyDisabledException e) {
            return; // ya deshabilitada: idempotente
        }
        k.disabled = true;
        k.deletionWindowDays = pendingWindowInDays;
    }

    @Override
    public synchronized void createKey(String alias, boolean signing) {
        try {
            if (signing) {
                keys.computeIfAbsent(alias, a -> new Key(null, newEd25519()));
            } else {
                KeyGenerator gen = KeyGenerator.getInstance("AES");
                gen.init(256);
                keys.computeIfAbsent(alias, a -> new Key(gen.generateKey(), null));
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair newEd25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
