package com.idp.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Cubre eventos-kafka AC-01, AC-02, AC-03 y AC-04 contra Postgres y Kafka reales. */
@Testcontainers(disabledWithoutDocker = true)
class OutboxKafkaIntegrationTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    private final EventSerde serde = new EventSerde();
    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private OutboxRepository repo;

    @BeforeEach
    void setUp() {
        ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/events/outbox-schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        jdbc.update("delete from outbox");
        jdbc.update("delete from processed_event");
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        repo = new OutboxRepository(jdbc);
    }

    @Test
    void ac01_ac02_ac04_publicaEnOutboxYElRelayLoLlevaAKafkaConTenantEnCabeceras() {
        UUID tenant = UUID.randomUUID();
        EventEnvelope e = TestEvents.accesoRevocado(tenant);
        JdbcOutboxPublisher publisher = new JdbcOutboxPublisher(repo, new EventSchemaValidator(serde), serde);

        tx.executeWithoutResult(s -> publisher.publish("user-7", e));
        assertEquals("PENDING", jdbc.queryForObject("select status from outbox", String.class));

        KafkaTemplate<String, String> kafka = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
            "bootstrap.servers", KAFKA.getBootstrapServers(),
            "key.serializer", StringSerializer.class,
            "value.serializer", StringSerializer.class)));
        TenantOutboxAccess access = new TenantOutboxAccess() {
            @Override
            public <T> T inTransaction(String tenantId, java.util.function.Function<OutboxRepository, T> work) {
                return tx.execute(s -> work.apply(repo));
            }
        };
        assertEquals(1, new OutboxRelay(access, () -> List.of(tenant.toString()), kafka).relayAll());
        assertEquals("PUBLISHED", jdbc.queryForObject("select status from outbox", String.class));

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("acceso.revocado"));
            ConsumerRecord<String, String> rec = null;
            for (int i = 0; i < 20 && rec == null; i++) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    rec = r;
                }
            }
            assertTrue(rec != null, "no llego el mensaje a Kafka");
            assertEquals("user-7", rec.key());
            assertEquals(tenant.toString(),
                new String(rec.headers().lastHeader("tenantId").value(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void ac03_elMismoEventoSoloSeProcesaUnaVez() {
        EventEnvelope e = TestEvents.accesoRevocado(UUID.randomUUID());
        IdempotentEventConsumer consumer = new IdempotentEventConsumer(jdbc, tx, new EventSchemaValidator(serde), serde);
        String json = serde.toJson(e);
        int[] calls = {0};

        assertEquals(IdempotentEventConsumer.Result.PROCESSED, consumer.consume(json, ev -> calls[0]++));
        assertEquals(IdempotentEventConsumer.Result.DUPLICATE, consumer.consume(json, ev -> calls[0]++));
        assertEquals(1, calls[0]);
    }

    @Test
    void skipLocked_dosRelaysConcurrentesNoTomanLasMismasFilas() {
        repo.insert(UUID.randomUUID(), "k", "acceso.revocado", UUID.randomUUID(), "{}");
        TransactionTemplate other = new TransactionTemplate(new DataSourceTransactionManager(ds));
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<OutboxRecord> second = tx.execute(s -> {
            assertEquals(1, repo.lockPending(10).size());
            return other.execute(s2 -> new OutboxRepository(new JdbcTemplate(ds)).lockPending(10));
        });

        assertTrue(second.isEmpty());
    }
}
