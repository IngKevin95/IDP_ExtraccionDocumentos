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
import com.idp.storage.ObjectStore.StorageException;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** AC-03: consolidacion de lotes en WORM con firma asimetrica, politica por tamano o antiguedad. */
@TestPropertySource(properties = "idp.audit.anchor.batch-size=3")
class WormAnchorServiceTest extends AuditTestSupport {
    @Autowired WormAnchorService anchors;
    @Autowired AuditRepository repo;

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
