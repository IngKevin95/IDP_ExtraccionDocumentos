package com.idp.extraction.config;

import com.idp.events.JdbcTenantOutboxAccess;
import com.idp.events.OutboxRelay;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Relay del outbox hacia Kafka para los tenants configurados ({@code extraction.relay.*}). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "extraction.relay.enabled", havingValue = "true")
public class OutboxRelayConfig {

    @Bean
    OutboxRelay outboxRelay(DataSource tenantDataSource, ExtractionProperties props, KafkaTemplate<String, String> kafka) {
        List<String> tenants = List.copyOf(props.relay().tenants());
        return new OutboxRelay(new JdbcTenantOutboxAccess(tenantDataSource), () -> tenants, kafka);
    }

    @Bean
    RelayJob relayJob(OutboxRelay relay) {
        return new RelayJob(relay);
    }

    /** Tarea planificada del relay. */
    public static final class RelayJob {
        private final OutboxRelay relay;

        RelayJob(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${extraction.relay.interval:PT1S}")
        public void run() {
            relay.relayAll();
        }
    }
}
