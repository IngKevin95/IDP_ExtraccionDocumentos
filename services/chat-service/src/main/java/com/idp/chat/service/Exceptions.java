package com.idp.chat.service;

import java.util.UUID;

/** Excepciones de negocio del chat-service. El mapeo HTTP vive en GlobalExceptionHandler. */
public final class Exceptions {

    private Exceptions() {
    }

    /** Sesion inexistente (o de otro tenant: mismo error, SEC-003). */
    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException() {
            super("Sesion no encontrada");
        }
    }

    /** Documento inexistente en el tenant del llamador. */
    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException() {
            super("Documento no encontrado");
        }
    }

    /** Sin acceso a la sesion o al documento (403). */
    public static class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }

    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }

    /** Prompt injection detectado (400, SEC-033). {@code incidentId} liga la respuesta con el evento de seguridad. */
    public static class PromptInjectionException extends RuntimeException {
        private final transient UUID incidentId;

        public PromptInjectionException(UUID incidentId) {
            super("Consulta bloqueada por politicas de seguridad");
            this.incidentId = incidentId;
        }

        public UUID incidentId() {
            return incidentId;
        }
    }

    /** El proveedor LLM o de embeddings no esta disponible o devolvio una respuesta inutilizable (503). */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public LlmUnavailableException(String message) {
            super(message);
        }
    }

    /** Peticion sin identidad utilizable (401). */
    public static class UnauthenticatedException extends RuntimeException {
        public UnauthenticatedException() {
            super("Sin identidad");
        }
    }
}
