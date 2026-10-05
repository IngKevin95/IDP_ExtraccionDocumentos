package com.idp.document.config;

import com.idp.events.EventErrorHandlers;
import com.idp.events.EventSchemaValidator;
import com.idp.events.EventSerde;
import com.idp.events.IdempotentEventConsumer;
import com.idp.events.JdbcOutboxPublisher;
import com.idp.events.JdbcTenantOutboxAccess;
import com.idp.events.OutboxPublisher;
import com.idp.events.OutboxRelay;
import com.idp.events.OutboxRepository;
import com.idp.events.TenantOutboxAccess;
import com.idp.tenant.context.TenantDirectory;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/** Outbox transaccional, relay a Kafka y consumidor idempotente (libs/events). */
@Configuration
@EnableScheduling
public class EventsConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EventSerde eventSerde() {
        return new EventSerde();
    }

    @Bean
    EventSchemaValidator eventSchemaValidator(EventSerde serde) {
        return new EventSchemaValidator(serde);
    }

    @Bean
    OutboxRepository outboxRepository(JdbcTemplate jdbc) {
        return new OutboxRepository(jdbc);
    }

    @Bean
    OutboxPublisher outboxPublisher(OutboxRepository repo, EventSchemaValidator validator, EventSerde serde) {
        return new JdbcOutboxPublisher(repo, validator, serde);
    }

    @Bean
    IdempotentEventConsumer idempotentEventConsumer(JdbcTemplate jdbc, TransactionTemplate tx,
                                                    EventSchemaValidator validator, EventSerde serde) {
        return new IdempotentEventConsumer(jdbc, tx, validator, serde);
    }

    @Bean
    TenantOutboxAccess tenantOutboxAccess(DataSource routedDataSource) {
        return new JdbcTenantOutboxAccess(routedDataSource);
    }

    /** 3 reintentos con backoff exponencial y luego DLT; los mensajes fuera de contrato van directo a DLT. */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        return EventErrorHandlers.deadLetter(template);
    }

    @Bean
    @ConditionalOnProperty(value = "idp.document.relay.enabled", matchIfMissing = true)
    OutboxRelayJob outboxRelayJob(TenantOutboxAccess access, KafkaTemplate<String, String> kafka,
                                  @Value("${idp.topic:dominio.documentos}") String topic,
                                  TenantDirectory tenants) {
        OutboxRelay relay = new OutboxRelay(access, tenants::activeTenants, kafka,
                eventType -> topic, 100, Duration.ofSeconds(10));
        return new OutboxRelayJob(relay);
    }

    /** Planifica el relay del outbox de los tenants activos del directorio. */
    public static final class OutboxRelayJob {
        private final OutboxRelay relay;

        OutboxRelayJob(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${idp.document.relay.interval:1s}")
        public void run() {
            relay.relayAll();
        }
    }
}
