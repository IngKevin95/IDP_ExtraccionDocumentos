package com.idp.tenant.infrastructure.messaging;

import java.util.concurrent.TimeUnit;
import com.idp.events.EventTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Relay at-least-once de la tabla outbox hacia Kafka (los consumidores son idempotentes por eventId). */
@Component
@ConditionalOnProperty(name = "idp.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcOutbox outbox;
    private final KafkaTemplate<String, String> kafka;
    private final EventTopology topology = EventTopology.defaults();

    public OutboxRelay(JdbcOutbox outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${idp.outbox.interval-ms:1000}")
    public int relay() {
        int sent = 0;
        for (JdbcOutbox.Pending p : outbox.findPending(100)) {
            try {
                // Topico por eventType (EventTopology): tenant.* y acceso.* en idp.tenant.events; consumo.registrado y
                // legalhold.* en audit.control (control con productores explicitos por eventType).
                String topic = topology.allowedTopicFor(JdbcOutbox.PRODUCER, p.type());
                kafka.send(topic, p.partitionKey(), p.payload()).get(5, TimeUnit.SECONDS);
                outbox.markPublished(p.id());
                sent++;
            } catch (Exception e) {
                LOG.warn("Outbox: fallo al publicar evento {}, se reintenta", p.id());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                break;
            }
        }
        return sent;
    }
}
