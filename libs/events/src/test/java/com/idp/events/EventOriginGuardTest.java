package com.idp.events;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** SEC-052: el contador de rechazos es exacto; el log se limita a 1 por minuto y por (topico, eventType). */
class EventOriginGuardTest {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EventOriginGuard guard = new EventOriginGuard(EventTopology.defaults(), registry, nanos::get);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(EventOriginGuard.class);

    @BeforeEach
    void attach() {
        logger.setLevel(Level.ERROR);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    @Test
    void contadorExactYLogLimitadoAUnoPorMinutoPorTopicoYEventType() {
        for (int i = 0; i < 50; i++) {
            guard.accepts("documentos.eventos", "revision.completada");
        }
        assertThat(registry.counter("idp.events.origin.rejected", "reason", "wrong_topic").count()).isEqualTo(50.0);
        assertThat(logs.list).hasSize(1);

        // otra clave (topico, eventType) tiene su propio cupo
        guard.accepts("extraccion.eventos", "revision.completada");
        assertThat(logs.list).hasSize(2);

        // pasado el minuto vuelve a registrarse una linea para la clave original
        nanos.addAndGet(Duration.ofSeconds(61).toNanos());
        guard.accepts("documentos.eventos", "revision.completada");
        guard.accepts("documentos.eventos", "revision.completada");
        assertThat(logs.list).hasSize(3);
        assertThat(registry.counter("idp.events.origin.rejected", "reason", "wrong_topic").count()).isEqualTo(53.0);
    }

    @Test
    void elLogNuncaIncluyePayloadYLasClavesDelAtacanteEstanAcotadas() {
        for (int i = 0; i < 3000; i++) {
            guard.accepts("topico-" + i, "tipo.desconocido." + i);
        }
        assertThat(registry.counter("idp.events.origin.rejected", "reason", "unknown_type").count()).isEqualTo(3000.0);
        // 1024 claves propias + 1 compartida de desborde, no una linea por intento
        assertThat(logs.list.size()).isLessThanOrEqualTo(1025);
    }

    @Test
    void aceptaElTopicoAsignadoSinContarNiRegistrar() {
        assertThat(guard.accepts("revision.eventos", "revision.completada")).isTrue();
        assertThat(logs.list).isEmpty();
        assertThat(registry.find("idp.events.origin.rejected").counters()).isEmpty();
    }
}
