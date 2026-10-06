package com.idp.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.idp.events.EventOriginGuard;
import com.idp.events.EventSerde;
import com.idp.tenant.context.TenantDataSourceRouter;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;

/**
 * Desaloja (con drenado) el pool del silo de un tenant al llegar {@code tenant.baja_iniciada} o una rotacion de
 * credenciales (H7). Cada instancia usa su propio consumer group para que todas las replicas lo reciban.
 */
public final class TenantPoolEvictionKafkaListener {

    private static final Logger LOG = LoggerFactory.getLogger(TenantPoolEvictionKafkaListener.class);
    static final Set<String> EVICTING = Set.of("tenant.baja_iniciada", "tenant.credenciales_rotadas");

    private final TenantDataSourceRouter router;
    private final EventSerde serde;
    private final EventOriginGuard guard;

    public TenantPoolEvictionKafkaListener(TenantDataSourceRouter router, EventSerde serde,
                                           EventOriginGuard guard) {
        this.router = router;
        this.serde = serde;
        this.guard = guard;
    }

    @KafkaListener(topics = "${idp.security.revocation-topic:#{T(com.idp.events.EventTopology).defaults().topicFor('tenant.baja_iniciada')}}",
            groupId = "${spring.application.name:svc}-pool-evict-${random.uuid}",
            properties = {"auto.offset.reset=latest"})
    public void onMessage(String json, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        try {
            JsonNode node = serde.mapper().readTree(json);
            String type = node.path("eventType").asText("");
            if (EVICTING.contains(type) && guard.accepts(topic, type)) {
                String tenantId = node.path("tenantId").asText("");
                if (!tenantId.isEmpty()) {
                    router.evict(tenantId);
                    LOG.info("Pool del tenant desalojado por {}", node.path("eventType").asText());
                }
            }
        } catch (JsonProcessingException | RuntimeException e) {
            LOG.warn("Evento de desalojo ignorado: {}", e.getClass().getSimpleName());
        }
    }
}
