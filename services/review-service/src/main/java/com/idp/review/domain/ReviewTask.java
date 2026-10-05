package com.idp.review.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Tarea de revision de un documento. El id es el taskId del evento extraccion.requiere_revision. */
public record ReviewTask(UUID id, String tenantId, UUID documentId, UUID correlationId, TaskStatus status,
                         String assigneeId, OffsetDateTime assignedAt, String firstReviewerId,
                         String secondReviewerId, boolean criticalCorrection, OffsetDateTime slaDueAt,
                         int escalationLevel, OffsetDateTime escalatedAt, OffsetDateTime completedAt,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public boolean overdue(OffsetDateTime now) {
        return status.open() && slaDueAt.isBefore(now);
    }
}
