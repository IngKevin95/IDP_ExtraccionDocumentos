package com.idp.quality.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.events.EventErrorHandlers;
import com.idp.events.EventSchemaValidator;
import com.idp.events.EventSerde;
import com.idp.events.IdempotentEventConsumer;
import com.idp.events.JdbcOutboxPublisher;
import com.idp.events.OutboxPublisher;
import com.idp.events.OutboxRelay;
import com.idp.events.OutboxRepository;
import com.idp.events.TenantOutboxAccess;
import com.idp.quality.golden.EvaluationEngine;
import com.idp.quality.golden.ExtractionRunner;
import com.idp.quality.golden.FilePredictionRunner;
import com.idp.quality.metrics.BlindSampler;
import com.idp.quality.replay.ReplayService;
import com.idp.security.AccesoRevocadoKafkaListener;
import com.idp.security.CachingRoleAssignmentVerifier;
import com.idp.security.JdbcRoleAssignmentSource;
import com.idp.security.RoleAssignmentSource;
import com.idp.security.RoleAssignmentVerifier;
import com.idp.security.TenantAuthorizer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

/** Beans del servicio: eventos idempotentes, seguridad por rol, muestreo ciego, evaluacion y replay. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(QualityProperties.class)
public class QualityConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ObjectMapper idpObjectMapper() {
        // Los montos del golden set no pasan por double: decimales exactos.
        return new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature
            .USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @Bean
    EventSerde eventSerde(ObjectMapper idpObjectMapper) {
        return new EventSerde(idpObjectMapper);
    }

    @Bean
    EventSchemaValidator eventSchemaValidator(EventSerde serde) {
        return new EventSchemaValidator(serde);
    }

    @Bean
    IdempotentEventConsumer idempotentEventConsumer(JdbcTemplate jdbc, TransactionTemplate tx,
                                                    EventSchemaValidator validator, EventSerde serde) {
        return new IdempotentEventConsumer(jdbc, tx, validator, serde);
    }

    @Bean
    OutboxRepository outboxRepository(JdbcTemplate jdbc) {
        return new OutboxRepository(jdbc);
    }

    @Bean
    OutboxPublisher outboxPublisher(OutboxRepository repo, EventSchemaValidator validator, EventSerde serde) {
        return new JdbcOutboxPublisher(repo, validator, serde);
    }

    /**
     * quality-service no tiene silo por tenant: su outbox vive en la base de control. El acceso ignora el tenant y
     * opera siempre sobre esa base (cada fila conserva su tenantId, que el relay publica en la cabecera).
     */
    @Bean
    TenantOutboxAccess controlOutboxAccess(OutboxRepository repo, TransactionTemplate tx) {
        return new TenantOutboxAccess() {
            @Override
            public <T> T inTransaction(String tenantId, java.util.function.Function<OutboxRepository, T> work) {
                return tx.execute(status -> work.apply(repo));
            }
        };
    }

    @Bean
    @ConditionalOnProperty(value = "quality.relay.enabled", matchIfMissing = true)
    OutboxRelayJob outboxRelayJob(TenantOutboxAccess access, KafkaTemplate<String, String> kafka,
                                  @Value("${quality.kafka.topic:dominio.documentos}") String topic) {
        return new OutboxRelayJob(new OutboxRelay(access, () -> List.of("control"), kafka, eventType -> topic, 100,
            Duration.ofSeconds(10)));
    }

    /** Planifica el relay del outbox de la base de control hacia Kafka. */
    public static final class OutboxRelayJob {
        private final OutboxRelay relay;

        OutboxRelayJob(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${quality.relay.interval:1s}")
        public void run() {
            relay.relayAll();
        }
    }

    /** 3 reintentos con backoff exponencial y luego DLT; los mensajes fuera de contrato van directo a DLT. */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        return EventErrorHandlers.deadLetter(template);
    }

    /** Semilla secreta de al menos 32 bytes salvo dev-mode (SEC-051); la validacion vive en {@link BlindSampler}. */
    @Bean
    BlindSampler blindSampler(QualityProperties props, @Value("${idp.security.dev-mode:false}") boolean devMode) {
        QualityProperties.BlindSampling bs = props.blindSampling();
        return new BlindSampler(bs.seed(), bs.rate(), bs.rateByTypology(), devMode);
    }

    @Bean
    EvaluationEngine evaluationEngine(QualityProperties props) {
        QualityProperties.Calibration c = props.calibration();
        return new EvaluationEngine(new EvaluationEngine.Params(c.targetAuto(), c.targetRevisar(),
            c.minFieldSamples(), c.eceBins()));
    }

    /** Adaptador por archivos; sustituible por un adaptador hacia el proveedor homologado sin tocar el servicio. */
    @Bean
    ExtractionRunner extractionRunner(QualityProperties props, ObjectMapper idpObjectMapper) {
        String dir = props.runner().predictionsDir();
        return new FilePredictionRunner(dir.isBlank() ? null : Path.of(dir), idpObjectMapper);
    }

    @Bean(destroyMethod = "shutdown")
    java.util.concurrent.ExecutorService replayExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    ReplayService replayService(ExtractionRunner runner, EvaluationEngine engine,
                                java.util.concurrent.ExecutorService replayExecutor) {
        return new ReplayService(runner, engine, replayExecutor);
    }

    @Bean
    Executor qualityExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("quality-eval-");
        ex.initialize();
        return ex;
    }

    /** role_assignment vive en la base de control, que es la de este servicio. */
    @Bean
    RoleAssignmentSource roleAssignmentSource(JdbcTemplate jdbc) {
        return new JdbcRoleAssignmentSource(jdbc);
    }

    @Bean
    CachingRoleAssignmentVerifier roleAssignmentVerifier(RoleAssignmentSource source) {
        return new CachingRoleAssignmentVerifier(source);
    }

    /** acceso.revocado purga la cache de roles de este servicio. */
    @Bean
    AccesoRevocadoKafkaListener accesoRevocadoListener(CachingRoleAssignmentVerifier verifier, EventSerde serde) {
        return new AccesoRevocadoKafkaListener(verifier, serde);
    }

    @Bean
    TenantAuthorizer tenantAuthorizer(RoleAssignmentVerifier verifier) {
        return new TenantAuthorizer(verifier);
    }
}
