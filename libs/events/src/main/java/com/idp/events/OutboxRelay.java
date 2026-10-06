package com.idp.events;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Relay multi-tenant del outbox. Por cada tenant lee filas PENDING con
 * {@code FOR UPDATE SKIP LOCKED} (varias instancias no se pisan), publica en Kafka con
 * key=partitionKey y cabecera tenantId, y las marca PUBLISHED en la misma transaccion.
 * Ante un fallo de publicacion conserva el orden: detiene el lote y registra el intento.
 * La planificacion (p. ej. @Scheduled) es responsabilidad del servicio que lo usa.
 */
public final class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private final TenantOutboxAccess access;
    private final Supplier<Collection<String>> tenantIds;
    private final KafkaTemplate<String, String> kafka;
    private final Function<String, String> topicResolver;
    private final int batchSize;
    private final Duration sendTimeout;

    private OutboxRelay(TenantOutboxAccess access, Supplier<Collection<String>> tenantIds,
                        KafkaTemplate<String, String> kafka, Function<String, String> topicResolver,
                        int batchSize, Duration sendTimeout) {
        this.access = access;
        this.tenantIds = tenantIds;
        this.kafka = kafka;
        this.topicResolver = topicResolver;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    /**
     * Relay de un servicio productor: cada evento va al topico que dicta {@link EventTopology} (por eventType). Un
     * eventType que el servicio no produce falla de forma explicita (la fila queda FAILED y no se publica).
     */
    public OutboxRelay(TenantOutboxAccess access, Supplier<Collection<String>> tenantIds,
                       KafkaTemplate<String, String> kafka, EventTopology topology, String producerService,
                       int batchSize, Duration sendTimeout) {
        this(access, tenantIds, kafka, eventType -> topology.allowedTopicFor(producerService, eventType),
            batchSize, sendTimeout);
    }

    /**
     * Topic por defecto: el eventType, sin validar productor.
     *
     * @deprecated solo para pruebas de la libreria; los servicios usan el constructor con {@link EventTopology}.
     */
    @Deprecated
    public OutboxRelay(TenantOutboxAccess access, Supplier<Collection<String>> tenantIds,
                       KafkaTemplate<String, String> kafka) {
        this(access, tenantIds, kafka, Function.identity(), 100, Duration.ofSeconds(10));
    }

    /** Relay de todos los tenants; el fallo de uno no bloquea a los demas. Devuelve eventos publicados. */
    public int relayAll() {
        int total = 0;
        for (String tenantId : tenantIds.get()) {
            try {
                total += relayTenant(tenantId);
            } catch (RuntimeException e) {
                LOG.warn("Relay del tenant {} fallo: {}", tenantId, e.getClass().getSimpleName());
            }
        }
        return total;
    }

    public int relayTenant(String tenantId) {
        return access.inTransaction(tenantId, repo -> {
            List<OutboxRecord> rows = repo.lockPending(batchSize);
            int published = 0;
            for (OutboxRecord row : rows) {
                try {
                    send(tenantId, row);
                    repo.markPublished(row.id());
                    published++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    repo.markFailed(row.id(), e.getClass().getSimpleName());
                    break;
                }
            }
            return published;
        });
    }

    private void send(String tenantId, OutboxRecord row)
        throws InterruptedException, ExecutionException, TimeoutException {
        ProducerRecord<String, String> rec = new ProducerRecord<>(topicResolver.apply(row.eventType()), null,
            row.partitionKey(), row.payload());
        rec.headers().add("tenantId", row.tenantId().toString().getBytes(StandardCharsets.UTF_8));
        rec.headers().add("eventId", row.id().toString().getBytes(StandardCharsets.UTF_8));
        rec.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        kafka.send(rec).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
