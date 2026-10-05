package com.idp.extraction.typology;

/** YAML de tipologia invalido: el arranque debe fallar (fail-fast, SEC-023). */
public class InvalidTypologyException extends RuntimeException {
    public InvalidTypologyException(String message) {
        super(message);
    }

    public InvalidTypologyException(String message, Throwable cause) {
        super(message, cause);
    }
}
