package com.idp.events;

import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/** Manejo de errores de consumo: 3 reintentos con backoff exponencial y luego DLT (AC-05, AC-08). */
public final class EventErrorHandlers {

    public static final int MAX_RETRIES = 3;

    private EventErrorHandlers() {
    }

    /**
     * Errores temporales se reintentan; los fatales ({@link EventValidationException}) van directo a DLT.
     * El DLT por defecto es {@code <topic>-dlt}.
     */
    public static DefaultErrorHandler deadLetter(KafkaOperations<?, ?> operations) {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxAttempts(MAX_RETRIES);
        DefaultErrorHandler handler = new DefaultErrorHandler(new DeadLetterPublishingRecoverer(operations), backOff);
        handler.addNotRetryableExceptions(EventValidationException.class);
        return handler;
    }
}
