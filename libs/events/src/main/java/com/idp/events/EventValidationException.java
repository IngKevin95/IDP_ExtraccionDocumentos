package com.idp.events;

/** Evento malformado o que incumple su JSON Schema. Error fatal: no se reintenta, va a DLT. */
public class EventValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EventValidationException(String message) {
        super(message);
    }

    public EventValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
