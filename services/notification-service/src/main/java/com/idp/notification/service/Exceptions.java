package com.idp.notification.service;

/** Errores de negocio del servicio, traducidos por el manejador global sin revelar existencia de recursos. */
public final class Exceptions {

    private Exceptions() {
    }

    /** Solicitud invalida (400) con un codigo estable. */
    public static final class InvalidRequestException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String errorCode;

        public InvalidRequestException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }

    /** Recurso inexistente en el tenant del llamador (404). */
    public static final class NotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NotFoundException(String message) {
            super(message);
        }
    }

    /** Estado incompatible con la operacion (409). */
    public static final class ConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String errorCode;

        public ConflictException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
