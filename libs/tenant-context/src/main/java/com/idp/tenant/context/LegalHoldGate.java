package com.idp.tenant.context;

import java.util.UUID;

/**
 * Fuente unica de legal hold para document-service (purga) y tenant-service (shredding): consulta la tabla
 * {@code legal_hold_records} de la base de control. Falla cerrado: ante error de lectura lanza excepcion y el
 * llamador debe abortar la destruccion.
 */
public interface LegalHoldGate {

    /** Sin control DB (desarrollo/pruebas): nunca hay hold. */
    LegalHoldGate NONE = new LegalHoldGate() {
        @Override
        public boolean isHeld(String tenantId, UUID documentId) {
            return false;
        }

        @Override
        public boolean anyHold(String tenantId) {
            return false;
        }
    };

    /** Hay hold activo que cubre el documento: propio o de tenant completo. */
    boolean isHeld(String tenantId, UUID documentId);

    /** Hay algun hold activo del tenant (de tenant completo o de cualquier documento). */
    boolean anyHold(String tenantId);
}
