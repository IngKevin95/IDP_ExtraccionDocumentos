package com.idp.tenant.support;

import com.idp.kms.KeyService;
import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** KeyService en memoria para tests: firma HMAC-SHA256 por (tenant, keyId) y registro de KEK deshabilitadas. */
public class InMemoryKeyService implements KeyService {
    public volatile RuntimeException disableFailure;
    public final List<String> disabled = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> secrets = new ConcurrentHashMap<>();

    private byte[] secret(TenantId t, String keyId) {
        return secrets.computeIfAbsent(t.value() + "|" + keyId, k -> k.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public CryptoResult wrapDek(TenantId t, byte[] dek, String kekId, Map<String, String> aad) {
        return new CryptoResult(dek);
    }

    @Override
    public CryptoResult unwrapDek(TenantId t, byte[] wrapped, String kekId, Map<String, String> aad) {
        return new CryptoResult(wrapped);
    }

    @Override
    public CryptoResult sign(TenantId t, byte[] data, String keyId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret(t, keyId), "HmacSHA256"));
            return new CryptoResult(mac.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean verify(TenantId t, byte[] data, byte[] signature, String keyId) {
        return MessageDigest.isEqual(sign(t, data, keyId).getData(), signature);
    }

    @Override
    public void disableKek(TenantId t, String kekId) {
        if (disableFailure != null) {
            throw disableFailure;
        }
        disabled.add(kekId);
    }
}
