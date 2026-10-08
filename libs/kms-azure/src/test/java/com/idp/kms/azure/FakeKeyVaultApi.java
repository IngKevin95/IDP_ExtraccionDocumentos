package com.idp.kms.azure;

import com.idp.kms.KeyService;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * Key Vault en memoria con criptografia real del JDK: RSA-OAEP-256 y ECDSA P-256 en formato r||s. Cada llave tiene
 * versiones ("v1", "v2", ...); wrap y firma usan la ultima, unwrap la indicada.
 */
class FakeKeyVaultApi implements KeyVaultApi {

    private final Map<String, List<KeyPair>> keys = new ConcurrentHashMap<>();
    /** Registro de nombres de llave con los que se llamo al proveedor. */
    final List<String> calls = new ArrayList<>();
    /** Material recibido por wrap, para verificar DEK || SHA-256(AAD). */
    byte[] lastWrapMaterial;
    RuntimeException failure;

    /** 2048 bits y no 3072 para acelerar los tests; el algoritmo es el mismo. */
    void provisionRsa(String name) {
        keys.computeIfAbsent(name, n -> new ArrayList<>(List.of(generate("RSA", 2048, null))));
    }

    void provisionEc(String name) {
        keys.computeIfAbsent(name, n -> new ArrayList<>(List.of(generate("EC", 0, new ECGenParameterSpec("secp256r1")))));
    }

    /** Rotacion: agrega una version nueva RSA que pasa a ser la vigente. */
    void rotateRsa(String name) {
        keys.get(name).add(generate("RSA", 2048, null));
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

    private List<KeyPair> versions(String name) {
        calls.add(name);
        if (failure != null) {
            throw failure;
        }
        List<KeyPair> v = keys.get(name);
        if (v == null) {
            throw new KeyService.KeyNotFoundException("Llave inexistente en el KMS");
        }
        return v;
    }

    private KeyPair latest(String name) {
        List<KeyPair> v = versions(name);
        return v.get(v.size() - 1);
    }

    private static Cipher oaep(int mode, java.security.Key k) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
        c.init(mode, k, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return c;
    }

    @Override
    public Wrapped wrap(String keyName, byte[] material) {
        List<KeyPair> v = versions(keyName);
        lastWrapMaterial = material.clone();
        try {
            return new Wrapped(oaep(Cipher.ENCRYPT_MODE, v.get(v.size() - 1).getPublic()).doFinal(material),
                "v" + v.size());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] unwrap(String keyName, String version, byte[] wrapped) {
        List<KeyPair> v = versions(keyName);
        int idx = version.startsWith("v") ? Integer.parseInt(version.substring(1)) - 1 : -1;
        if (idx < 0 || idx >= v.size()) {
            throw new KeyService.KeyNotFoundException("Llave inexistente en el KMS");
        }
        try {
            return oaep(Cipher.DECRYPT_MODE, v.get(idx).getPrivate()).doFinal(wrapped);
        } catch (GeneralSecurityException e) {
            throw new CiphertextRejectedException();
        }
    }

    @Override
    public byte[] sign(String keyName, byte[] data) {
        KeyPair kp = latest(keyName);
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
        KeyPair kp = latest(keyName);
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
        calls.add(keyName);
        if (failure != null) {
            throw failure;
        }
        keys.remove(keyName);
    }
}
