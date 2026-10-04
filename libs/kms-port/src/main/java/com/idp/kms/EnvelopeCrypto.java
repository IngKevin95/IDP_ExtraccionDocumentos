package com.idp.kms;

import com.idp.tenant.TenantId;
import java.util.Arrays;
import java.util.Map;

/**
 * Cifrado de sobre (SEC-015): genera una DEK AES-256 por dato, cifra con AES-GCM y envuelve la DEK
 * con la KEK del tenant via {@link KeyService}. Las DEK en claro se sobrescriben al terminar (AC-08).
 */
public final class EnvelopeCrypto {

    private final KeyService keys;

    public EnvelopeCrypto(KeyService keys) {
        this.keys = keys;
    }

    public EnvelopeCiphertext encrypt(TenantId tenantId, String kekId, Map<String, String> aadContext,
                                      byte[] plaintext) {
        byte[] dek = AesGcm.newKey();
        try {
            byte[] wrapped = keys.wrapDek(tenantId, dek, kekId, aadContext).getData();
            byte[] data = AesGcm.encrypt(dek, plaintext, AadContext.canonical(aadContext));
            return new EnvelopeCiphertext(wrapped, data);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    public byte[] decrypt(TenantId tenantId, String kekId, Map<String, String> aadContext,
                          EnvelopeCiphertext envelope) {
        byte[] dek = keys.unwrapDek(tenantId, envelope.wrappedDek(), kekId, aadContext).getData();
        try {
            return AesGcm.decrypt(dek, envelope.encryptedData(), AadContext.canonical(aadContext));
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }
}
