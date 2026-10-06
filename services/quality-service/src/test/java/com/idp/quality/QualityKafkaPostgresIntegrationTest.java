package com.idp.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Consumo real desde Kafka hacia PostgreSQL real (migracion Flyway incluida): AC-01, AC-02, AC-03 de punta a punta
 * e idempotencia ante reentrega. Se omite sin Docker; corre en CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(TestBeans.class)
class QualityKafkaPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");
    @Container
    static final ConfluentKafkaContainer KAFKA =
        new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.3"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.kafka.listener.auto-startup", () -> "true");
        // El application.yml de test sombrea al de main: se fija el reinicio de offsets para no perder eventos.
        r.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcTemplate jdbc;

    private static ObjectNode event(String type, UUID tenant, Instant at) {
        ObjectNode n = JSON.createObjectNode();
        n.put("eventId", UUID.randomUUID().toString());
        n.put("eventType", type);
        n.put("schemaVersion", 1);
        n.put("occurredAt", at.toString());
        n.put("tenantId", tenant.toString());
        n.put("correlationId", UUID.randomUUID().toString());
        n.put("documentId", UUID.randomUUID().toString());
        return n;
    }

    private void awaitTotal(UUID tenant, int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (total(tenant) < expected && System.nanoTime() < deadline) {
            Thread.sleep(250);
        }
        assertThat(total(tenant)).isEqualTo(expected);
    }

    private int total(UUID tenant) {
        Integer n = jdbc.queryForObject("select coalesce(sum(total_documentos + hitl_count + "
            + "blind_samples_total), 0) from qa_metrics_daily where tenant_id = ?", Integer.class, tenant);
        return n == null ? 0 : n;
    }

    @Test
    void consumeDeKafkaAPostgresDeFormaIdempotente() throws Exception {
        UUID tenant = UUID.randomUUID();
        Instant now = Instant.now();
        ObjectNode stp = event("extraccion.aprobada", tenant, now).put("approvedBy", "AUTO_STP")
            .put("typology", "EC");
        ObjectNode review = event("revision.completada", tenant, now).put("taskId", UUID.randomUUID().toString())
            .put("action", "APROBADO").put("reviewerId", "revisor-1").put("typology", "EC");
        review.putArray("correctedFields").addObject().put("field", "monto").put("correctionType", "VALOR");
        ObjectNode blind = event("revision.completada", tenant, now).put("taskId", UUID.randomUUID().toString())
            .put("action", "APROBADO").put("reviewerId", "revisor-1").put("typology", "EC")
            .put("blindSample", true);
        blind.putArray("correctedFields").addObject().put("field", "radicado").put("correctionType", "FORMATO");

        String key = tenant.toString();
        kafka.send("documentos.eventos", key, stp.toString()).get();
        kafka.send("documentos.eventos", key, stp.toString()).get(); // reentrega del mismo eventId
        kafka.send("revision.eventos", key, review.toString()).get();
        kafka.send("revision.eventos", key, blind.toString()).get();
        // SEC-052: una aprobacion publicada por el topico de otro productor se ignora (no cuenta).
        kafka.send("revision.eventos", key, event("extraccion.aprobada", tenant, now).put("approvedBy", "AUTO_STP")
            .put("typology", "EC").toString()).get();

        awaitTotal(tenant, 3); // 1 aprobada + 1 hitl + 1 muestra ciega
        Thread.sleep(1000); // margen para detectar un doble conteo de la reentrega
        assertThat(total(tenant)).isEqualTo(3);
        assertThat(jdbc.queryForMap("select stp_count, hitl_count, silent_error_count, blind_samples_total "
            + "from qa_metrics_daily where tenant_id = ?", tenant))
            .containsEntry("stp_count", 1).containsEntry("hitl_count", 1).containsEntry("silent_error_count", 1)
            .containsEntry("blind_samples_total", 1);
        assertThat(jdbc.queryForObject("select count(*) from qa_field_error where tenant_id = ?", Integer.class,
            tenant)).isEqualTo(2);
    }
}
