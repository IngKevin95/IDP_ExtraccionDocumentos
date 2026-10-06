package com.idp.extraction.config;

import com.idp.events.EventTopology;
import com.idp.events.JdbcTenantOutboxAccess;
import com.idp.events.OutboxRelay;
import com.idp.tenant.context.JdbcTenantDirectory;
import com.idp.tenant.context.StaticTenantDirectory;
import com.idp.tenant.context.TenantDirectory;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Relay del outbox hacia Kafka para los tenants configurados ({@code extraction.relay.*}). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "extraction.relay.enabled", havingValue = "true")
public class OutboxRelayConfig {

    @Bean
    TenantDirectory tenantDirectory(ExtractionProperties props, Clock clock) {
        ExtractionProperties.Control c = props.control();
        if (c.url().isBlank()) {
            return new StaticTenantDirectory(props.relay().tenants());
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(c.url());
        ds.setUsername(c.username());
        ds.setPassword(c.password());
        ds.setMaximumPoolSize(2);
        return new JdbcTenantDirectory(new JdbcTemplate(ds), c.directoryTtl(), clock);
    }

    @Bean
    OutboxRelay outboxRelay(DataSource tenantDataSource, TenantDirectory tenants, ExtractionProperties props,
                            KafkaTemplate<String, String> kafka) {
        return new OutboxRelay(new JdbcTenantOutboxAccess(tenantDataSource), tenants::activeTenants, kafka,
            EventTopology.defaults(), "extraction-service", 100, Duration.ofSeconds(10));
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
