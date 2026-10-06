package com.idp.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.audit.domain.AuditEntry;
import com.idp.audit.domain.Exceptions.ChainIntegrityException;
import com.idp.audit.infrastructure.AuditRepository;
import com.idp.audit.infrastructure.AuditRepository.Head;
import com.idp.audit.domain.Exceptions.UnknownTenantException;
import com.idp.events.EventEnvelope;
import com.idp.events.EventOriginGuard;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ingesta de eventos en la hash-chain del tenant. La secuencia se asigna aqui, bajo bloqueo de la cabeza de
 * cadena del tenant en la base de control (no por offset de Kafka), y entra al hash (AC-01, AC-05). Antes de
 * encadenar se contrasta la cabeza con el ultimo registro y con la huella en memoria; si no coinciden se
 * alerta y se lanza {@link ChainIntegrityException}, lo que bloquea el consumo (AC-02).
 */
@Service
public class AuditIngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditIngestionService.class);
    private static final List<String> ACTOR_FIELDS = List.of("actorId", "subjectId", "appliedBy", "releasedBy",
            "approvedBy", "reviewerId", "userId");

    /** Huella de la cadena en curso conocida por este proceso. */
    private record ChainState(long sequenceId, String hash) {}

    private final AuditRepository repo;
    private final AuditAlertService alerts;
    private final AuditMetrics metrics;
    private final Clock clock;
    private final Map<UUID, ChainState> memory = new ConcurrentHashMap<>();

    public AuditIngestionService(AuditRepository repo, AuditAlertService alerts, AuditMetrics metrics, Clock clock) {
        this.repo = repo;
        this.alerts = alerts;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Encadena un evento ya validado contra su schema. Debe ejecutarse dentro de la transaccion del consumidor. */
    @Transactional
    public AuditEntry ingest(EventEnvelope e) {
        UUID tenantId = e.tenantId();
        requireKnownTenant(e);
        repo.ensureHead(tenantId);
        Head head = repo.lockHead(tenantId).orElseThrow(() -> new IllegalStateException("Cabeza de cadena ausente"));
        long next = head.lastSequenceId() + 1;
        checkConsistency(tenantId, head, next);

        JsonNode payload = e.payload() == null ? CanonicalJson.mapper().createObjectNode() : e.payload();
        Instant occurredAt = e.occurredAt().truncatedTo(ChronoUnit.MICROS);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID documentId = documentId(payload);
        String actor = actor(payload);
        String payloadSha = HashChainService.payloadSha256(payload);
        String canonical = HashChainService.canonicalEvent(tenantId, e.eventId(), e.eventType(),
                e.correlationId(), documentId, actor, occurredAt, payloadSha);
        String hash = HashChainService.hash(head.lastHash(), next, canonical);

        AuditEntry entry = new AuditEntry(UUID.randomUUID(), next, tenantId, e.eventId(), e.correlationId(),
                documentId, e.eventType(), actor, occurredAt, payload, payloadSha, hash, head.lastHash(), false, now);
        repo.insert(entry);
        repo.updateHead(tenantId, next, hash);
        remember(tenantId, new ChainState(next, hash));
        metrics.chainLength(tenantId, next);
        metrics.ingestionLag(Duration.between(occurredAt, now));
        return entry;
    }

    /**
     * SEC-053: un tenantId inexistente no puede abrir una cadena (ensureHead) ni inflar la base de auditoria. Se
     * descarta con alerta SECURITY sin payload; no es un error de infraestructura, no se reintenta.
     */
    private void requireKnownTenant(EventEnvelope e) {
        if (!repo.tenantExists(e.tenantId())) {
            metrics.count("audit.ingestion.unknown_tenant");
            LOG.error(EventOriginGuard.SECURITY,
                    "Evento descartado: tenant inexistente en el directorio tenant={} eventType={}", e.tenantId(),
                    safe(e.eventType()));
            throw new UnknownTenantException("Tenant inexistente en el directorio de la plataforma");
        }
    }

    private static String safe(String value) {
        return value != null && value.matches("[a-zA-Z0-9_.-]{1,80}") ? value : "?";
    }

    /** La huella en memoria solo avanza tras el commit: un rollback no debe dejarla por delante de la BD. */
    private void remember(UUID tenantId, ChainState state) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    memory.put(tenantId, state);
                }
            });
        } else {
            memory.put(tenantId, state);
        }
    }

    private void checkConsistency(UUID tenantId, Head head, long next) {
        Optional<AuditEntry> last = repo.lastEntry(tenantId);
        long lastSeq = last.map(AuditEntry::sequenceId).orElse(0L);
        String lastHash = last.map(AuditEntry::currentHash).orElse(HashChainService.GENESIS);
        if (lastSeq != head.lastSequenceId() || !lastHash.equals(head.lastHash())) {
            fail(tenantId, next, "HASH_MISMATCH",
                    "La cabeza de cadena no coincide con el ultimo registro persistido (manipulacion de BD)");
        }
        ChainState known = memory.get(tenantId);
        if (known != null && (head.lastSequenceId() < known.sequenceId()
                || (head.lastSequenceId() == known.sequenceId() && !head.lastHash().equals(known.hash())))) {
            // Otra replica puede haber avanzado (seq mayor): es valido. Retroceso o hash distinto no lo es.
            fail(tenantId, next, "HASH_MISMATCH",
                    "El previous_hash de la BD no coincide con la huella en memoria de la cadena en curso");
        }
    }

    private void fail(UUID tenantId, long sequenceId, String errorType, String message) {
        alerts.integrityAlert(tenantId, sequenceId, errorType, message);
        throw new ChainIntegrityException(tenantId, sequenceId, errorType, message);
    }

    static UUID documentId(JsonNode payload) {
        String v = payload.path("documentId").asText("");
        try {
            return v.isEmpty() ? null : UUID.fromString(v);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static String actor(JsonNode payload) {
        for (String f : ACTOR_FIELDS) {
            String v = payload.path(f).asText("");
            if (!v.isEmpty()) {
                return v.length() > 100 ? v.substring(0, 100) : v;
            }
        }
        return "system";
    }
}
