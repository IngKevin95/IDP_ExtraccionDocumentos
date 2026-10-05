package com.idp.document.domain;

public class InvalidTransitionException extends RuntimeException {
    public InvalidTransitionException(DocumentStatus from, DocumentStatus to) {
        super("Transicion invalida " + from + " -> " + to);
    }
}
