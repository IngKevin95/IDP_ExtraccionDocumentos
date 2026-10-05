package com.idp.kms;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Ed25519VerifierTest {

    private final InMemoryKeyService keys = new InMemoryKeyService();
    private final TenantId tenant = new TenantId("t-1");
    private final byte[] data = "expediente".getBytes(StandardCharsets.UTF_8);

    @Test
    void h3_verificaFirmaCrudaConLlavePublicaCacheable() {
        byte[] sig = keys.sign(tenant, data, "audit").getData();
        Map<Integer, byte[]> pub = keys.publicKeys(tenant, "audit").orElseThrow();
        assertTrue(Ed25519Verifier.verify(pub, data, sig));
        assertFalse(Ed25519Verifier.verify(pub, "otro".getBytes(StandardCharsets.UTF_8), sig));
    }

    @Test
    void h3_verificaFormatoTransit() {
        byte[] sig = keys.sign(tenant, data, "audit").getData();
        byte[] transit = ("vault:v1:" + Base64.getEncoder().encodeToString(sig)).getBytes(StandardCharsets.UTF_8);
        assertTrue(Ed25519Verifier.verify(keys.publicKeys(tenant, "audit").orElseThrow(), data, transit));
        assertFalse(Ed25519Verifier.verify(Map.of(), data, transit));
    }
}
