package com.idp.audit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idp.audit.domain.AuditEntry;
import com.idp.audit.domain.Exceptions.ChainIntegrityException;
import com.idp.audit.infrastructure.AuditRepository;
import com.idp.audit.support.AuditTestSupport;
import com.idp.events.EventEnvelope;
import com.idp.events.EventValidationException;
import com.idp.events.IdempotentEventConsumer.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** AC-01, AC-02, AC-05 y AC-06 contra el consumidor idempotente real, Flyway y H2 en modo PostgreSQL. */
class AuditIngestionTest extends AuditTestSupport {
    @Autowired AuditRepository repo;
    @Autowired AuditVerificationService verification;

    @Test
    void ac01_ingestaSecuencialEncadenaHashYAsignaSecuencia() {
        UUID t = newTenant();
        UUID doc = UUID.randomUUID();
        assertEquals(Result.PROCESSED, consume(recibida(t, doc)));
        assertEquals(Result.PROCESSED, consume(aprobada(t, doc)));
        assertEquals(Result.PROCESSED, consume(breakGlass(t)));

        List<AuditEntry> chain = repo.page(t, 0, 10, 10);
        assertEquals(3, chain.size());
        assertEquals(List.of(1L, 2L, 3L), chain.stream().map(AuditEntry::sequenceId).toList());
        assertEquals(HashChainService.GENESIS, chain.get(0).previousHash());
        assertEquals(chain.get(0).currentHash(), chain.get(1).previousHash());
        assertEquals(chain.get(1).currentHash(), chain.get(2).previousHash());
        for (AuditEntry e : chain) {
            assertEquals(HashChainService.recompute(e), e.currentHash());
            assertFalse(e.wormAnchored());
        }
        assertEquals(doc, chain.get(1).documentId());
        assertEquals("HUMAN_REVIEWER", chain.get(1).actorId());
        assertEquals("sre1", chain.get(2).actorId());
    }

    @Test
    void ac01_eventoDuplicadoSeIgnoraYNoDuplicaLaCadena() {
        UUID t = newTenant();
        EventEnvelope e = aprobada(t, UUID.randomUUID());
        assertEquals(Result.PROCESSED, consume(e));
        assertEquals(Result.DUPLICATE, consume(e));
        assertEquals(1, countEntries(t));
    }

    @Test
    void ac01_eventoFueraDeContratoSeRechazaSinEncadenar() {
        UUID t = newTenant();
        String sinCamposRequeridos = "{\"eventId\":\"" + UUID.randomUUID()
                + "\",\"eventType\":\"extraccion.aprobada\",\"schemaVersion\":1,\"occurredAt\":\"2026-10-04T10:00:00Z\","
                + "\"tenantId\":\"" + t + "\",\"correlationId\":\"" + UUID.randomUUID() + "\"}";
        assertThrows(EventValidationException.class, () -> consumer.consume(sinCamposRequeridos, ingestion::ingest));
        assertEquals(0, countEntries(t));
    }

    @Test
    void ac02_hashAlteradoEnBdBloqueaLaIngestaYEmiteAlertaCritica() {
        UUID t = newTenant();
        UUID doc = UUID.randomUUID();
        consume(recibida(t, doc));
        consume(aprobada(t, doc));
        jdbc.sql("update audit_entries set current_hash = :h where tenant_id = :t and sequence_id = 2")
                .param("h", "f".repeat(64)).param("t", t).update();

        EventEnvelope siguiente = breakGlass(t);
        ChainIntegrityException ex = assertThrows(ChainIntegrityException.class, () -> consume(siguiente));

        assertEquals("HASH_MISMATCH", ex.errorType());
        assertEquals(3, ex.sequenceId());
        assertEquals(2, countEntries(t));
        assertEquals(0, jdbc.sql("select count(*) from processed_event where event_id = :e")
                .param("e", siguiente.eventId()).query(Long.class).single(), "el offset no debe confirmarse");
        var alerts = publisher.of(t, "auditoria.alerta_integridad");
        assertEquals(1, alerts.size());
        assertEquals("HASH_MISMATCH", alerts.get(0).payload().get("errorType").asText());
    }

