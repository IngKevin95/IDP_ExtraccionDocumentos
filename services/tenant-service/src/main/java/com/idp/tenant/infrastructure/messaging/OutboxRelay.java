package com.idp.tenant.infrastructure.messaging;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final String topic;

    public OutboxRelay(JdbcOutbox outbox, KafkaTemplate<String, String> kafka,
                       @Value("${idp.outbox.topic:idp.tenant.events}") String topic) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.topic = topic;
    }

    @Scheduled(fixedDelayString = "${idp.outbox.interval-ms:1000}")
    public int relay() {
        int sent = 0;
        for (JdbcOutbox.Pending p : outbox.findPending(100)) {
            try {
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
