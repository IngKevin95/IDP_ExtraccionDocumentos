package com.idp.events;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Topologia de eventos (fuente unica: {@code contracts/events/topology.yaml}, empaquetada en el jar). Un
 * eventType pertenece a un unico topico y solo sus productores declarados pueden escribirlo. Los productores
 * resuelven el topico con {@link #allowedTopicFor}; los consumidores validan el origen con
 * {@link EventOriginGuard} (integridad de origen, SEC-052).
 */
public final class EventTopology {

    public static final String RESOURCE = "contracts/events/topology.yaml";
    private static volatile EventTopology defaults;

    /** Entrada de un eventType. */
    public record Entry(String topic, List<String> producers, String key) {
    }

    private final Map<String, Entry> entries;

    public EventTopology(Map<String, Entry> entries) {
        this.entries = Map.copyOf(entries);
    }

    /** Topologia empaquetada en el classpath (cargada una vez). */
    public static EventTopology defaults() {
        EventTopology t = defaults;
        if (t == null) {
            synchronized (EventTopology.class) {
                if (defaults == null) {
                    try (InputStream in = EventTopology.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                        if (in == null) {
                            throw new IllegalStateException("Falta " + RESOURCE + " en el classpath");
                        }
                        defaults = parse(in);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                t = defaults;
            }
        }
        return t;
    }

    @SuppressWarnings("unchecked")
    public static EventTopology parse(InputStream in) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        Map<String, Object> types = (Map<String, Object>) ((Map<String, Object>) root).get("eventTypes");
        if (types == null || types.isEmpty()) {
            throw new IllegalStateException("topology.yaml sin eventTypes");
        }
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : types.entrySet()) {
            Map<String, Object> v = (Map<String, Object>) e.getValue();
            if (v.get("topic") == null || v.get("producers") == null || v.get("key") == null) {
                throw new IllegalStateException("Entrada incompleta en topology.yaml: " + e.getKey());
            }
            List<String> producers = new ArrayList<>((List<String>) v.get("producers"));
            if (producers.isEmpty()) {
                throw new IllegalStateException("Entrada sin productores en topology.yaml: " + e.getKey());
            }
            out.put(e.getKey(), new Entry((String) v.get("topic"), List.copyOf(producers), (String) v.get("key")));
        }
        return new EventTopology(out);
    }

    public boolean knows(String eventType) {
        return eventType != null && entries.containsKey(eventType);
    }

    public Set<String> eventTypes() {
        return entries.keySet();
    }

    private Entry entry(String eventType) {
        Entry e = eventType == null ? null : entries.get(eventType);
        if (e == null) {
            throw new IllegalStateException("eventType sin entrada en la topologia");
        }
        return e;
    }

    public String topicFor(String eventType) {
        return entry(eventType).topic();
    }

    public String keyOf(String eventType) {
        return entry(eventType).key();
    }

    public List<String> producersOf(String eventType) {
        return entry(eventType).producers();
    }

    /** Productor unico del eventType; falla si tiene varios (solo auditoria.senales y auditoria.control). */
    public String producerOf(String eventType) {
        List<String> p = producersOf(eventType);
        if (p.size() != 1) {
            throw new IllegalStateException("eventType con varios productores: " + eventType);
        }
        return p.get(0);
    }

    public boolean isProducer(String service, String eventType) {
        return knows(eventType) && entries.get(eventType).producers().contains(service);
    }

    /** Topico en el que {@code service} puede publicar el eventType; falla de forma explicita si no es productor. */
    public String allowedTopicFor(String service, String eventType) {
        Entry e = entry(eventType);
        if (!e.producers().contains(service)) {
            throw new IllegalStateException(
                "El servicio " + service + " no es productor autorizado de " + eventType);
        }
        return e.topic();
    }

    /** Topicos distintos (ordenados) de los eventTypes dados; util para {@code @KafkaListener(topics=...)}. */
    public String[] topicsFor(String... eventTypes) {
        Set<String> topics = new TreeSet<>();
        for (String t : eventTypes) {
            topics.add(topicFor(t));
        }
        return topics.toArray(String[]::new);
    }

    /** Todos los topicos de la topologia (consumo del audit-service). */
    public String[] allTopics() {
        Set<String> topics = new TreeSet<>();
        entries.values().forEach(e -> topics.add(e.topic()));
        return topics.toArray(String[]::new);
    }

    /** {@code eventType -> topic} de los eventos producidos por el servicio. */
    public Map<String, String> producedBy(String service) {
        Map<String, String> out = new LinkedHashMap<>();
        entries.forEach((t, e) -> {
            if (e.producers().contains(service)) {
                out.put(t, e.topic());
            }
        });
        return out;
    }
}