    @Test
    void ac02_retrocesoDeLaCadenaFrenteALaHuellaEnMemoriaBloquea() {
        UUID t = newTenant();
        consume(recibida(t, UUID.randomUUID()));
        consume(aprobada(t, UUID.randomUUID()));
        // Reversion coherente de BD (borra el ultimo registro y rebobina la cabeza): solo la huella en memoria la delata.
        String hash1 = jdbc.sql("select current_hash from audit_entries where tenant_id = :t and sequence_id = 1")
                .param("t", t).query(String.class).single();
        jdbc.sql("delete from audit_entries where tenant_id = :t and sequence_id = 2").param("t", t).update();
        jdbc.sql("update audit_chain_head set last_sequence_id = 1, last_hash = :h where tenant_id = :t")
                .param("h", hash1).param("t", t).update();

        assertThrows(ChainIntegrityException.class, () -> consume(breakGlass(t)));
        assertEquals(1, countEntries(t));
        assertEquals(1, publisher.of(t, "auditoria.alerta_integridad").size());
    }

    @Test
    void ac05_tenantsConcurrentesMantienenSecuenciasYCadenasIndependientes() throws Exception {
        UUID a = newTenant();
        UUID b = newTenant();
        consume(recibida(a, UUID.randomUUID()));
        consume(recibida(b, UUID.randomUUID()));
        int perTenant = 14;
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (int i = 0; i < perTenant; i++) {
                futures.add(pool.submit((Callable<Result>) () -> consume(aprobada(a, UUID.randomUUID()))));
                futures.add(pool.submit((Callable<Result>) () -> consume(aprobada(b, UUID.randomUUID()))));
            }
            for (Future<Result> f : futures) {
                assertEquals(Result.PROCESSED, f.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        for (UUID t : List.of(a, b)) {
            List<AuditEntry> chain = repo.page(t, 0, 1000, 1000);
            assertEquals(perTenant + 1, chain.size());
            String prev = HashChainService.GENESIS;
            long seq = 0;
            for (AuditEntry e : chain) {
                assertEquals(++seq, e.sequenceId());
                assertEquals(prev, e.previousHash());
                assertEquals(HashChainService.recompute(e), e.currentHash());
                assertEquals(t, e.tenantId());
                prev = e.currentHash();
            }
            assertTrue(verification.verify(t, null, null).isChainIntact());
        }
    }

    @Test
    void sec053_tenantInexistenteSeDescartaSinCrearCabezaNiEntradas() {
        UUID fantasma = UUID.randomUUID();
        assertThrows(com.idp.audit.domain.Exceptions.UnknownTenantException.class,
                () -> consume(recibida(fantasma, UUID.randomUUID())));
        assertEquals(0, countEntries(fantasma));
        assertEquals(0, jdbc.sql("select count(*) from audit_chain_head where tenant_id = :t").param("t", fantasma)
                .query(Long.class).single(), "no se abre cadena para un tenant inexistente");
        assertEquals(0, publisher.of(fantasma, "auditoria.alerta_integridad").size(),
                "no es una alerta de integridad de cadena");
    }

    @Test
    void sec053_tenantEnCualquierEstadoDelCicloDeVidaSiSeAudita() {
        UUID t = newTenant();
        jdbc.sql("update tenants set status = 'PENDING_DELETION' where id = :t").param("t", t).update();
        assertEquals(Result.PROCESSED, consume(breakGlass(t)));
    }

    @Test
    void ac06_caidaDeLaBaseDeControlNoPierdeElEventoYElReintentoLoEncadena() {
        UUID t = newTenant();
        consume(recibida(t, UUID.randomUUID()));
        EventEnvelope critico = breakGlass(t);

        jdbc.sql("alter table audit_chain_head rename to audit_chain_head_caida").update();
        try {
            assertThrows(RuntimeException.class, () -> consume(critico));
        } finally {
            jdbc.sql("alter table audit_chain_head_caida rename to audit_chain_head").update();
        }
        assertEquals(1, countEntries(t), "nada persistido mientras la BD estuvo caida");
        assertEquals(0, jdbc.sql("select count(*) from processed_event where event_id = :e")
                .param("e", critico.eventId()).query(Long.class).single(),
                "el evento no queda marcado como procesado: el reintento lo reprocesa");

        assertEquals(Result.PROCESSED, consume(critico));
        List<AuditEntry> chain = repo.page(t, 0, 10, 10);
        assertEquals(2, chain.size());
        assertEquals("breakglass.otorgado", chain.get(1).eventType());
        assertEquals(chain.get(0).currentHash(), chain.get(1).previousHash());
    }
}
