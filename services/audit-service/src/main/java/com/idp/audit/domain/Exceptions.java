package com.idp.audit.domain;

import java.util.UUID;

/** Excepciones de negocio del audit-service. */
public final class Exceptions {
    private Exceptions() {}

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    /** La retencion legal bloquea la destruccion solicitada (AC-08). */
    public static class LegalHoldActiveException extends RuntimeException {
        public LegalHoldActiveException(String message) {
            super(message);
        }
    }

    /**
     * La cadena del tenant es inconsistente (alteracion de BD o hueco). En el consumidor Kafka bloquea la
     * particion: se reintenta indefinidamente y no se hace commit del offset (AC-02).
     */
    public static class ChainIntegrityException extends RuntimeException {
        private final UUID tenantId;
        private final long sequenceId;
        private final String errorType;

        public ChainIntegrityException(UUID tenantId, long sequenceId, String errorType, String message) {
            super(message);
            this.tenantId = tenantId;
            this.sequenceId = sequenceId;
            this.errorType = errorType;
        }

        public UUID tenantId() {
            return tenantId;
        }

        public long sequenceId() {
            return sequenceId;
        }

        public String errorType() {
            return errorType;
        }
    }
}
