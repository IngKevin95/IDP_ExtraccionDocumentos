package com.idp.kms.gcp;

import com.idp.kms.AesGcm;
import com.idp.kms.KeyService;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cloud KMS en memoria con criptografia real (AES-GCM con AAD, Ed25519 del JDK). No existe emulador verificable
 * de Cloud KMS (spike T-00): este fake es lo que da significado a la suite de contrato.
 */
class FakeGcpKmsApi implements GcpKmsApi {

    private static final class Key {
        final byte[] aesKey = AesGcm.newKey();
        final List<KeyPair> signingVersions = new ArrayList<>();
        boolean disabled;
        boolean destroyScheduled;
    }

    private final Map<String, Key> keys = new ConcurrentHashMap<>();
    final List<String> calls = new ArrayList<>();
    volatile RuntimeException failure;

    void createEncryptionKey(String name) {
        keys.putIfAbsent(name, new Key());
    }

    void createSigningKey(String name) {
        Key key = keys.computeIfAbsent(name, n -> new Key());
        addSigningVersion(key);
    }

    /** Agrega una version de firma (rotacion) a una llave ya creada. */
    void rotateSigningKey(String name) {
        addSigningVersion(keys.get(name));
    }

    private static void addSigningVersion(Key key) {
        try {
            key.signingVersions.add(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean destroyScheduled(String name) {
        return keys.get(name).destroyScheduled;
    }

    private Key key(String name) {
        calls.add(name);
        if (failure != null) {
            throw failure;
        }
        Key key = keys.get(name);
        if (key == null) {
            throw new KeyService.KeyNotFoundException("Llave inexistente en Cloud KMS");
        }
        return key;
    }

    private Key enabled(String name) {
        Key key = key(name);
        if (key.disabled) {
            throw new IllegalStateException("FAILED_PRECONDITION: version deshabilitada");
        }
        return key;
    }

    @Override
    public byte[] encrypt(String cryptoKey, byte[] plaintext, byte[] aad) {
        return AesGcm.encrypt(enabled(cryptoKey).aesKey, plaintext, aad);
    }

    @Override
    public byte[] decrypt(String cryptoKey, byte[] ciphertext, byte[] aad) {
        return AesGcm.decrypt(enabled(cryptoKey).aesKey, ciphertext, aad);
    }

    @Override
    public byte[] sign(String cryptoKey, byte[] data) {
        Key key = enabled(cryptoKey);
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key.signingVersions.get(key.signingVersions.size() - 1).getPrivate());
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public SigningKey signingKey(String cryptoKey) {
        Key key = enabled(cryptoKey);
        int version = key.signingVersions.size();
        return new SigningKey(version, key.signingVersions.get(version - 1).getPublic().getEncoded());
    }

    @Override
    public void disable(String cryptoKey) {
        Key key = key(cryptoKey);
        key.disabled = true;
        key.destroyScheduled = true;
    }
}
