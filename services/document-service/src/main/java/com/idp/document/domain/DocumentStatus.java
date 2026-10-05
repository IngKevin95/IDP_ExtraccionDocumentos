package com.idp.document.domain;

import java.util.EnumSet;
import java.util.Set;

/** Estados canonicos del documento (arquitectura 8) mas APROBADO_PENDIENTE_STEWARD del contrato OpenAPI. */
public enum DocumentStatus {
    RECIBIDO,
    RECHAZADO,
    RENDERIZADO,
    EN_EXTRACCION,
    EN_REVISION,
    APROBADO_PENDIENTE_STEWARD,
    APROBADO,
    FALLIDO;

    public Set<DocumentStatus> successors() {
        return switch (this) {
            case RECIBIDO -> EnumSet.of(RENDERIZADO, RECHAZADO, FALLIDO);
            case RENDERIZADO -> EnumSet.of(EN_EXTRACCION, FALLIDO);
            case EN_EXTRACCION -> EnumSet.of(EN_REVISION, APROBADO, APROBADO_PENDIENTE_STEWARD, FALLIDO);
            case EN_REVISION -> EnumSet.of(APROBADO, APROBADO_PENDIENTE_STEWARD, RECHAZADO);
            case APROBADO_PENDIENTE_STEWARD -> EnumSet.of(APROBADO);
            case APROBADO, RECHAZADO, FALLIDO -> EnumSet.noneOf(DocumentStatus.class);
        };
    }

    public boolean canTransitionTo(DocumentStatus target) {
        return successors().contains(target);
    }
}
