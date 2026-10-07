package com.idp.audit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.audit.infrastructure.AuditRepository;
import com.idp.audit.support.AuditTestSupport;
import com.idp.kms.InMemoryKeyService;
import com.idp.kms.KeyService;
import com.idp.kms.SignatureAlgorithm;
import com.idp.tenant.TenantId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

class DossierSignatureTest extends AuditTestSupport {

    @Autowired DossierService dossiers;

    private static class DelegatingFakeKeyService implements KeyService {
        private final KeyService delegate;
        private final SignatureAlgorithm algorithm;
        public int verifyCalls = 0;

        public DelegatingFakeKeyService(KeyService delegate, SignatureAlgorithm algorithm) {
            this.delegate = delegate;
            this.algorithm = algorithm;
        }

        @Override
        public SignatureAlgorithm signatureAlgorithm(TenantId tenantId, String keyId) {
            return algorithm;
        }

        @Override
        public CryptoResult wrapDek(TenantId tenantId, byte[] dek, String kekId, Map<String, String> aadContext) {
            return delegate.wrapDek(tenantId, dek, kekId, aadContext);
        }

        @Override
        public CryptoResult unwrapDek(TenantId tenantId, byte[] wrappedDek, String kekId, Map<String, String> aadContext) {
            return delegate.unwrapDek(tenantId, wrappedDek, kekId, aadContext);
        }

        @Override
        public CryptoResult sign(TenantId tenantId, byte[] data, String keyId) {
            return delegate.sign(tenantId, data, keyId);
        }

        @Override
        public boolean verify(TenantId tenantId, byte[] data, byte[] signature, String keyId) {
            verifyCalls++;
            return delegate.verify(tenantId, data, signature, keyId);
        }

        @Override
        public Optional<Map<Integer, byte[]>> publicKeys(TenantId tenantId, String keyId) {
            if (algorithm == SignatureAlgorithm.ES256) {
                return Optional.empty();
            }
            return delegate.publicKeys(tenantId, keyId);
        }

        @Override
        public void disableKek(TenantId tenantId, String kekId) {
            delegate.disableKek(tenantId, kekId);
        }
    }

    @Test
    void ac16_ac18_expedienteFirmadoYVerificadoConEs256() throws Exception {
        UUID t = newTenant();
        UUID doc = UUID.randomUUID();
        consume(recibida(t, doc));
        consume(aprobada(t, doc));

        DossierService realService = AopTestUtils.getTargetObject(dossiers);
        KeyService originalKeys = (KeyService) ReflectionTestUtils.getField(realService, "keys");
        DelegatingFakeKeyService es256Keys = new DelegatingFakeKeyService(originalKeys, SignatureAlgorithm.ES256);
        
        try {
            ReflectionTestUtils.setField(realService, "keys", es256Keys);
            
            String dossierJson = dossiers.export(t, doc);
            JsonNode dossier = new ObjectMapper().readTree(dossierJson);
            
            assertEquals("es256", dossier.get("signature").get("algorithm").asText());
            
            DossierService.SignatureCheck check = dossiers.verifySignature(dossier);
            assertTrue(check.valid());
            assertTrue(es256Keys.verifyCalls > 0);
        } finally {
            ReflectionTestUtils.setField(realService, "keys", originalKeys);
        }
    }

    @Test
    void ac17_downgradeAlgorithmInvalid() throws Exception {
        UUID t = newTenant();
        UUID doc = UUID.randomUUID();
        consume(recibida(t, doc));
        consume(aprobada(t, doc));

        String dossierJson = dossiers.export(t, doc);
        JsonNode dossier = new ObjectMapper().readTree(dossierJson);
        assertEquals("ed25519", dossier.get("signature").get("algorithm").asText());

        DossierService realService = AopTestUtils.getTargetObject(dossiers);
        KeyService originalKeys = (KeyService) ReflectionTestUtils.getField(realService, "keys");
        DelegatingFakeKeyService es256Keys = new DelegatingFakeKeyService(originalKeys, SignatureAlgorithm.ES256);
        
        try {
            ReflectionTestUtils.setField(realService, "keys", es256Keys);
            
            DossierService.SignatureCheck check = dossiers.verifySignature(dossier);
            assertFalse(check.valid());
            assertEquals(0, es256Keys.verifyCalls);
        } finally {
            ReflectionTestUtils.setField(realService, "keys", originalKeys);
        }
    }

    /** SEC-055, sentido inverso: expediente es256 y llave que declara ED25519. */
    @Test
    void ac17_downgradeInversoEs256ConLlaveEd25519Invalido() throws Exception {
        UUID t = newTenant();
        UUID doc = UUID.randomUUID();
        consume(recibida(t, doc));
        consume(aprobada(t, doc));

        DossierService realService = AopTestUtils.getTargetObject(dossiers);
        KeyService originalKeys = (KeyService) ReflectionTestUtils.getField(realService, "keys");
        JsonNode dossier;
        try {
            ReflectionTestUtils.setField(realService, "keys",
                    new DelegatingFakeKeyService(originalKeys, SignatureAlgorithm.ES256));
            dossier = new ObjectMapper().readTree(dossiers.export(t, doc));
        } finally {
            ReflectionTestUtils.setField(realService, "keys", originalKeys);
        }
        assertEquals("es256", dossier.get("signature").get("algorithm").asText());

        DelegatingFakeKeyService ed = new DelegatingFakeKeyService(originalKeys, SignatureAlgorithm.ED25519);
        try {
            ReflectionTestUtils.setField(realService, "keys", ed);
            assertFalse(dossiers.verifySignature(dossier).valid());
            assertEquals(0, ed.verifyCalls);
        } finally {
            ReflectionTestUtils.setField(realService, "keys", originalKeys);
        }
    }

    @Test
    void ac16_ed25519NormalSiguePasando() throws Exception {
        UUID t = newTenant();
        UUID doc = UUID.randomUUID();
        consume(recibida(t, doc));
        consume(aprobada(t, doc));

        String dossierJson = dossiers.export(t, doc);
        JsonNode dossier = new ObjectMapper().readTree(dossierJson);
        assertEquals("ed25519", dossier.get("signature").get("algorithm").asText());

        DossierService.SignatureCheck check = dossiers.verifySignature(dossier);
        assertTrue(check.valid());
    }
}