package com.idp.audit.support;

import com.idp.events.EventSchemaValidator;
import com.idp.events.EventSerde;
import com.idp.events.IdempotentEventConsumer;
import com.idp.kms.InMemoryKeyService;
import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {
    @Bean
    @Primary
    InMemoryKeyService keyService() {
        return new InMemoryKeyService();
    }

    @Bean
    @Primary
    RecordingImmutableStore immutableStore() {
        return new RecordingImmutableStore();
    }

    @Bean
    @Primary
    CapturingPublisher publisher() {
        return new CapturingPublisher();
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    /**
     * H2 no admite el objetivo de conflicto de {@code on conflict (event_id) do nothing} que usa la libreria
     * de eventos. Solo en tests se reescribe a la forma sin objetivo (valida en H2 y PostgreSQL); los IT con
     * PostgreSQL real ejercitan la sentencia original.
     */
    @Bean
    @Primary
    IdempotentEventConsumer h2CompatibleConsumer(DataSource ds, TransactionTemplate tx, EventSchemaValidator validator,
                                                 EventSerde serde) {
        JdbcTemplate compat = new JdbcTemplate(ds) {
            @Override
            public int update(String sql, Object... args) {
                return super.update(sql.replace("on conflict (event_id) do nothing", "on conflict do nothing"), args);
            }
        };
        return new IdempotentEventConsumer(compat, tx, validator, serde);
    }
}
