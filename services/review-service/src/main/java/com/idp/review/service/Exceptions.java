package com.idp.review.service;

/** Excepciones de negocio del review-service. El mapeo HTTP vive en GlobalExceptionHandler. */
public final class Exceptions {

    private Exceptions() {
    }

    /** Tarea inexistente o de otro tenant: mismo error en ambos casos (SEC-003). */
    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException() {
            super("Tarea no encontrada");
        }
    }

    /** La tarea no esta en un estado que admita la operacion (400 segun contrato). */
    public static class InvalidStateException extends RuntimeException {
        public InvalidStateException(String message) {
            super(message);
        }
    }

    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }

    /** Conflicto de asignacion o carrera perdida (409). */
    public static class ConflictException extends RuntimeException {
        private final String errorCode;

        public ConflictException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }

    /** Violacion de la regla de cuatro ojos (SEC-009): 403. */
    public static class FourEyesViolationException extends RuntimeException {
        public FourEyesViolationException(String message) {
            super(message);
        }
    }
}
