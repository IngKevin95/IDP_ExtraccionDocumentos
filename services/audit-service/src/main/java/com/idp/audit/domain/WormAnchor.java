package com.idp.audit.domain;

import java.time.Instant;
import java.util.UUID;

/** Ancla de un lote de la cadena almacenado en el ImmutableStore (Object Lock compliance). */
public record WormAnchor(
        UUID anchorId,
        UUID tenantId,
        long startSequenceId,
        long endSequenceId,
        String fileUri,
        String manifestHash,
        String signature,
        Instant createdAt) {
}
