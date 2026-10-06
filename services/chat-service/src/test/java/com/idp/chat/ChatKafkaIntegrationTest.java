package com.idp.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Kafka real: extraccion.aprobada por document.events indexa (AC-01); un mensaje fuera de contrato va al DLT; la
 * reentrega no duplica; el outbox se relaya a audit.signals con eventos conformes a su esquema (SEC-050, SEC-052).
 */
@Testcontainers(disabledWithoutDocker = true)
@org.springframework.boot.test.context.SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=true",
    "idp.chat.relay.enabled=true",
    "idp.chat.relay.interval=200ms"
})
class ChatKafkaIntegrationTest extends AbstractChatIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, String> kafka;

    private KafkaConsumer<String, String> consumer() {
        Map<String, Object> cfg = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(cfg);
    }

    @Test
    void indexaPorKafkaEnviaElMensajeInvalidoAlDltYRelayaLasSenalesDeAuditoria() throws Exception {
        UUID doc = UUID.randomUUID();
        registerDocument(tenant, doc, "CONFIDENCIAL", "u");
        putTextLayer(tenant, doc, "El juzgado ordena el embargo por un monto de 100 pesos.");
        String event = aprobada(tenant, UUID.randomUUID(), doc);

        // Poison pill primero: no debe bloquear al consumidor.
        kafka.send("document.events", doc.toString(), "{esto no es json").get();
        kafka.send("document.events", doc.toString(), event).get();
        await().atMost(Duration.ofSeconds(60)).until(() -> count("select count(*) from chunk where document_id = ?",
                doc) == 1);
        // Reentrega del mismo evento: sin efectos adicionales.
        kafka.send("document.events", doc.toString(), event).get();
        // Documento inexistente: acceso denegado que el relay publica en audit.signals.
        UUID ghost = UUID.randomUUID();
        putTextLayer(tenant, ghost, "texto");
        kafka.send("document.events", ghost.toString(), aprobada(tenant, UUID.randomUUID(), ghost)).get();

        try (KafkaConsumer<String, String> c = consumer()) {
            c.subscribe(Pattern.compile("(?i)document\\.events[.-]dlt"));
            boolean[] dlt = {false};
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                c.poll(Duration.ofMillis(500)).forEach(r -> dlt[0] |= r.value().contains("esto no es json"));
                return dlt[0];
            });
        }
        try (KafkaConsumer<String, String> c = consumer()) {
            c.subscribe(List.of("audit.signals"));
            List<JsonNode> found = new ArrayList<>();
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(500))) {
                    JsonNode n = JSON.readTree(r.value());
                    if ("seguridad.acceso_denegado".equals(n.path("eventType").asText())
                            && ghost.toString().equals(n.path("resourceId").asText())) {
                        found.add(n);
                    }
                }
                return !found.isEmpty();
            });
            validator.validateFlat(found.get(0));
            assertThat(found.get(0).path("tenantId").asText()).isEqualTo(tenant);
        }
        assertThat(count("select count(*) from chunk where document_id = ?", doc)).isEqualTo(1);
        assertThat(count("select count(*) from processed_event")).isEqualTo(1);
    }
}
