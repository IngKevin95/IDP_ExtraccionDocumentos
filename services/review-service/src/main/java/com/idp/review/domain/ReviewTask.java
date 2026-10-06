package com.idp.review.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tarea de revision de un documento. El id es el taskId del evento extraccion.requiere_revision, o el sampleId de
 * calidad.muestra_ciega_solicitada cuando {@code blindSample} (revision ciega de un oficio ya auto-aprobado).
 */
public record ReviewTask(UUID id, String tenantId, UUID documentId, UUID correlationId, TaskStatus status,
                         String assigneeId, OffsetDateTime assignedAt, String firstReviewerId,
                         String secondReviewerId, boolean criticalCorrection, OffsetDateTime slaDueAt,
                         int escalationLevel, OffsetDateTime escalatedAt, OffsetDateTime completedAt,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt, boolean blindSample) {

    /** Tarea de revision normal (no ciega). */
    public ReviewTask(UUID id, String tenantId, UUID documentId, UUID correlationId, TaskStatus status,
                      String assigneeId, OffsetDateTime assignedAt, String firstReviewerId,
                      String secondReviewerId, boolean criticalCorrection, OffsetDateTime slaDueAt,
                      int escalationLevel, OffsetDateTime escalatedAt, OffsetDateTime completedAt,
                      OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, tenantId, documentId, correlationId, status, assigneeId, assignedAt, firstReviewerId,
                secondReviewerId, criticalCorrection, slaDueAt, escalationLevel, escalatedAt, completedAt,
                createdAt, updatedAt, false);
    }

    public boolean overdue(OffsetDateTime now) {
        return status.open() && slaDueAt.isBefore(now);
    }
}
