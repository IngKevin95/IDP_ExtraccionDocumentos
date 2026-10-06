package com.idp.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.notification.support.TestReceiver.Reply;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Hallazgo 3: circuit breaker por host; abierto, pospone sin gastar intentos y protege tambien a otros tenants. */
@TestPropertySource(properties = {"idp.notification.breaker.minimum-calls=2",
    "idp.notification.breaker.sliding-window-size=2", "idp.notification.breaker.open-duration=1h"})
class CircuitBreakerIntegrationTest extends AbstractIntegrationTest {

    private static final String CB_HOST = "caido.banco.test";

    @Autowired MeterRegistry meters;

    private String tenantFor(String host) throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, host);
        createWebhook(tenant, "http://" + host + ":" + receiver.port() + "/webhook", "extraccion.aprobada");
        return tenant;
    }

    private static Instant at(Object ts) {
        return ((java.time.OffsetDateTime) ts).toInstant();
    }

    @Test
    void ante5xxRepetidosElHostAbreYLasEntregasSePosponenSinContarIntentoParaTodosLosTenants() throws Exception {
        dns.map(CB_HOST, "127.0.0.1");
        receiver.otherwise(r -> Reply.status(503));
        String tenant = tenantFor(CB_HOST);
        for (int i = 0; i < 3; i++) {
            listener.onMessage(aprobada(tenant, UUID.randomUUID().toString()));
        }

        assertThat(runWorker(tenant)).isEqualTo(3);

        // Dos envios reales fallaron (abren el breaker); el tercero ni se intento.
        assertThat(receiver.count()).isEqualTo(2);
        List<Map<String, Object>> rows = deliveries(tenant);
        assertThat(rows).filteredOn(r -> ((Integer) r.get("attempts")) == 1).hasSize(2);
        Map<String, Object> deferred = rows.stream().filter(r -> ((Integer) r.get("attempts")) == 0).findFirst()
            .orElseThrow();
        assertThat(deferred.get("status")).isEqualTo("PENDIENTE");
        assertThat(Duration.between(clock.instant(), at(deferred.get("next_attempt_at"))))
            .isGreaterThanOrEqualTo(Duration.ofMinutes(59));
        assertThat(meters.find("webhook.delivery.deferred").tag("tenant", tenant).tag("cause", "circuit_open")
            .counter().count()).isEqualTo(1);

        // Otro tenant hacia el mismo host tampoco le pega mientras el breaker esta abierto.
        String other = tenantFor(CB_HOST);
        listener.onMessage(aprobada(other, UUID.randomUUID().toString()));
        assertThat(runWorker(other)).isEqualTo(1);
        assertThat(receiver.count()).isEqualTo(2);
        assertThat(deliveries(other).get(0).get("attempts")).isEqualTo(0);
        assertThat(deliveries(other).get(0).get("status")).isEqualTo("PENDIENTE");
    }

    @Test
    void unHostSanoNoSeVeAfectadoPorElBreakerDeOtro() throws Exception {
        dns.map("sano.banco.test", "127.0.0.1");
        String tenant = tenantFor("sano.banco.test");
        listener.onMessage(aprobada(tenant, UUID.randomUUID().toString()));
        assertThat(runWorker(tenant)).isEqualTo(1);
        assertThat(deliveries(tenant).get(0).get("status")).isEqualTo("ENTREGADO");
    }
}
