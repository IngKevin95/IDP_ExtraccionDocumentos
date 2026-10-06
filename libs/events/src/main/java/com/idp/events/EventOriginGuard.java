package com.idp.events;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Valida que un evento llego por el topico que {@link EventTopology} asigna a su eventType (integridad de
 * origen, SEC-052): con ACL de Write exclusivas por topico, un servicio comprometido no puede suplantar a otro
 * productor. Si no coincide el evento se ignora, se cuenta {@code idp.events.origin.rejected} y se registra una
 * alerta SECURITY sin payload. El contador es exacto; el log se limita a 1 por minuto y por (topico, eventType)
 * para que un productor comprometido no inunde los logs (la alerta de Prometheus usa el contador).
 */
public final class EventOriginGuard {

    public static final Marker SECURITY = MarkerFactory.getMarker("SECURITY");
    private static final Logger LOG = LoggerFactory.getLogger(EventOriginGuard.class);
    private static final Pattern SAFE = Pattern.compile("[a-zA-Z0-9_.\\-]{1,80}");

    static final Duration LOG_INTERVAL = Duration.ofMinutes(1);
    /** Tope de claves (topico, eventType) distintas: el espacio lo controla el atacante. */
    private static final int MAX_LOG_KEYS = 1024;

    private final EventTopology topology;
    private final MeterRegistry registry;
    private final LongSupplier nanoClock;
    private final Map<String, Long> lastLogged = new ConcurrentHashMap<>();

    public EventOriginGuard(EventTopology topology, MeterRegistry registry) {
        this(topology, registry, System::nanoTime);
    }

    EventOriginGuard(EventTopology topology, MeterRegistry registry, LongSupplier nanoClock) {
        this.topology = topology;
        this.registry = registry;
        this.nanoClock = nanoClock;
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
        if (shouldLog(safe(topic) + "|" + safe(eventType))) {
            LOG.error(SECURITY, "Evento ignorado por origen invalido: eventType={} topic={} reason={}",
                safe(eventType), safe(topic), reason);
        }
        return false;
    }

    /** {@code true} como maximo una vez por minuto y clave; el contador no pasa por aqui. */
    private boolean shouldLog(String key) {
        String k = !lastLogged.containsKey(key) && lastLogged.size() >= MAX_LOG_KEYS ? "*overflow*" : key;
        long now = nanoClock.getAsLong();
        boolean[] log = {false};
        lastLogged.compute(k, (key2, last) -> {
            if (last == null || now - last >= LOG_INTERVAL.toNanos()) {
                log[0] = true;
                return now;
            }
            return last;
        });
        return log[0];
    }

    private static String safe(String value) {
        return value != null && SAFE.matcher(value).matches() ? value : "?";
    }
}
