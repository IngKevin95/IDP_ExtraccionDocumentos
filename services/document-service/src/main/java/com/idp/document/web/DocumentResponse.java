package com.idp.document.web;

import com.idp.document.domain.DocumentRecord;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentResponse(UUID id, String tenantId, String hashSha256, String typology, String radicado,
                               int version, String status, String classification, OffsetDateTime createdAt,
                               OffsetDateTime updatedAt) {

    public static DocumentResponse of(DocumentRecord d) {
        return new DocumentResponse(d.id(), d.tenantId(), d.hashSha256(), d.typology(), d.radicado(), d.version(),
                d.status().name(), d.classification().name(), d.createdAt(), d.updatedAt());
    }
}
