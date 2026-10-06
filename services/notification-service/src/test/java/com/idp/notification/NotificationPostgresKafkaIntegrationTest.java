package com.idp.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.notification.domain.DeliveryStatus;
import com.idp.notification.domain.WebhookDelivery;
import com.idp.notification.store.WebhookRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * AC-02/AC-06/AC-07 contra PostgreSQL y Kafka reales: el listener consume extraccion.aprobada, el worker programado
 * entrega al receptor, el outbox publica webhook.entregado por el relay y los mensajes invalidos van al DLT.
 * Tambien prueba el reclamo concurrente con FOR UPDATE SKIP LOCKED y la migracion V1 en PostgreSQL.
 */
@Testcontainers(disabledWithoutDocker = true)
class NotificationPostgresKafkaIntegrationTest extends AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    private static final String TENANT = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        // Un unico PostgreSQL: la URL no usa {tenant}; el silo se simula con el tenant_id de cada fila.
        r.add("idp.tenant-db.jdbc-url-template", PG::getJdbcUrl);
        r.add("idp.tenant-db.username", PG::getUsername);
        r.add("idp.tenant-db.password", PG::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.kafka.security.protocol", () -> "PLAINTEXT");
        r.add("spring.kafka.listener.auto-startup", () -> "true");
        r.add("spring.kafka.consumer.group-id", () -> "notification-it-" + UUID.randomUUID());
        r.add("idp.notification.relay.enabled", () -> "true");
        r.add("idp.notification.relay.interval", () -> "200ms");
        r.add("idp.notification.worker.enabled", () -> "true");
        r.add("idp.notification.worker.interval", () -> "200ms");
        r.add("idp.tenants", () -> TENANT);
    }

    @Autowired WebhookRepository repository;

    private static Map<String, Object> producerProps() {
        return Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "key.serializer", StringSerializer.class,
            "value.serializer", StringSerializer.class);
    }

    private KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, group, ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.METADATA_MAX_AGE_CONFIG, "500",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    @Test
    void ac02_extraccionAprobadaEnKafkaTerminaEntregadaYPublicaWebhookEntregadoPorElRelay() throws Exception {
        migrator.migrate(TENANT);
        allowHosts(TENANT, HOST);
        JsonNode created = createWebhook(TENANT, hookUrl(), "extraccion.aprobada");
        String doc = UUID.randomUUID().toString();

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>("document.events", doc, aprobada(TENANT, doc))).get();
        }

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(receiver.count()).isEqualTo(1));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(deliveries(TENANT).stream().map(d -> d.get("status")).toList()).containsExactly("ENTREGADO"));
        assertThat(JSON.readTree(receiver.received().get(0).body()).path("documentId").asText()).isEqualTo(doc);

        // El relay lleva webhook.entregado a Kafka (sin PII) y marca la fila del outbox como PUBLISHED.
        try (KafkaConsumer<String, String> c = consumer("it-" + UUID.randomUUID())) {
            c.subscribe(List.of("notification.events"));
            JsonNode entregado = null;
            long deadline = System.currentTimeMillis() + 30_000;
            while (entregado == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> rec : c.poll(Duration.ofMillis(500))) {
                    JsonNode n = JSON.readTree(rec.value());
                    if ("webhook.entregado".equals(n.path("eventType").asText())) {
                        entregado = n;
                    }
                }
            }
            assertThat(entregado).as("webhook.entregado en Kafka").isNotNull();
            assertThat(entregado.path("webhookId").asText()).isEqualTo(created.path("id").asText());
            assertThat(entregado.path("documentId").asText()).isEqualTo(doc);
            assertThat(entregado.path("tenantId").asText()).isEqualTo(TENANT);
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(inTenant(TENANT, () -> jdbc.queryForObject(
            "select status from outbox where event_type = 'webhook.entregado'", String.class))).isEqualTo("PUBLISHED"));
    }

    @Test
    void ac13_unMensajeFueraDeContratoVaAlDltSinReintentos() throws Exception {
        migrator.migrate(TENANT);
        String bad = event("extraccion.aprobada", TENANT, UUID.randomUUID().toString(), "approvedBy", "NADIE");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>("document.events", "bad", bad)).get();
        }
          try (KafkaConsumer<String, String> c = consumer("it-dlt-" + UUID.randomUUID())) {
              c.subscribe(Pattern.compile("(?i)document\\.events[.-]dlt"));
              boolean found = false;
            long deadline = System.currentTimeMillis() + 40_000;
            while (!found && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> rec : c.poll(Duration.ofMillis(500))) {
                    found |= rec.value().contains("NADIE");
                }
            }
            assertThat(found).as("mensaje invalido en el DLT").isTrue();
        }
    }

    @Test
    void skipLocked_dosReclamosConcurrentesNoTomanLasMismasEntregas() {
        migrator.migrate(TENANT);
        UUID tenant = UUID.fromString(TENANT);
        UUID webhook = UUID.randomUUID();
        Instant now = clock.instant();
        inTenant(TENANT, () -> {
            for (int i = 0; i < 6; i++) {
                repository.insertDelivery(new WebhookDelivery(UUID.randomUUID(), tenant, webhook, UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), "extraccion.aprobada", "{}", DeliveryStatus.PENDIENTE, 0,
                    now.minusSeconds(5), null, null, null, null, null, now));
            }
            return null;
        });
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        TransactionTemplate other = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<UUID> claimedTwice = inTenant(TENANT, () -> tx.execute(s -> {
            List<WebhookDelivery> first = repository.claimDue(tenant, now, Duration.ofMinutes(5), 3);
            // Mientras la primera transaccion mantiene sus filas bloqueadas, la segunda salta esas filas.
            List<WebhookDelivery> second = other.execute(s2 -> repository.claimDue(tenant, now,
                Duration.ofMinutes(5), 10));
            Set<UUID> a = new HashSet<>(first.stream().map(WebhookDelivery::id).toList());
            List<UUID> overlap = new ArrayList<>(second.stream().map(WebhookDelivery::id).filter(a::contains).toList());
            assertThat(first).hasSize(3);
            assertThat(second).hasSizeGreaterThanOrEqualTo(3);
            return overlap;
        }));
        assertThat(claimedTwice).isEmpty();
    }
}
