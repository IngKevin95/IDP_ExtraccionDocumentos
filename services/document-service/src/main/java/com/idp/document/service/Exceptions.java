package com.idp.document.service;

/** Excepciones de negocio del document-service. El mapeo HTTP vive en GlobalExceptionHandler. */
public final class Exceptions {

    private Exceptions() {
    }

    public static class InvalidFileFormatException extends RuntimeException {
        public InvalidFileFormatException(String message) {
            super(message);
        }
    }

    public static class FileTooLargeException extends RuntimeException {
        public FileTooLargeException(String message) {
            super(message);
        }
    }

    public static class InvalidMetadataException extends RuntimeException {
        public InvalidMetadataException(String message) {
            super(message);
        }
    }

    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException() {
            super("Documento no encontrado");
        }
    }

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
}
