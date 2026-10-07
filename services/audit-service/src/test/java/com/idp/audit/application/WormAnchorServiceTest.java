package com.idp.audit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.audit.domain.AuditEntry;
import com.idp.audit.domain.WormAnchor;
import com.idp.audit.infrastructure.AuditRepository;
import com.idp.audit.support.AuditTestSupport;
import com.idp.kms.KeyService;
import com.idp.kms.SignatureAlgorithm;
import com.idp.storage.ObjectStore.StorageException;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** AC-03: consolidacion de lotes en WORM con firma asimetrica, politica por tamano o antiguedad. */
@TestPropertySource(properties = "idp.audit.anchor.batch-size=3")
class WormAnchorServiceTest extends AuditTestSupport {
    @Autowired WormAnchorService anchors;
    @Autowired AuditRepository repo;
    @Autowired AuditVerificationService verification;

    private void ingest(UUID t, int n) {
        for (int i = 0; i < n; i++) {
            consume(aprobada(t, UUID.randomUUID()));
        }
    }

    private JsonNode storedDoc(UUID t, WormAnchor a) throws Exception {
        byte[] gz = store.raw(t.toString(), a.fileUri());
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return json(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void ac03_anclaPorTamanoDeLoteFirmaSubeAWormYMarcaLosRegistros() throws Exception {
        UUID t = newTenant();
        ingest(t, 2);
        assertTrue(anchors.anchorIfDue(t).isEmpty(), "bajo el umbral y sin antiguedad no ancla");
        assertTrue(store.puts.stream().noneMatch(p -> p.tenant().equals(t.toString())));

        ingest(t, 1);
        WormAnchor a = anchors.anchorIfDue(t).orElseThrow();

        assertEquals(1, a.startSequenceId());
        assertEquals(3, a.endSequenceId());
        assertEquals(List.of(a), repo.anchors(t));
        assertTrue(repo.findUnanchored(t, 10).isEmpty());
        assertEquals(3, repo.page(t, 0, 10, 10).stream().filter(AuditEntry::wormAnchored).count());

        var put = store.puts.stream().filter(p -> p.tenant().equals(t.toString())).findFirst().orElseThrow();
        assertEquals(a.fileUri(), put.path());
        assertEquals(Duration.ofDays(3650), put.retention(), "retencion obligatoria del ImmutableStore");

        JsonNode doc = storedDoc(t, a);
        JsonNode manifest = doc.get("manifest");
        assertEquals(3, manifest.get("entries").size());
        assertEquals(repo.page(t, 0, 10, 10).get(2).currentHash(), manifest.get("headHash").asText());
        assertEquals(a.manifestHash(), HashChainService.sha256Hex(CanonicalJson.bytes(manifest)));
        byte[] signature = Base64.getDecoder().decode(doc.get("signature").get("value").asText());
        assertTrue(keys.verify(new TenantId(t.toString()), CanonicalJson.bytes(manifest), signature,
                "audit-signing"), "firma ed25519 valida con la llave de auditoria");
        assertEquals("ed25519", doc.get("signature").get("algorithm").asText());
    }

    /** KeyService que firma y verifica con el delegado pero declara otro algoritmo y cuenta las verificaciones. */
    private static final class DeclaringKeys implements KeyService {
        private final KeyService delegate;
        private final SignatureAlgorithm algorithm;
        int verifyCalls;

        DeclaringKeys(KeyService delegate, SignatureAlgorithm algorithm) {
            this.delegate = delegate;
            this.algorithm = algorithm;
        }

        public SignatureAlgorithm signatureAlgorithm(TenantId tenantId, String keyId) {
            return algorithm;
        }
        public CryptoResult wrapDek(TenantId tenantId, byte[] dek, String kekId, Map<String, String> aad) {
            return delegate.wrapDek(tenantId, dek, kekId, aad);
        }
        public CryptoResult unwrapDek(TenantId tenantId, byte[] dek, String kekId, Map<String, String> aad) {
            return delegate.unwrapDek(tenantId, dek, kekId, aad);
        }
        public CryptoResult sign(TenantId tenantId, byte[] data, String keyId) {
            return delegate.sign(tenantId, data, keyId);
        }
        public boolean verify(TenantId tenantId, byte[] data, byte[] signature, String keyId) {
            verifyCalls++;
            return delegate.verify(tenantId, data, signature, keyId);
        }
        public void disableKek(TenantId tenantId, String kekId) {
            delegate.disableKek(tenantId, kekId);
        }
    }

    /** Cambia el KeyService de los servicios bajo prueba y lo restaura al terminar (los singletons son compartidos). */
    private void withKeys(KeyService replacement, Runnable body) {
        AuditVerificationService verifier = AopTestUtils.getTargetObject(verification);
        WormAnchorService signer = AopTestUtils.getTargetObject(anchors);
        KeyService originalVerifier = (KeyService) ReflectionTestUtils.getField(verifier, "keys");
        KeyService originalSigner = (KeyService) ReflectionTestUtils.getField(signer, "keys");
        try {
            ReflectionTestUtils.setField(verifier, "keys", replacement);
            ReflectionTestUtils.setField(signer, "keys", replacement);
            body.run();
        } finally {
            ReflectionTestUtils.setField(verifier, "keys", originalVerifier);
            ReflectionTestUtils.setField(signer, "keys", originalSigner);
        }
    }

    /** AC-18: un ancla firmada con una llave que declara ES256 lo registra y se verifica con el proveedor. */
    @Test
    void ac18_anclaFirmadaConEs256SeVerificaConElProveedor() {
        UUID t = newTenant();
        ingest(t, 3);
        DeclaringKeys es256 = new DeclaringKeys(keys, SignatureAlgorithm.ES256);
        withKeys(es256, () -> {
            WormAnchor a = anchors.anchorIfDue(t).orElseThrow();
            try {
                assertEquals("es256", storedDoc(t, a).get("signature").get("algorithm").asText());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertTrue(verification.verify(t, null, null).isChainIntact());
            assertTrue(es256.verifyCalls > 0);
        });
    }

    /** SEC-055: el algoritmo del ancla debe coincidir con el que declara la llave; no se verifica con el recibido. */
    @Test
    void sec055_anclaEd25519ConLlaveQueDeclaraEs256EsInvalida() {
        UUID t = newTenant();
        ingest(t, 3);
        anchors.anchorIfDue(t).orElseThrow();
        assertTrue(verification.verify(t, null, null).isChainIntact(), "ancla ed25519 valida con llave ed25519");

        DeclaringKeys es256 = new DeclaringKeys(keys, SignatureAlgorithm.ES256);
        withKeys(es256, () -> {
            assertFalse(verification.verify(t, null, null).isChainIntact());
            assertEquals(0, es256.verifyCalls, "no se invoca verify con un algoritmo que la llave no declara");
        });
    }

    /** SEC-055, sentido inverso: ancla es256 y llave que declara ED25519. */
    @Test
    void sec055_anclaEs256ConLlaveQueDeclaraEd25519EsInvalida() {
        UUID t = newTenant();
        ingest(t, 3);
        withKeys(new DeclaringKeys(keys, SignatureAlgorithm.ES256), () -> anchors.anchorIfDue(t).orElseThrow());

        DeclaringKeys ed = new DeclaringKeys(keys, SignatureAlgorithm.ED25519);
        withKeys(ed, () -> {
            assertFalse(verification.verify(t, null, null).isChainIntact());
            assertEquals(0, ed.verifyCalls);
        });
    }

    @Test
    void ac03_anclaPorAntiguedadYEncadenaManifiestos() throws Exception {
        UUID t = newTenant();
        ingest(t, 1);
        assertTrue(anchors.anchorIfDue(t).isEmpty());
        clock.advance(Duration.ofHours(25));
        WormAnchor first = anchors.anchorIfDue(t).orElseThrow();
        assertEquals(1, first.endSequenceId());

        ingest(t, 2);
        assertTrue(anchors.anchorNow(t).isPresent());
        WormAnchor second = repo.lastAnchor(t).orElseThrow();
        assertEquals(2, second.startSequenceId());
        assertEquals(3, second.endSequenceId());
        assertEquals(first.manifestHash(), storedDoc(t, second).get("manifest").get("previousManifestHash").asText());
        assertTrue(anchors.anchorNow(t).isEmpty(), "sin pendientes no hay ancla nueva");
    }

    @Test
    void ac03_elJobPeriodicoAnclaSoloLosTenantsPendientesYNoMezclaCadenas() {
        UUID a = newTenant();
        UUID b = newTenant();
        ingest(a, 3);
        ingest(b, 1);
        new AnchorJob(repo, anchors).run();

        assertEquals(1, repo.anchors(a).size());
        assertTrue(repo.anchors(b).isEmpty(), "el lote de B no alcanza el umbral");
        assertEquals(1, repo.findUnanchored(b, 10).size());
        assertTrue(store.puts.stream().noneMatch(p -> p.tenant().equals(b.toString())));
    }

    @Test
    void ac03_siElWormFallaNoSeMarcaNadaNiSeRegistraElAncla() {
        UUID t = newTenant();
        ingest(t, 3);
        store.failPuts = true;
        assertThrows(StorageException.class, () -> anchors.anchorNow(t));
        assertTrue(repo.anchors(t).isEmpty());
        assertEquals(3, repo.findUnanchored(t, 10).size());

        store.failPuts = false;
        Optional<WormAnchor> retry = anchors.anchorNow(t);
        assertTrue(retry.isPresent());
        assertFalse(repo.findUnanchored(t, 10).iterator().hasNext());
    }
}
