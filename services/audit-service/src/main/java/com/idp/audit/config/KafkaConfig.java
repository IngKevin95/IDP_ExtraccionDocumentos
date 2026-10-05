package com.idp.audit.config;

import com.idp.audit.application.AuditMetrics;
import com.idp.events.EventValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Consumo con reintento BLOQUEANTE: sin {@code @RetryableTopic} y sin DLT, para no reordenar ni perder
 * eventos de la hash-chain (ADR 0016). Todo error de persistencia o de integridad se reintenta
 * indefinidamente con backoff acotado; la particion queda detenida y el offset sin avanzar. Unica excepcion:
 * un mensaje fuera de contrato (no puede encadenarse jamas) se descarta con alerta CRITICA para no
 * detener la auditoria de todo el tenant.
 */
@Configuration
public class KafkaConfig {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConfig.class);

    /** Backoff exponencial 1 s a 30 s sin limite de intentos ni de tiempo. */
    public static BackOff blockingBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        return backOff;
    }

    @Bean
    DefaultErrorHandler auditErrorHandler(AuditMetrics metrics) {
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            metrics.count("audit.ingestion.rejected");
            LOG.error("CRITICAL evento descartado por violar el contrato: topico={} particion={} offset={} causa={}",
                    record.topic(), record.partition(), record.offset(), ex.getClass().getSimpleName());
        }, blockingBackOff());
        handler.addNotRetryableExceptions(EventValidationException.class);
        handler.setRetryListeners((record, ex, attempt) -> {
            metrics.count("audit.ingestion.retries");
            LOG.error("CRITICAL ingesta de auditoria bloqueada: topico={} particion={} offset={} intento={} causa={}",
                    record.topic(), record.partition(), record.offset(), attempt, ex.getClass().getSimpleName());
        });
        return handler;
    }
}
