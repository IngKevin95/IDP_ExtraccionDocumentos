package com.idp.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idp.audit.application.AuditVerificationService;
import com.idp.audit.support.AuditTestSupport;
import com.idp.events.EventEnvelope;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Consumo real desde dominio.documentos y auditoria.eventos con consumer group propio (AC-01, AC-05):
 * la secuencia la asigna la ingesta, no el offset. Se omite sin Docker; corre en CI.
 */
@Testcontainers(disabledWithoutDocker = true)
class AuditKafkaIntegrationTest extends AuditTestSupport {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.3"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.autoconfigure.exclude", () -> "");
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // El application.yml de test sombrea al de main: sin esto el grupo nuevo arranca en `latest` y pierde
        // los eventos publicados antes de que se le asignen particiones.
        r.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        r.add("spring.kafka.consumer.enable-auto-commit", () -> "false");
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired AuditVerificationService verification;

    private void send(String topic, EventEnvelope e) throws Exception {
        kafka.send(topic, e.tenantId().toString(), SERDE.toJson(e)).get();
    }

    private void awaitEntries(UUID tenant, long expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (countEntries(tenant) < expected && System.nanoTime() < deadline) {
            Thread.sleep(250);
        }
        assertEquals(expected, countEntries(tenant));
    }

    @Test
    void ac01_ac05_consumeAmbosTopicosYEncadenaCadaTenantPorSeparado() throws Exception {
        UUID a = newTenant();
        UUID b = newTenant();
        UUID doc = UUID.randomUUID();
        send("dominio.documentos", recibida(a, doc));
        send("dominio.documentos", aprobada(a, doc));
        send("auditoria.eventos", breakGlass(a));
        send("dominio.documentos", recibida(b, UUID.randomUUID()));

        awaitEntries(a, 3);
        awaitEntries(b, 1);
        assertTrue(verification.verify(a, null, null).isChainIntact());
        assertTrue(verification.verify(b, null, null).isChainIntact());
    }
}
