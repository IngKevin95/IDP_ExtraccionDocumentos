package com.idp.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** SEC-052: topologia de eventos (topico por productor) y validacion de origen. */
class EventTopologyTest {

    private final EventTopology topology = EventTopology.defaults();

    @Test
    void sec052_topicoPorProductor() {
        assertThat(topology.topicFor("revision.completada")).isEqualTo("review.events");
        assertThat(topology.producerOf("revision.completada")).isEqualTo("review-service");
        assertThat(topology.topicFor("extraccion.aprobada")).isEqualTo("document.events");
        assertThat(topology.topicFor("calidad.muestra_ciega_solicitada")).isEqualTo("quality.events");
        assertThat(topology.keyOf("acceso.revocado")).isEqualTo("tenantId");
        assertThat(topology.topicsFor("extraccion.completada", "extraccion.requiere_revision", "revision.completada"))
            .containsExactly("extraction.events", "review.events");
    }

    @Test
    void sec052_servicioNoProductorFallaDeFormaExplicita() {
        assertThat(topology.allowedTopicFor("review-service", "revision.completada")).isEqualTo("review.events");
        assertThatThrownBy(() -> topology.allowedTopicFor("quality-service", "revision.completada"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("no es productor autorizado");
        assertThatThrownBy(() -> topology.topicFor("no.existe")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sec052_soloSenalesYControlAdmitenVariosProductores() {
        for (String type : topology.eventTypes()) {
            if (topology.producersOf(type).size() > 1) {
                assertThat(topology.topicFor(type)).as(type).isIn("audit.signals", "audit.control");
            }
        }
    }

    @Test
    void sec052_auditoriaSenalesSoloTieneSenalesYControlNoSeMezcla() {
        for (String type : topology.eventTypes()) {
            boolean senal = type.startsWith("seguridad.") || type.startsWith("chat.");
            if (topology.topicFor(type).equals("audit.signals")) {
                assertThat(senal).as("%s en audit.signals debe ser una senal", type).isTrue();
            } else {
                assertThat(senal).as("%s es una senal y debe ir en audit.signals", type).isFalse();
            }
        }
        assertThat(topology.topicsFor("legalhold.aplicado", "legalhold.liberado", "consumo.registrado",
            "auditoria.alerta_integridad")).containsExactly("audit.control");
        assertThat(topology.topicsFor("seguridad.acceso_denegado", "chat.respuesta_bloqueada"))
            .containsExactly("audit.signals");
    }

    @Test
    void sec052_todoEventTypeDelCatalogoTieneEntrada() throws IOException {
        Set<String> catalog = new HashSet<>();
        try (Stream<Path> files = Files.list(Path.of("../../contracts/events"))) {
            files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".schema.json"))
                .forEach(n -> catalog.add(n.substring(0, n.indexOf(".v"))));
        }
        assertThat(catalog).isNotEmpty();
        assertThat(topology.eventTypes()).containsAll(catalog);
    }

    @Test
    void sec052_guardAceptaSoloElTopicoAsignado() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EventOriginGuard guard = new EventOriginGuard(topology, registry);

        assertThat(guard.accepts("review.events", "revision.completada")).isTrue();
        assertThat(guard.accepts("document.events", "revision.completada")).isFalse();
        assertThat(guard.accepts("review.events", "tipo.desconocido")).isFalse();
        assertThat(guard.accepts(null, "revision.completada")).isFalse();
        assertThat(registry.counter("idp.events.origin.rejected", "reason", "wrong_topic").count()).isEqualTo(2.0);
        assertThat(registry.counter("idp.events.origin.rejected", "reason", "unknown_type").count()).isEqualTo(1.0);
    }
}
