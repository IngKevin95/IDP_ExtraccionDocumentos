package com.idp.audit.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.audit.application.CanonicalJson;
import com.idp.audit.application.HashChainService;
import com.idp.audit.domain.AuditEntry;
import com.idp.audit.domain.LegalHoldRecord;
import com.idp.audit.domain.WormAnchor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistencia JDBC portable (PostgreSQL y H2 en modo PostgreSQL) de cadena, anclajes y legal holds. */
@Repository
public class AuditRepository {

    /** Cabeza de cadena de un tenant. */
    public record Head(long lastSequenceId, String lastHash) {}

    private static final String ENTRY_COLS = "id, sequence_id, tenant_id, event_id, correlation_id, document_id, "
            + "event_type, actor_id, occurred_at, CAST(payload AS VARCHAR) AS payload_text, payload_sha256, "
            + "current_hash, previous_hash, worm_anchored, created_at";

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime odt(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime o = rs.getObject(col, OffsetDateTime.class);
        return o == null ? null : o.toInstant();
    }

    private static AuditEntry entry(ResultSet rs, int row) throws SQLException {
        JsonNode payload = CanonicalJson.parse(rs.getString("payload_text"));
        return new AuditEntry(rs.getObject("id", UUID.class), rs.getLong("sequence_id"),
                rs.getObject("tenant_id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getObject("correlation_id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getString("event_type"), rs.getString("actor_id"), instant(rs, "occurred_at"), payload,
                rs.getString("payload_sha256"), rs.getString("current_hash"), rs.getString("previous_hash"),
                rs.getBoolean("worm_anchored"), instant(rs, "created_at"));
    }

    // ---- directorio de tenants (base de control compartida) ----

    /** {@code true} si el tenant figura en el directorio de la plataforma (cualquier estado del ciclo de vida). */
    public boolean tenantExists(UUID tenantId) {
        return jdbc.sql("select count(*) from tenants where id = :t").param("t", tenantId)
                .query(Long.class).single() > 0;
    }

    // ---- cabeza de cadena (bloqueo por tenant) ----

    public void ensureHead(UUID tenantId) {
        jdbc.sql("insert into audit_chain_head (tenant_id, last_sequence_id, last_hash) values (:t, 0, :h) "
                        + "on conflict do nothing")
                .param("t", tenantId).param("h", HashChainService.GENESIS).update();
    }

    /** Bloquea la fila del tenant hasta el fin de la transaccion: serializa ingesta y anclaje por tenant. */
    public Optional<Head> lockHead(UUID tenantId) {
        return jdbc.sql("select last_sequence_id, last_hash from audit_chain_head where tenant_id = :t for update")
                .param("t", tenantId)
                .query((rs, i) -> new Head(rs.getLong("last_sequence_id"), rs.getString("last_hash")))
                .optional();
    }

    public Optional<Head> head(UUID tenantId) {
        return jdbc.sql("select last_sequence_id, last_hash from audit_chain_head where tenant_id = :t")
                .param("t", tenantId)
                .query((rs, i) -> new Head(rs.getLong("last_sequence_id"), rs.getString("last_hash")))
                .optional();
    }

    public void updateHead(UUID tenantId, long sequenceId, String hash) {
        jdbc.sql("update audit_chain_head set last_sequence_id = :s, last_hash = :h where tenant_id = :t")
                .param("s", sequenceId).param("h", hash).param("t", tenantId).update();
    }

    // ---- entradas ----

    public void insert(AuditEntry e) {
        jdbc.sql("insert into audit_entries (id, sequence_id, tenant_id, event_id, correlation_id, document_id, "
                        + "event_type, actor_id, occurred_at, payload, payload_sha256, current_hash, previous_hash, "
                        + "worm_anchored, created_at) values (:id, :seq, :t, :ev, :corr, :doc, :type, :actor, :occ, "
                        + "CAST(:payload AS JSONB), :psha, :cur, :prev, false, :created)")
                .param("id", e.id()).param("seq", e.sequenceId()).param("t", e.tenantId())
                .param("ev", e.eventId()).param("corr", e.correlationId()).param("doc", e.documentId())
                .param("type", e.eventType()).param("actor", e.actorId()).param("occ", odt(e.occurredAt()))
                .param("payload", CanonicalJson.write(e.payload())).param("psha", e.payloadSha256())
                .param("cur", e.currentHash()).param("prev", e.previousHash()).param("created", odt(e.createdAt()))
                .update();
    }

    public Optional<AuditEntry> lastEntry(UUID tenantId) {
        return jdbc.sql("select " + ENTRY_COLS + " from audit_entries where tenant_id = :t "
                        + "order by sequence_id desc limit 1")
                .param("t", tenantId).query(AuditRepository::entry).optional();
    }

    public long maxSequence(UUID tenantId) {
        Long v = jdbc.sql("select max(sequence_id) from audit_entries where tenant_id = :t")
                .param("t", tenantId).query(Long.class).optional().orElse(null);
        return v == null ? 0 : v;
    }

    public long count(UUID tenantId) {
        return jdbc.sql("select count(*) from audit_entries where tenant_id = :t")
                .param("t", tenantId).query(Long.class).single();
    }

    /** Pagina ordenada de entradas con sequence_id en (after, to]. */
    public List<AuditEntry> page(UUID tenantId, long after, long to, int limit) {
        return jdbc.sql("select " + ENTRY_COLS + " from audit_entries where tenant_id = :t and sequence_id > :a "
                        + "and sequence_id <= :to order by sequence_id limit :n")
                .param("t", tenantId).param("a", after).param("to", to).param("n", limit)
                .query(AuditRepository::entry).list();
    }

    public List<AuditEntry> findByDocument(UUID tenantId, UUID documentId) {
        return jdbc.sql("select " + ENTRY_COLS + " from audit_entries where tenant_id = :t and document_id = :d "
                        + "order by sequence_id")
                .param("t", tenantId).param("d", documentId).query(AuditRepository::entry).list();
    }

    /** current_hash por sequence_id para los indicados. */
    public Map<Long, String> hashesBySequence(UUID tenantId, Collection<Long> sequences) {
        Map<Long, String> out = new HashMap<>();
        if (sequences.isEmpty()) {
            return out;
        }
        jdbc.sql("select sequence_id, current_hash from audit_entries where tenant_id = :t "
                        + "and sequence_id in (:s)")
                .param("t", tenantId).param("s", sequences)
                .query((rs, i) -> {
                    out.put(rs.getLong("sequence_id"), rs.getString("current_hash"));
                    return null;
                }).list();
        return out;
    }

    public List<AuditEntry> findUnanchored(UUID tenantId, int limit) {
        return jdbc.sql("select " + ENTRY_COLS + " from audit_entries where tenant_id = :t "
                        + "and worm_anchored = false order by sequence_id limit :n")
                .param("t", tenantId).param("n", limit).query(AuditRepository::entry).list();
    }

    public long countUnanchored(UUID tenantId) {
        return jdbc.sql("select count(*) from audit_entries where tenant_id = :t and worm_anchored = false")
                .param("t", tenantId).query(Long.class).single();
    }

    public List<UUID> tenantsWithUnanchored() {
        return jdbc.sql("select distinct tenant_id from audit_entries where worm_anchored = false")
                .query((rs, i) -> rs.getObject("tenant_id", UUID.class)).list();
    }

    public int markAnchored(UUID tenantId, long from, long to) {
        return jdbc.sql("update audit_entries set worm_anchored = true where tenant_id = :t "
                        + "and sequence_id between :a and :b and worm_anchored = false")
                .param("t", tenantId).param("a", from).param("b", to).update();
    }

    // ---- anclajes ----

    public void insertAnchor(WormAnchor a) {
        jdbc.sql("insert into worm_anchors (anchor_id, tenant_id, start_sequence_id, end_sequence_id, file_uri, "
                        + "manifest_hash, signature, created_at) values (:id, :t, :s, :e, :uri, :mh, :sig, :c)")
                .param("id", a.anchorId()).param("t", a.tenantId()).param("s", a.startSequenceId())
                .param("e", a.endSequenceId()).param("uri", a.fileUri()).param("mh", a.manifestHash())
                .param("sig", a.signature()).param("c", odt(a.createdAt())).update();
    }

    private static WormAnchor anchor(ResultSet rs, int row) throws SQLException {
        return new WormAnchor(rs.getObject("anchor_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getLong("start_sequence_id"), rs.getLong("end_sequence_id"), rs.getString("file_uri"),
                rs.getString("manifest_hash"), rs.getString("signature"), instant(rs, "created_at"));
    }

    public List<WormAnchor> anchors(UUID tenantId) {
        return jdbc.sql("select * from worm_anchors where tenant_id = :t order by start_sequence_id")
                .param("t", tenantId).query(AuditRepository::anchor).list();
    }

    public Optional<WormAnchor> lastAnchor(UUID tenantId) {
        return jdbc.sql("select * from worm_anchors where tenant_id = :t order by start_sequence_id desc limit 1")
                .param("t", tenantId).query(AuditRepository::anchor).optional();
    }

    public List<WormAnchor> anchorsCovering(UUID tenantId, long minSeq, long maxSeq) {
        return jdbc.sql("select * from worm_anchors where tenant_id = :t and end_sequence_id >= :a "
                        + "and start_sequence_id <= :b order by start_sequence_id")
                .param("t", tenantId).param("a", minSeq).param("b", maxSeq).query(AuditRepository::anchor).list();
    }

    // ---- legal hold ----

    public void insertHold(LegalHoldRecord h) {
        jdbc.sql("insert into legal_hold_records (id, tenant_id, document_id, reason, applied_by, status, "
                        + "created_at) values (:id, :t, :d, :r, :by, :s, :c)")
                .param("id", h.id()).param("t", h.tenantId()).param("d", h.documentId()).param("r", h.reason())
                .param("by", h.appliedBy()).param("s", h.status().name()).param("c", odt(h.createdAt())).update();
    }

    private static LegalHoldRecord hold(ResultSet rs, int row) throws SQLException {
        return new LegalHoldRecord(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getString("reason"), rs.getString("applied_by"),
                LegalHoldRecord.Status.valueOf(rs.getString("status")), instant(rs, "created_at"),
                rs.getString("released_by"), instant(rs, "released_at"));
    }

    public List<LegalHoldRecord> activeHolds(UUID tenantId) {
        return jdbc.sql("select * from legal_hold_records where tenant_id = :t and status = 'ACTIVE' "
                        + "order by created_at")
                .param("t", tenantId).query(AuditRepository::hold).list();
    }

    /** Hold activo con exactamente ese alcance (documentId nulo = tenant completo). */
    public Optional<LegalHoldRecord> activeHoldWithScope(UUID tenantId, UUID documentId) {
        return activeHolds(tenantId).stream()
                .filter(h -> documentId == null ? h.documentId() == null : documentId.equals(h.documentId()))
                .findFirst();
    }

    /** Hay hold activo que cubre el documento (propio o de tenant completo). */
    public boolean isHeld(UUID tenantId, UUID documentId) {
        return activeHolds(tenantId).stream()
                .anyMatch(h -> h.documentId() == null || h.documentId().equals(documentId));
    }

    public void releaseHold(UUID id, String releasedBy, Instant at) {
        jdbc.sql("update legal_hold_records set status = 'RELEASED', released_by = :by, released_at = :at "
                        + "where id = :id")
                .param("by", releasedBy).param("at", odt(at)).param("id", id).update();
    }
}
