package com.idp.document.infra;

import com.idp.document.domain.Classification;
import com.idp.document.domain.DocumentRecord;
import com.idp.document.domain.DocumentStatus;
import com.idp.document.domain.PageArtifact;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Acceso JDBC al silo del tenant actual (el DataSource enrutado lo resuelve por contexto). */
@Repository
public class DocumentRepository {

    private static final String COLS = "id, tenant_id, hash_sha256, typology, radicado, version, status, "
            + "classification, object_store_key, mime_type, file_size_bytes, uploaded_by, idempotency_key, "
            + "created_at, updated_at, purged_at";

    private static final RowMapper<DocumentRecord> MAPPER = (rs, i) -> {
        long size = rs.getLong("file_size_bytes");
        Long sizeOrNull = rs.wasNull() ? null : size;
        return new DocumentRecord(
                rs.getObject("id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("hash_sha256"),
                rs.getString("typology"),
                rs.getString("radicado"),
                rs.getInt("version"),
                DocumentStatus.valueOf(rs.getString("status")),
                Classification.valueOf(rs.getString("classification")),
                rs.getString("object_store_key"),
                rs.getString("mime_type"),
                sizeOrNull,
                rs.getString("uploaded_by"),
                rs.getString("idempotency_key"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("purged_at", OffsetDateTime.class));
    };

    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(DocumentRecord d) {
        jdbc.update("insert into document (" + COLS + ") values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                d.id(), d.tenantId(), d.hashSha256(), d.typology(), d.radicado(), d.version(), d.status().name(),
                d.classification().name(), d.objectStoreKey(), d.mimeType(), d.fileSizeBytes(), d.uploadedBy(),
                d.idempotencyKey(), d.createdAt(), d.updatedAt(), d.purgedAt());
    }

    public Optional<DocumentRecord> findById(String tenantId, UUID id) {
        return one("select " + COLS + " from document where tenant_id = ? and id = ? and purged_at is null",
                tenantId, id);
    }

    public Optional<DocumentRecord> findByHash(String tenantId, String hash) {
        return one("select " + COLS + " from document where tenant_id = ? and hash_sha256 = ?", tenantId, hash);
    }

    public Optional<DocumentRecord> findByIdempotencyKey(String tenantId, String key) {
        return one("select " + COLS + " from document where tenant_id = ? and idempotency_key = ?", tenantId, key);
    }

    public Optional<DocumentRecord> findByBusinessKey(String tenantId, String typology, String radicado,
                                                      int version) {
        return one("select " + COLS + " from document where tenant_id = ? and typology = ? and radicado = ? "
                + "and version = ?", tenantId, typology, radicado, version);
    }

    private Optional<DocumentRecord> one(String sql, Object... args) {
        return jdbc.query(sql, MAPPER, args).stream().findFirst();
    }

    /** Transicion con control optimista del estado de origen. Devuelve filas afectadas. */
    public int updateStatus(String tenantId, UUID id, DocumentStatus from, DocumentStatus to, OffsetDateTime now) {
        return jdbc.update("update document set status = ?, updated_at = ? where tenant_id = ? and id = ? "
                + "and status = ? and purged_at is null", to.name(), now, tenantId, id, from.name());
    }

    /**
     * Lista paginada. {@code privileged} permite ver los Altamente Confidenciales de otros cargadores
     * (Data Steward o admin); si no, solo los propios.
     */
    public List<DocumentRecord> list(String tenantId, DocumentStatus status, String userId, boolean privileged,
                                     int limit, int offset) {
        List<Object> args = new ArrayList<>();
        String where = where(tenantId, status, userId, privileged, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("select " + COLS + " from document " + where
                + " order by created_at desc, id limit ? offset ?", MAPPER, args.toArray());
    }

    public long count(String tenantId, DocumentStatus status, String userId, boolean privileged) {
        List<Object> args = new ArrayList<>();
        String where = where(tenantId, status, userId, privileged, args);
        Long n = jdbc.queryForObject("select count(*) from document " + where, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    private static String where(String tenantId, DocumentStatus status, String userId, boolean privileged,
                                List<Object> args) {
        StringBuilder sb = new StringBuilder("where tenant_id = ? and purged_at is null");
        args.add(tenantId);
        if (status != null) {
            sb.append(" and status = ?");
            args.add(status.name());
        }
        if (!privileged) {
            sb.append(" and (classification <> 'ALTAMENTE_CONFIDENCIAL' or uploaded_by = ?)");
            args.add(userId);
        }
        return sb.toString();
    }

    public void insertArtifact(PageArtifact a, OffsetDateTime now) {
        jdbc.update("insert into page_artifact (id, document_id, kind, page_number, object_store_key, created_at) "
                + "values (?,?,?,?,?,?)", a.id(), a.documentId(), a.kind(), a.pageNumber(), a.objectStoreKey(), now);
    }

    public List<PageArtifact> artifacts(UUID documentId) {
        return jdbc.query("select id, document_id, kind, page_number, object_store_key from page_artifact "
                        + "where document_id = ? order by kind, page_number",
                (rs, i) -> new PageArtifact(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getString("kind"), rs.getInt("page_number"), rs.getString("object_store_key")),
                documentId);
    }

    public void deleteArtifacts(UUID documentId) {
        jdbc.update("delete from page_artifact where document_id = ?", documentId);
    }

    /** Tombstone SEC-022: anula binario y datos de negocio, conserva id, tenant, estado y fechas. */
    public int tombstone(String tenantId, UUID id, OffsetDateTime now) {
        return jdbc.update("update document set hash_sha256 = null, typology = null, radicado = null, "
                + "object_store_key = null, mime_type = null, file_size_bytes = null, uploaded_by = null, "
                + "idempotency_key = null, purged_at = ?, updated_at = ? where tenant_id = ? and id = ? "
                + "and purged_at is null", now, now, tenantId, id);
    }
}
