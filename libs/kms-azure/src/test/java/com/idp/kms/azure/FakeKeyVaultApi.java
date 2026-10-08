package com.idp.kms.azure;

import com.idp.kms.KeyService;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.spec.MGF1ParameterSpec;

/** Key Vault en memoria con criptografia real del JDK: RSA-OAEP-256 y ECDSA P-256 en formato r||s. */
class FakeKeyVaultApi implements KeyVaultApi {

    private final Map<String, KeyPair> keys = new ConcurrentHashMap<>();
    /** Registro de nombres de llave con los que se llamo al proveedor. */
    final List<String> calls = new ArrayList<>();
    /** Material recibido por wrap, para verificar DEK || SHA-256(AAD). */
    byte[] lastWrapMaterial;
    RuntimeException failure;

    /** 2048 bits y no 3072 para acelerar los tests; el algoritmo es el mismo. */
    void provisionRsa(String name) {
        keys.computeIfAbsent(name, n -> generate("RSA", 2048, null));
    }

    void provisionEc(String name) {
        keys.computeIfAbsent(name, n -> generate("EC", 0, new ECGenParameterSpec("secp256r1")));
    }

    private static KeyPair generate(String alg, int size, ECGenParameterSpec spec) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance(alg);
            if (spec != null) {
                g.initialize(spec);
            } else {
                g.initialize(size);
            }
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private KeyPair key(String name) {
        calls.add(name);
        if (failure != null) {
            throw failure;
        }
        KeyPair kp = keys.get(name);
        if (kp == null) {
            throw new KeyService.KeyNotFoundException("Llave inexistente en el KMS");
        }
        return kp;
    }

    private static Cipher oaep(int mode, java.security.Key k) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
        c.init(mode, k, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return c;
    }

    @Override
    public byte[] wrap(String keyName, byte[] material) {
        KeyPair kp = key(keyName);
        lastWrapMaterial = material.clone();
        try {
            return oaep(Cipher.ENCRYPT_MODE, kp.getPublic()).doFinal(material);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] unwrap(String keyName, byte[] wrapped) {
        KeyPair kp = key(keyName);
        try {
            return oaep(Cipher.DECRYPT_MODE, kp.getPrivate()).doFinal(wrapped);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] sign(String keyName, byte[] data) {
        KeyPair kp = key(keyName);
        try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(kp.getPrivate());
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean verify(String keyName, byte[] data, byte[] signature) {
        KeyPair kp = key(keyName);
        try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initVerify(kp.getPublic());
            s.update(data);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    @Override
    public void disable(String keyName) {
        key(keyName);
        keys.remove(keyName);
    }
}
