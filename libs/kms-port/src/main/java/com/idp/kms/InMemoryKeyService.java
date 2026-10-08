package com.idp.kms;

import com.idp.tenant.TenantId;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** KeyService en memoria para tests: KEK AES-256 y claves Ed25519 por (tenant, id), creadas bajo demanda. */
public final class InMemoryKeyService implements KeyService {

    private final Map<String, byte[]> keks = new ConcurrentHashMap<>();
    private final Map<String, KeyPair> signingKeys = new ConcurrentHashMap<>();
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static String id(TenantId tenantId, String keyId) {
        if (!NAME.matcher(tenantId.value()).matches() || keyId == null || !NAME.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Identificador de tenant o llave invalido");
        }
        return tenantId.value() + "/" + keyId;
    }

    private void checkEnabled(String id) {
        if (disabled.contains(id)) {
            throw new KeyDisabledException("KEK deshabilitada: " + id);
        }
    }

    @Override
    public CryptoResult wrapDek(TenantId tenantId, byte[] dek, String kekId, Map<String, String> aadContext) {
        String id = id(tenantId, kekId);
        checkEnabled(id);
        byte[] kek = keks.computeIfAbsent(id, k -> AesGcm.newKey());
        return new CryptoResult(AesGcm.encrypt(kek, dek, AadContext.canonical(aadContext)));
    }

    @Override
    public CryptoResult unwrapDek(TenantId tenantId, byte[] wrappedDek, String kekId,
                                  Map<String, String> aadContext) {
        String id = id(tenantId, kekId);
        checkEnabled(id);
        byte[] kek = keks.get(id);
        if (kek == null) {
            throw new KeyNotFoundException("KEK inexistente: " + id);
        }
        byte[] plain = AesGcm.decrypt(kek, wrappedDek, AadContext.canonical(aadContext));
        try {
            return new CryptoResult(plain);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    @Override
    public CryptoResult sign(TenantId tenantId, byte[] data, String keyId) {
        String id = id(tenantId, keyId);
        checkEnabled(id);
        KeyPair kp = signingKeys.computeIfAbsent(id, k -> newKeyPair());
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(kp.getPrivate());
            s.update(data);
            return new CryptoResult(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Fallo de firma", e);
        }
    }

    @Override
    public boolean verify(TenantId tenantId, byte[] data, byte[] signature, String keyId) {
        String id = id(tenantId, keyId);
        checkEnabled(id);
        KeyPair kp = signingKeys.get(id);
        if (kp == null) {
            throw new KeyNotFoundException("Llave de firma inexistente: " + id);
        }
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(kp.getPublic());
            s.update(data);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    @Override
    public java.util.Optional<Map<Integer, byte[]>> publicKeys(TenantId tenantId, String keyId) {
        String id = id(tenantId, keyId);
        checkEnabled(id);
        KeyPair kp = signingKeys.get(id);
        if (kp == null) {
            throw new KeyNotFoundException("Llave de firma inexistente: " + id);
        }
        byte[] encoded = kp.getPublic().getEncoded();
        return java.util.Optional.of(Map.of(1, Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length)));
    }

    /** Deshabilita y destruye el material de la llave (crypto-shredding). */
    @Override
    public void disableKek(TenantId tenantId, String kekId) {
        String id = id(tenantId, kekId);
        disabled.add(id);
        byte[] removed = keks.remove(id);
        if (removed != null) {
            Arrays.fill(removed, (byte) 0);
        }
        signingKeys.remove(id);
    }

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 no disponible", e);
        }
    }
}
