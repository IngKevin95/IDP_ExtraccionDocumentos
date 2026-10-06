package com.idp.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.idp.events.EventOriginGuard;
import com.idp.events.EventSerde;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;

/**
 * Consume {@code acceso.revocado} y purga la cache de roles del servicio. Cada instancia usa un consumer group
 * propio (uuid) para que TODAS las replicas reciban el evento; solo importa lo nuevo (offset latest).
 * Registrar como bean en cada servicio con API que use {@link CachingRoleAssignmentVerifier}.
 */
public final class AccesoRevocadoKafkaListener {

    private static final Logger LOG = LoggerFactory.getLogger(AccesoRevocadoKafkaListener.class);

    private final AccesoRevocadoHandler handler;
    private final EventSerde serde;
    private final EventOriginGuard guard;

    public AccesoRevocadoKafkaListener(CachingRoleAssignmentVerifier verifier, EventSerde serde) {
        this(verifier, serde, EventOriginGuard.standalone());
    }

    public AccesoRevocadoKafkaListener(CachingRoleAssignmentVerifier verifier, EventSerde serde,
                                       EventOriginGuard guard) {
        this.handler = new AccesoRevocadoHandler(verifier);
        this.serde = serde;
        this.guard = guard;
    }

    @KafkaListener(topics = "${idp.security.revocation-topic:#{T(com.idp.events.EventTopology).defaults().topicFor('acceso.revocado')}}",
            groupId = "${spring.application.name:svc}-acceso-revocado-${random.uuid}",
            properties = {"auto.offset.reset=latest"})
    public void onMessage(String json, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        try {
            if (AccesoRevocadoHandler.EVENT_TYPE.equals(serde.mapper().readTree(json).path("eventType").asText(""))
                && guard.accepts(topic, AccesoRevocadoHandler.EVENT_TYPE)) {
                handler.accept(serde.fromJson(json));
            }
        } catch (JsonProcessingException | RuntimeException e) {
            LOG.warn("Evento de revocacion ignorado: {}", e.getClass().getSimpleName());
        }
    }
}
