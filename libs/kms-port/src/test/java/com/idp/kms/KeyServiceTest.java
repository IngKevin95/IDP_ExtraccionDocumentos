package com.idp.kms;

import com.idp.tenant.TenantId;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyServiceTest {

    @Test
    void defaultSignatureAlgorithmIsEd25519() {
        KeyService service = new KeyService() {
            @Override
            public CryptoResult wrapDek(TenantId tenantId, byte[] dek, String kekId, Map<String, String> aadContext) { return null; }
            @Override
            public CryptoResult unwrapDek(TenantId tenantId, byte[] wrappedDek, String kekId, Map<String, String> aadContext) { return null; }
            @Override
            public CryptoResult sign(TenantId tenantId, byte[] data, String keyId) { return null; }
            @Override
            public boolean verify(TenantId tenantId, byte[] data, byte[] signature, String keyId) { return false; }
            @Override
            public void disableKek(TenantId tenantId, String kekId) {}
        };

        assertEquals(SignatureAlgorithm.ED25519, service.signatureAlgorithm(new TenantId("t"), "key"));
    }
}
