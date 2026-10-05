package com.idp.document.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Fila de {@code document}. Tras la purga los campos del binario y del negocio quedan en null (tombstone). */
public record DocumentRecord(
        UUID id,
        String tenantId,
        String hashSha256,
        String typology,
        String radicado,
        int version,
        DocumentStatus status,
        Classification classification,
        String objectStoreKey,
        String mimeType,
        Long fileSizeBytes,
        String uploadedBy,
        String idempotencyKey,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime purgedAt) {

    public boolean purged() {
        return purgedAt != null;
    }

    public DocumentRecord withStatus(DocumentStatus newStatus, OffsetDateTime now) {
        return new DocumentRecord(id, tenantId, hashSha256, typology, radicado, version, newStatus, classification,
                objectStoreKey, mimeType, fileSizeBytes, uploadedBy, idempotencyKey, createdAt, now, purgedAt);
    }
}
