package com.idp.review.domain;

/** Estados de la tarea de revision. Distintos de los estados canonicos del documento (document-service). */
public enum TaskStatus {
    PENDING,
    PENDING_SECOND_APPROVAL,
    APPROVED,
    REJECTED;

    /** La tarea sigue en la cola: se puede asignar, corregir, aprobar o rechazar. */
    public boolean open() {
        return this == PENDING || this == PENDING_SECOND_APPROVAL;
    }
}
