package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.events.EventSchemaValidator;
import com.idp.review.config.PersistenceConfig.TenantSchemaMigrator;
import com.idp.security.Roles;
import com.idp.tenant.context.TenantContextHolder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * T-09 y T-10 de extremo a extremo contra Kafka real: extraccion.requiere_revision crea la tarea (AC-01), la aprobacion
 * con cuatro ojos escribe el outbox y el relay publica revision.completada conforme a su esquema.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=true",
    "spring.kafka.security.protocol=PLAINTEXT",
    "idp.review.relay.enabled=true",
    "idp.review.relay.interval=200ms",
    "idp.tenants=" + ReviewKafkaIntegrationTest.TENANT
})
@AutoConfigureMockMvc
@Import(TestBeans.class)
class ReviewKafkaIntegrationTest {

    static final String TENANT = "6f1c3b0e-8d2a-4c57-9a43-2b7e5d9f1a10";
    /** requiere_revision llega por extraction.events; revision.completada se publica en review.events. */
    private static final String IN_TOPIC = "extraction.events";
    private static final String OUT_TOPIC = "review.events";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantSchemaMigrator migrator;
    @Autowired TestBeans.MutableRoleSource roles;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired EventSchemaValidator validator;

    private final ObjectMapper json = new ObjectMapper();

    private String requiereRevision(UUID documentId, UUID taskId) throws Exception {
        var n = json.createObjectNode();
        n.put("eventId", UUID.randomUUID().toString());
        n.put("eventType", "extraccion.requiere_revision");
        n.put("schemaVersion", 1);
        n.put("occurredAt", java.time.Instant.now().toString());
        n.put("tenantId", TENANT);
        n.put("correlationId", UUID.randomUUID().toString());
        n.put("documentId", documentId.toString());
        n.put("taskId", taskId.toString());
        return json.writeValueAsString(n);
    }

    private int taskRows(UUID taskId) {
        TenantContextHolder.setTenantId(TENANT);
        try {
            return jdbc.queryForObject("select count(*) from review_task where id = ?", Integer.class, taskId);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private JsonNode awaitCompleted(UUID taskId) {
        Map<String, Object> cfg = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(cfg)) {
            consumer.subscribe(List.of(OUT_TOPIC));
            List<JsonNode> found = new ArrayList<>();
            await().atMost(Duration.ofSeconds(45)).until(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode n = json.readTree(r.value());
                    if ("revision.completada".equals(n.path("eventType").asText())
                            && taskId.toString().equals(n.path("taskId").asText())) {
                        found.add(n);
                    }
                }
                return !found.isEmpty();
            });
            return found.get(0);
        }
    }

    @Test
    void ac01_ac05_deRequiereRevisionAlaRevisionCompletadaConCuatroOjosPorKafka() throws Exception {
        migrator.migrate(TENANT);
        roles.grant(TENANT, "ana", Roles.REVISOR);
        roles.grant(TENANT, "beto", Roles.REVISOR);
        UUID taskId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        kafka.send(IN_TOPIC, documentId.toString(), requiereRevision(documentId, taskId)).get();
        await().atMost(Duration.ofSeconds(45)).until(() -> taskRows(taskId) == 1);

        mvc.perform(post("/v1/review/tasks/" + taskId + "/corrections")
                .with(jwt().jwt(j -> j.subject("ana").claim("tenant_id", TENANT)))
                .contentType("application/json")
                .content("[{\"fieldName\":\"monto\",\"correctedValue\":\"2500000\"}]")).andReturn();
        mvc.perform(post("/v1/review/tasks/" + taskId + "/approve")
                .with(jwt().jwt(j -> j.subject("ana").claim("tenant_id", TENANT)))).andReturn();
        int secondStatus = mvc.perform(post("/v1/review/tasks/" + taskId + "/approve-secondary")
                .with(jwt().jwt(j -> j.subject("beto").claim("tenant_id", TENANT)))).andReturn().getResponse()
                .getStatus();

        assertThat(secondStatus).isEqualTo(200);
        JsonNode event = awaitCompleted(taskId);
        validator.validateFlat(event);
        assertThat(event.path("action").asText()).isEqualTo("APROBADO");
        assertThat(event.path("reviewerId").asText()).isEqualTo("ana");
        assertThat(event.path("secondReviewerId").asText()).isEqualTo("beto");
        assertThat(event.path("criticalCorrection").asBoolean()).isTrue();
        assertThat(event.path("documentId").asText()).isEqualTo(documentId.toString());
        assertThat(event.path("tenantId").asText()).isEqualTo(TENANT);
    }
}
