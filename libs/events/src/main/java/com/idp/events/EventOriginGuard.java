package com.idp.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Valida que un evento llego por el topico que {@link EventTopology} asigna a su eventType (integridad de
 * origen, SEC-052): con ACL de Write exclusivas por topico, un servicio comprometido no puede suplantar a otro
 * productor. Si no coincide el evento se ignora, se cuenta {@code idp.events.origin.rejected} y se registra una
 * alerta SECURITY sin payload.
 */
public final class EventOriginGuard {

    public static final Marker SECURITY = MarkerFactory.getMarker("SECURITY");
    private static final Logger LOG = LoggerFactory.getLogger(EventOriginGuard.class);
    private static final Pattern SAFE = Pattern.compile("[a-zA-Z0-9_.\\-]{1,80}");

    private final EventTopology topology;
    private final MeterRegistry registry;

    public EventOriginGuard(EventTopology topology, MeterRegistry registry) {
        this.topology = topology;
        this.registry = registry;
    }

    /** Guard con la topologia empaquetada y sin metricas (listeners de libs y tests). */
    public static EventOriginGuard standalone() {
        return new EventOriginGuard(EventTopology.defaults(), null);
    }

    /** @return {@code true} si el evento llego por su topico; {@code false} si debe ignorarse. */
    public boolean accepts(String topic, String eventType) {
        boolean known = topology.knows(eventType);
        if (known && topic != null && topology.topicFor(eventType).equals(topic)) {
            return true;
        }
        String reason = known ? "wrong_topic" : "unknown_type";
        if (registry != null) {
            registry.counter("idp.events.origin.rejected", "reason", reason).increment();
        }
        LOG.error(SECURITY, "Evento ignorado por origen invalido: eventType={} topic={} reason={}",
            safe(eventType), safe(topic), reason);
        return false;
    }

    private static String safe(String value) {
        return value != null && SAFE.matcher(value).matches() ? value : "?";
    }
}
