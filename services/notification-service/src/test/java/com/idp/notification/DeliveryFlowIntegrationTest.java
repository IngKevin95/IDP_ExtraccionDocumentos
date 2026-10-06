package com.idp.notification;

import com.idp.testsupport.Topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.kms.InMemoryKeyService;
import com.idp.kms.KeyService;
import com.idp.notification.http.HmacSignatureService;
import com.idp.notification.store.WebhookRepository;
import com.idp.notification.support.TestReceiver;
import com.idp.notification.support.TestReceiver.Reply;
import com.idp.tenant.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Flujo completo evento -> entrega -> HTTP -> outbox con receptor real: AC-02, AC-05, AC-06, AC-07, AC-08, AC-09,
 * AC-12, AC-13, AC-15, AC-16 y AC-17. Un silo H2 por tenant; el reloj es controlable para el backoff.
 */
class DeliveryFlowIntegrationTest extends AbstractIntegrationTest {

    private static final Duration WINDOW = Duration.ofMinutes(5);

    @Autowired HmacSignatureService hmac;
    @Autowired MeterRegistry meters;
    @Autowired KeyService keys;
    @Autowired WebhookRepository repository;

    /** Tenant con politica por defecto (5 intentos, 30s x2) y un webhook suscrito a los eventos dados. */
    private record Setup(String tenant, String webhookId, String secret) {
    }

    private Setup setup(String... events) throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        JsonNode created = createWebhook(tenant, hookUrl(), events);
        return new Setup(tenant, created.path("id").asText(), created.path("secret").asText());
    }

    private Setup setup() throws Exception {
        return setup("extraccion.aprobada");
    }

    private double count(String name, String tenant, String... more) {
        var search = meters.find(name).tag("tenant", tenant);
        for (int i = 0; i + 1 < more.length; i += 2) {
            search = search.tag(more[i], more[i + 1]);
        }
        var c = search.counter();
        return c == null ? 0 : c.count();
    }

    private Instant instantOf(Object ts) {
        return ((java.time.OffsetDateTime) ts).toInstant();
    }

    private String status(String tenant) {
        return (String) deliveries(tenant).get(0).get("status");
    }

    // ---------------------------------------------------------------- AC-02 / AC-05 / AC-08

    @Test
    void ac02_eventoAprobadoSeFirmaSeEnviaComoClaimCheckYPublicaWebhookEntregado() throws Exception {
        Setup s = setup();
        UUID doc = UUID.randomUUID();
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), doc.toString()));
        assertThat(deliveries(s.tenant())).hasSize(1);
        assertThat(status(s.tenant())).isEqualTo("PENDIENTE");

        assertThat(runWorker(s.tenant())).isEqualTo(1);

        assertThat(receiver.received()).hasSize(1);
        TestReceiver.Received r = receiver.received().get(0);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/webhook");
        JsonNode body = JSON.readTree(r.body());
        // Claim-check: solo id de entrega, tipo, instante, documento y estado.
        assertThat(body.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("id", "eventType", "occurredAt", "documentId", "status");
        assertThat(body.path("eventType").asText()).isEqualTo("extraccion.aprobada");
        assertThat(body.path("documentId").asText()).isEqualTo(doc.toString());
        assertThat(body.path("status").asText()).isEqualTo("APROBADA");
        // AC-05: timestamp actual, firma verificable con el secreto mostrado al crear, id de evento estable.
        assertThat(r.header("X-Hub-Timestamp")).isEqualTo(Long.toString(clock.instant().getEpochSecond()));
        assertThat(r.header("X-Hub-Event-Id")).isEqualTo(body.path("id").asText());
        assertThat(hmac.verify(r.header("X-Hub-Signature-256"), List.of(s.secret()),
            Long.parseLong(r.header("X-Hub-Timestamp")), r.body(), clock.instant(), WINDOW)).isTrue();
        assertThat(r.header("Content-Type")).startsWith("application/json");

        assertThat(status(s.tenant())).isEqualTo("ENTREGADO");
        assertThat(deliveries(s.tenant()).get(0).get("attempts")).isEqualTo(1);
        assertThat(deliveries(s.tenant()).get(0).get("last_http_status")).isEqualTo(200);
        List<JsonNode> out = outbox(s.tenant(), "webhook.entregado");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).path("webhookId").asText()).isEqualTo(s.webhookId());
        assertThat(out.get(0).path("documentId").asText()).isEqualTo(doc.toString());
        assertThat(out.get(0).path("tenantId").asText()).isEqualTo(s.tenant());
        assertThat(out.get(0).path("attempts").asInt()).isEqualTo(1);
        assertThat(outbox(s.tenant(), "webhook.fallido")).isEmpty();
        assertThat(count("webhook.delivery.success", s.tenant())).isEqualTo(1);
        // Nada que reintentar despues del exito.
        assertThat(runWorker(s.tenant())).isZero();
    }

    @Test
    void ac08_eventosPublicadosSoloLlevanIdentificadoresYContadoresSinPii() throws Exception {
        Setup s = setup();
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        runWorker(s.tenant());
        receiver.otherwise(r -> Reply.status(400));
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        runWorker(s.tenant());

        JsonNode entregado = outbox(s.tenant(), "webhook.entregado").get(0);
        JsonNode fallido = outbox(s.tenant(), "webhook.fallido").get(0);
        assertThat(entregado.fieldNames()).toIterable().containsExactlyInAnyOrder("eventId", "eventType",
            "schemaVersion", "occurredAt", "tenantId", "correlationId", "webhookId", "documentId", "attempts",
            "latencyMs");
        assertThat(fallido.fieldNames()).toIterable().containsExactlyInAnyOrder("eventId", "eventType",
            "schemaVersion", "occurredAt", "tenantId", "correlationId", "webhookId", "documentId", "reasonCode",
            "attempts");
        // Ni URL del cliente, ni secretos, ni cuerpo del webhook en lo publicado.
        for (JsonNode e : List.of(entregado, fallido)) {
            String text = e.toString();
            assertThat(text).doesNotContain(HOST).doesNotContain(s.secret()).doesNotContain("http");
        }
    }

    @Test
    void ac05_laCabeceraDeTimestampAvanzaConElRelojPeroElIdYElCuerpoSonEstablesEnReintentos() throws Exception {
        Setup s = setup();
        receiver.reply(Reply.status(503), Reply.status(200));
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        runWorker(s.tenant());
        clock.advance(Duration.ofSeconds(30));
        runWorker(s.tenant());

        List<TestReceiver.Received> rs = receiver.received();
        assertThat(rs).hasSize(2);
        assertThat(rs.get(1).header("X-Hub-Timestamp")).isNotEqualTo(rs.get(0).header("X-Hub-Timestamp"));
        assertThat(rs.get(1).header("X-Hub-Event-Id")).isEqualTo(rs.get(0).header("X-Hub-Event-Id"));
        assertThat(rs.get(1).bodyText()).isEqualTo(rs.get(0).bodyText());
        assertThat(rs.get(1).header("X-Hub-Signature-256")).isNotEqualTo(rs.get(0).header("X-Hub-Signature-256"));
    }

    // ---------------------------------------------------------------- AC-06 / AC-16 reintentos

    @Test
    void ac06_cincoRespuestas503ConBackoffExponencialTerminanEnFallidoYPublicanWebhookFallido() throws Exception {
        Setup s = setup();
        receiver.otherwise(r -> Reply.status(503));
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));

        long[] expectedWaits = {30, 60, 120, 240};
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(runWorker(s.tenant())).as("intento %d", attempt).isEqualTo(1);
            assertThat(status(s.tenant())).isEqualTo("PENDIENTE");
            assertThat(deliveries(s.tenant()).get(0).get("attempts")).isEqualTo(attempt);
            Instant next = instantOf(deliveries(s.tenant()).get(0).get("next_attempt_at"));
            assertThat(Duration.between(clock.instant(), next)).isEqualTo(Duration.ofSeconds(expectedWaits[attempt - 1]));
            // Antes de vencer el backoff no se reintenta.
            clock.advance(Duration.ofSeconds(expectedWaits[attempt - 1] - 1));
            assertThat(runWorker(s.tenant())).isZero();
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(runWorker(s.tenant())).isEqualTo(1);

        assertThat(receiver.count()).isEqualTo(5);
        assertThat(status(s.tenant())).isEqualTo("FALLIDO");
        assertThat(deliveries(s.tenant()).get(0).get("attempts")).isEqualTo(5);
        assertThat(deliveries(s.tenant()).get(0).get("error_code")).isEqualTo("HTTP_503");
        List<JsonNode> failed = outbox(s.tenant(), "webhook.fallido");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).path("reasonCode").asText()).isEqualTo("HTTP_ERROR_THRESHOLD_EXCEEDED");
        assertThat(failed.get(0).path("attempts").asInt()).isEqualTo(5);
        assertThat(failed.get(0).path("webhookId").asText()).isEqualTo(s.webhookId());
        assertThat(outbox(s.tenant(), "webhook.entregado")).isEmpty();
        assertThat(count("webhook.delivery.failure", s.tenant(), "cause", "http_error")).isEqualTo(1);
        assertThat(count("webhook.delivery.retry", s.tenant(), "cause", "http_error")).isEqualTo(4);
        // Agotado: ya no se vuelve a intentar sin reintento manual.
        clock.advance(Duration.ofDays(1));
        assertThat(runWorker(s.tenant())).isZero();
        assertThat(receiver.count()).isEqualTo(5);
    }

    @Test
    void ac06_siElReceptorSeRecuperaLaEntregaTerminaEntregadaConElNumeroDeIntentos() throws Exception {
        Setup s = setup();
        receiver.reply(Reply.status(503), Reply.status(500), Reply.status(200));
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        runWorker(s.tenant());
        clock.advance(Duration.ofSeconds(30));
        runWorker(s.tenant());
        clock.advance(Duration.ofSeconds(60));
        runWorker(s.tenant());

        assertThat(status(s.tenant())).isEqualTo("ENTREGADO");
        assertThat(deliveries(s.tenant()).get(0).get("attempts")).isEqualTo(3);
        assertThat(outbox(s.tenant(), "webhook.entregado").get(0).path("attempts").asInt()).isEqualTo(3);
        assertThat(outbox(s.tenant(), "webhook.fallido")).isEmpty();
    }

    @Test
    void ac06_errores429Y408SonReintentablesPeroLos4xxDelReceptorNo() throws Exception {
        Setup s = setup();
        receiver.reply(Reply.status(429), Reply.status(408), Reply.status(200));
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        runWorker(s.tenant());
        clock.advance(Duration.ofSeconds(30));
        runWorker(s.tenant());
        clock.advance(Duration.ofSeconds(60));
        runWorker(s.tenant());
        assertThat(status(s.tenant())).isEqualTo("ENTREGADO");

        for (int code : new int[] {400, 401, 403, 404, 410}) {
            Setup t = setup();
            TestReceiver own = new TestReceiver().reply(Reply.status(code));
            try {
                jdbcSetUrl(t.tenant(), "http://" + HOST + ":" + own.port() + "/webhook");
                Topics.deliver(listener::onMessage, aprobada(t.tenant(), UUID.randomUUID().toString()));
                assertThat(runWorker(t.tenant())).isEqualTo(1);
                assertThat(status(t.tenant())).as("HTTP %d", code).isEqualTo("FALLIDO");
                assertThat(deliveries(t.tenant()).get(0).get("attempts")).isEqualTo(1);
                assertThat(own.count()).isEqualTo(1);
                assertThat(outbox(t.tenant(), "webhook.fallido").get(0).path("reasonCode").asText())
                    .isEqualTo("HTTP_ERROR_THRESHOLD_EXCEEDED");
                clock.advance(Duration.ofHours(2));
                assertThat(runWorker(t.tenant())).isZero();
            } finally {
                own.close();
            }
        }
    }

    @Test
    void ac05_unRedirectNoSeSigueYSeTrataComoFalloDefinitivoSinTocarElDestinoDelRedirect() throws Exception {
        Setup s = setup();
        receiver.reply(Reply.redirect("/otra-ruta"));
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        runWorker(s.tenant());
        assertThat(receiver.received()).extracting(TestReceiver.Received::path).containsExactly("/webhook");
        assertThat(status(s.tenant())).isEqualTo("FALLIDO");
        assertThat(deliveries(s.tenant()).get(0).get("last_http_status")).isEqualTo(302);
    }

    @Test
    void ac06_destinoInalcanzableSeReintentaYAlAgotarseEmiteEndpointUnreachable() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, 2, 30, 2.0, 60, HOST);
        int closedPort;
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = ss.getLocalPort();
        }
        createWebhook(tenant, "http://" + HOST + ":" + closedPort + "/webhook", "extraccion.aprobada");
        Topics.deliver(listener::onMessage, aprobada(tenant, UUID.randomUUID().toString()));
        runWorker(tenant);
        assertThat(status(tenant)).isEqualTo("PENDIENTE");
        clock.advance(Duration.ofSeconds(30));
        runWorker(tenant);
        assertThat(status(tenant)).isEqualTo("FALLIDO");
        assertThat(outbox(tenant, "webhook.fallido").get(0).path("reasonCode").asText()).isEqualTo("ENDPOINT_UNREACHABLE");
        assertThat(count("webhook.delivery.failure", tenant, "cause", "unreachable")).isEqualTo(1);
    }

    @Test
    void ac16_elBackoffYLosIntentosSonConfigurablesPorTenantYTienenTope() throws Exception {
        String a = newTenant();
        allowHosts(a, 3, 30, 3.0, 60, HOST);
        createWebhook(a, hookUrl(), "extraccion.aprobada");
        Setup def = setup();
        receiver.otherwise(r -> Reply.status(503));

        Topics.deliver(listener::onMessage, aprobada(a, UUID.randomUUID().toString()));
        Topics.deliver(listener::onMessage, aprobada(def.tenant(), UUID.randomUUID().toString()));
        runWorker(a);
        runWorker(def.tenant());
        assertThat(Duration.between(clock.instant(), instantOf(deliveries(a).get(0).get("next_attempt_at"))))
            .isEqualTo(Duration.ofSeconds(30));
        assertThat(Duration.between(clock.instant(), instantOf(deliveries(def.tenant()).get(0).get("next_attempt_at"))))
            .isEqualTo(Duration.ofSeconds(30));

        clock.advance(Duration.ofSeconds(30));
        runWorker(a);
        // 30s x 3 = 90s, pero el tope maxBackoff del tenant es 60s.
        assertThat(Duration.between(clock.instant(), instantOf(deliveries(a).get(0).get("next_attempt_at"))))
            .isEqualTo(Duration.ofSeconds(60));
        clock.advance(Duration.ofSeconds(60));
        runWorker(a);
        // maxAttempts=3 del tenant A: tercer fallo es definitivo (el tenant por defecto sigue reintentando).
        assertThat(status(a)).isEqualTo("FALLIDO");
        assertThat(deliveries(a).get(0).get("attempts")).isEqualTo(3);
        assertThat(status(def.tenant())).isEqualTo("PENDIENTE");
    }

    // ---------------------------------------------------------------- filtrado de suscripciones

    @Test
    void ac02_soloRecibenElEventoLasSuscripcionesActivasSuscritasAEseTipo() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        TestReceiver soloRechazos = new TestReceiver();
        TestReceiver baja = new TestReceiver();
        try {
            JsonNode aprobadas = createWebhook(tenant, hookUrl(), "extraccion.aprobada");
            createWebhook(tenant, "http://" + HOST + ":" + soloRechazos.port() + "/r", "documento.rechazado");
            String bajaId = createWebhook(tenant, "http://" + HOST + ":" + baja.port() + "/b", "extraccion.aprobada")
                .path("id").asText();
            mvc.perform(delete("/v1/webhooks/" + bajaId).with(token(tenant, "admin"))).andReturn();

            Topics.deliver(listener::onMessage, aprobada(tenant, UUID.randomUUID().toString()));
            assertThat(deliveries(tenant)).hasSize(1);
            assertThat(deliveries(tenant).get(0).get("webhook_id").toString()).isEqualTo(aprobadas.path("id").asText());
            runWorker(tenant);
            assertThat(receiver.count()).isEqualTo(1);
            assertThat(soloRechazos.count()).isZero();
            assertThat(baja.count()).isZero();
        } finally {
            soloRechazos.close();
            baja.close();
        }
    }

    @Test
    void ac02_variasSuscripcionesRecibenCadaUnaSuPropiaEntregaConSuPropiaFirma() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        TestReceiver second = new TestReceiver();
        try {
            JsonNode w1 = createWebhook(tenant, hookUrl(), "extraccion.aprobada");
            JsonNode w2 = createWebhook(tenant, "http://" + HOST + ":" + second.port() + "/two", "extraccion.aprobada");
            Topics.deliver(listener::onMessage, aprobada(tenant, UUID.randomUUID().toString()));
            assertThat(runWorker(tenant)).isEqualTo(2);
            TestReceiver.Received r1 = receiver.received().get(0);
            TestReceiver.Received r2 = second.received().get(0);
            assertThat(r1.header("X-Hub-Event-Id")).isNotEqualTo(r2.header("X-Hub-Event-Id"));
            long ts1 = Long.parseLong(r1.header("X-Hub-Timestamp"));
            assertThat(hmac.verify(r1.header("X-Hub-Signature-256"), List.of(w1.path("secret").asText()), ts1, r1.body(),
                clock.instant(), WINDOW)).isTrue();
            assertThat(hmac.verify(r1.header("X-Hub-Signature-256"), List.of(w2.path("secret").asText()), ts1, r1.body(),
                clock.instant(), WINDOW)).isFalse();
            assertThat(hmac.verify(r2.header("X-Hub-Signature-256"), List.of(w2.path("secret").asText()),
                Long.parseLong(r2.header("X-Hub-Timestamp")), r2.body(), clock.instant(), WINDOW)).isTrue();
            assertThat(outbox(tenant, "webhook.entregado")).hasSize(2);
        } finally {
            second.close();
        }
    }

    // ---------------------------------------------------------------- AC-13 idempotencia

    @Test
    void ac13_elMismoEventoEntregadoDosVecesNoDuplicaLaEntrega() throws Exception {
        Setup s = setup();
        String event = aprobada(s.tenant(), UUID.randomUUID().toString());
        Topics.deliver(listener::onMessage, event);
        Topics.deliver(listener::onMessage, event);
        assertThat(deliveries(s.tenant())).hasSize(1);
        runWorker(s.tenant());
        Topics.deliver(listener::onMessage, event);
        assertThat(deliveries(s.tenant())).hasSize(1);
        assertThat(receiver.count()).isEqualTo(1);
        assertThat(inTenant(s.tenant(), () -> jdbc.queryForObject("select count(*) from processed_event", Integer.class)))
            .isEqualTo(1);
    }

    @Test
    void ac13_dosEventosDistintosDelMismoDocumentoSonDosEntregas() throws Exception {
        Setup s = setup("extraccion.aprobada", "revision.completada");
        UUID doc = UUID.randomUUID();
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), doc.toString()));
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), doc.toString()));
        assertThat(deliveries(s.tenant())).hasSize(2);
    }

    @Test
    void ac13_eventosFueraDeContratoOAjenosNoGeneranEntregas() throws Exception {
        Setup s = setup();
        Topics.deliver(listener::onMessage, event("documento.recibido", s.tenant(), UUID.randomUUID().toString(), "hashSha256", "x"));
        assertThat(deliveries(s.tenant())).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Topics.deliver(listener::onMessage, "no es json"))
            .isInstanceOf(com.idp.events.EventValidationException.class);
        // approvedBy invalido: viola el schema y va al DLT del consumidor (no reintentable).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Topics.deliver(listener::onMessage, 
            event("extraccion.aprobada", s.tenant(), UUID.randomUUID().toString(), "approvedBy", "NADIE")))
            .isInstanceOf(com.idp.events.EventValidationException.class);
        assertThat(deliveries(s.tenant())).isEmpty();
    }

    // ---------------------------------------------------------------- AC-15 otros eventos

    @Test
    void ac15_revisionCompletadaYDocumentoRechazadoSeNotificanComoClaimCheck() throws Exception {
        Setup s = setup("revision.completada", "documento.rechazado");
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        Topics.deliver(listener::onMessage, event("revision.completada", s.tenant(), d1.toString(), "taskId",
            UUID.randomUUID().toString(), "action", "APROBADO", "reviewerId", "revisor-1"));
        Topics.deliver(listener::onMessage, event("documento.rechazado", s.tenant(), d2.toString(), "reasonCode", "MALWARE_DETECTED"));
        assertThat(runWorker(s.tenant())).isEqualTo(2);

        JsonNode revision = bodyOf("revision.completada");
        JsonNode rechazo = bodyOf("documento.rechazado");
        assertThat(revision.path("eventType").asText()).isEqualTo("revision.completada");
        assertThat(revision.path("status").asText()).isEqualTo("REVISION_APROBADO");
        assertThat(revision.path("documentId").asText()).isEqualTo(d1.toString());
        // Ni reviewerId ni taskId salen hacia el integrador.
        assertThat(revision.toString()).doesNotContain("revisor-1").doesNotContain("taskId");
        assertThat(rechazo.path("status").asText()).isEqualTo("RECHAZADO");
        assertThat(rechazo.path("reasonCode").asText()).isEqualTo("MALWARE_DETECTED");
    }

    @Test
    void ac15_revisionCompletadaCiegaDeCalidadNoSeNotificaAlIntegrador() throws Exception {
        Setup s = setup("revision.completada");
        com.fasterxml.jackson.databind.node.ObjectNode blind = (com.fasterxml.jackson.databind.node.ObjectNode)
            JSON.readTree(event("revision.completada", s.tenant(), UUID.randomUUID().toString(),
                "taskId", UUID.randomUUID().toString(), "action", "APROBADO", "reviewerId", "revisor-1"));
        blind.put("blindSample", true);

        Topics.deliver(listener::onMessage, blind.toString());

        assertThat(deliveries(s.tenant())).isEmpty();
    }

    // ---------------------------------------------------------------- AC-07 aislamiento

    @Test
    void ac07_aprobarUnDocumentoDeAUsaElSiloDeAYNuncaLaConfiguracionDeB() throws Exception {
        Setup a = setup();
        String b = newTenant();
        allowHosts(b, HOST);
        TestReceiver receiverB = new TestReceiver();
        try {
            createWebhook(b, "http://" + HOST + ":" + receiverB.port() + "/b", "extraccion.aprobada");

            Topics.deliver(listener::onMessage, aprobada(a.tenant(), UUID.randomUUID().toString()));
            runWorker(a.tenant());
            runWorker(b);

            assertThat(receiver.count()).isEqualTo(1);
            assertThat(receiverB.count()).isZero();
            assertThat(deliveries(a.tenant())).hasSize(1);
            assertThat(deliveries(b)).isEmpty();
            assertThat(outbox(a.tenant(), "webhook.entregado")).hasSize(1);
            assertThat(outbox(b, "webhook.entregado")).isEmpty();
            // Las firmas de A no validan con el secreto de B (cada tenant tiene su secreto y su KEK de silo).
            String secretB = inTenant(b, () -> jdbc.queryForObject("select secret_current from webhook_subscription",
                String.class));
            assertThat(secretB).isNotBlank();
        } finally {
            receiverB.close();
        }
    }

    @Test
    void ac07_elOutboxYLaIdempotenciaSeGuardanEnElSiloDelTenantDelEvento() throws Exception {
        Setup a = setup();
        Setup b = setup();
        Topics.deliver(listener::onMessage, aprobada(a.tenant(), UUID.randomUUID().toString()));
        assertThat(inTenant(a.tenant(), () -> jdbc.queryForObject("select count(*) from processed_event", Integer.class)))
            .isEqualTo(1);
        assertThat(inTenant(b.tenant(), () -> jdbc.queryForObject("select count(*) from processed_event", Integer.class)))
            .isZero();
    }

    // ---------------------------------------------------------------- AC-09 rotacion

    @Test
    void ac09_duranteLaRotacionCadaEnvioLlevaDosFirmasYAlCerrarlaSoloLaDelSecretoNuevo() throws Exception {
        Setup s = setup();
        String fresh = json(mvc.perform(post("/v1/webhooks/" + s.webhookId() + "/secret/rotate")
            .with(token(s.tenant(), "admin"))).andReturn()).path("secret").asText();

        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        runWorker(s.tenant());
        TestReceiver.Received during = receiver.received().get(0);
        long ts = Long.parseLong(during.header("X-Hub-Timestamp"));
        assertThat(during.header("X-Hub-Signature-256").split(",")).hasSize(2);
        // Un receptor aun con el secreto original y otro ya migrado al nuevo validan el mismo envio.
        assertThat(hmac.verify(during.header("X-Hub-Signature-256"), List.of(s.secret()), ts, during.body(),
            clock.instant(), WINDOW)).isTrue();
        assertThat(hmac.verify(during.header("X-Hub-Signature-256"), List.of(fresh), ts, during.body(),
            clock.instant(), WINDOW)).isTrue();

        mvc.perform(delete("/v1/webhooks/" + s.webhookId() + "/secret/previous").with(token(s.tenant(), "admin")))
            .andReturn();
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        runWorker(s.tenant());
        TestReceiver.Received after = receiver.received().get(1);
        long ts2 = Long.parseLong(after.header("X-Hub-Timestamp"));
        assertThat(after.header("X-Hub-Signature-256").split(",")).hasSize(1);
        assertThat(hmac.verify(after.header("X-Hub-Signature-256"), List.of(fresh), ts2, after.body(), clock.instant(),
            WINDOW)).isTrue();
        assertThat(hmac.verify(after.header("X-Hub-Signature-256"), List.of(s.secret()), ts2, after.body(),
            clock.instant(), WINDOW)).isFalse();
    }

    @Test
    void ac09_vencidaLaVentanaDeSolapamientoElSecretoAnteriorDejaDeFirmar() throws Exception {
        Setup s = setup();
        String fresh = json(mvc.perform(post("/v1/webhooks/" + s.webhookId() + "/secret/rotate")
            .with(token(s.tenant(), "admin"))).andReturn()).path("secret").asText();
        clock.advance(Duration.ofDays(8));
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        runWorker(s.tenant());
        TestReceiver.Received r = receiver.received().get(0);
        assertThat(r.header("X-Hub-Signature-256").split(",")).hasSize(1);
        assertThat(hmac.verify(r.header("X-Hub-Signature-256"), List.of(fresh), Long.parseLong(r.header("X-Hub-Timestamp")),
            r.body(), clock.instant(), WINDOW)).isTrue();
    }

    @Test
    void ac09_unaEntregaPendienteUsaLosSecretosVigentesAlMomentoDelIntento() throws Exception {
        Setup s = setup();
        receiver.reply(Reply.status(503), Reply.status(200));
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        runWorker(s.tenant());
        String fresh = json(mvc.perform(post("/v1/webhooks/" + s.webhookId() + "/secret/rotate")
            .with(token(s.tenant(), "admin"))).andReturn()).path("secret").asText();
        clock.advance(Duration.ofSeconds(30));
        runWorker(s.tenant());
        TestReceiver.Received retry = receiver.received().get(1);
        assertThat(retry.header("X-Hub-Signature-256").split(",")).hasSize(2);
        assertThat(hmac.verify(retry.header("X-Hub-Signature-256"), List.of(fresh), Long.parseLong(
            retry.header("X-Hub-Timestamp")), retry.body(), clock.instant(), WINDOW)).isTrue();
    }

    // ---------------------------------------------------------------- AC-12 historial, DLT y reintento manual

    @Test
    void ac12_lasEntregasFallidasQuedanEnDltYSePuedenReintentarAManoConHistorial() throws Exception {
        Setup s = setup();
        receiver.reply(Reply.status(400));
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        runWorker(s.tenant());
        assertThat(status(s.tenant())).isEqualTo("FALLIDO");
        String deliveryId = deliveries(s.tenant()).get(0).get("id").toString();

        JsonNode history = json(mvc.perform(get("/v1/webhooks/" + s.webhookId() + "/deliveries?status=FALLIDO")
            .with(token(s.tenant(), "admin"))).andReturn());
        assertThat(history.path("total").asInt()).isEqualTo(1);
        JsonNode item = history.path("data").get(0);
        assertThat(item.path("id").asText()).isEqualTo(deliveryId);
        assertThat(item.path("status").asText()).isEqualTo("FALLIDO");
        assertThat(item.path("attempts").asInt()).isEqualTo(1);
        assertThat(item.path("lastHttpStatus").asInt()).isEqualTo(400);
        assertThat(item.path("errorCode").asText()).isEqualTo("HTTP_400");
        assertThat(item.has("payload")).isFalse();

        MvcResult retry = mvc.perform(post("/v1/webhooks/" + s.webhookId() + "/deliveries/" + deliveryId + "/retry")
            .with(token(s.tenant(), "admin"))).andReturn();
        assertThat(retry.getResponse().getStatus()).isEqualTo(202);
        assertThat(json(retry).path("status").asText()).isEqualTo("PENDIENTE");
        assertThat(json(retry).path("attempts").asInt()).isZero();
        // Solo FALLIDO se reintenta: ahora esta PENDIENTE.
        assertThat(mvc.perform(post("/v1/webhooks/" + s.webhookId() + "/deliveries/" + deliveryId + "/retry")
            .with(token(s.tenant(), "admin"))).andReturn().getResponse().getStatus()).isEqualTo(409);

        assertThat(runWorker(s.tenant())).isEqualTo(1);
        assertThat(status(s.tenant())).isEqualTo("ENTREGADO");
        assertThat(receiver.count()).isEqualTo(2);
        // Mismo id de evento hacia el integrador: puede deduplicar el reintento manual.
        assertThat(receiver.received().get(1).header("X-Hub-Event-Id"))
            .isEqualTo(receiver.received().get(0).header("X-Hub-Event-Id"));
        assertThat(outbox(s.tenant(), "webhook.fallido")).hasSize(1);
        assertThat(outbox(s.tenant(), "webhook.entregado")).hasSize(1);
        assertThat(mvc.perform(post("/v1/webhooks/" + s.webhookId() + "/deliveries/" + deliveryId + "/retry")
            .with(token(s.tenant(), "admin"))).andReturn().getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void ac12_historialPaginadoYFiltradoPorEstadoSoloDelTenantYWebhook() throws Exception {
        Setup s = setup();
        for (int i = 0; i < 3; i++) {
            Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        }
        runWorker(s.tenant());
        receiver.otherwise(r -> Reply.status(400));
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        runWorker(s.tenant());

        JsonNode all = json(mvc.perform(get("/v1/webhooks/" + s.webhookId() + "/deliveries?limit=3")
            .with(token(s.tenant(), "admin"))).andReturn());
        assertThat(all.path("total").asInt()).isEqualTo(4);
        assertThat(all.path("data")).hasSize(3);
        assertThat(all.path("limit").asInt()).isEqualTo(3);
        JsonNode second = json(mvc.perform(get("/v1/webhooks/" + s.webhookId() + "/deliveries?limit=3&offset=3")
            .with(token(s.tenant(), "admin"))).andReturn());
        assertThat(second.path("data")).hasSize(1);
        JsonNode delivered = json(mvc.perform(get("/v1/webhooks/" + s.webhookId() + "/deliveries?status=ENTREGADO")
            .with(token(s.tenant(), "admin"))).andReturn());
        assertThat(delivered.path("total").asInt()).isEqualTo(3);
        assertThat(mvc.perform(get("/v1/webhooks/" + s.webhookId() + "/deliveries?status=XX")
            .with(token(s.tenant(), "admin"))).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get("/v1/webhooks/" + s.webhookId() + "/deliveries?limit=1000")
            .with(token(s.tenant(), "admin"))).andReturn().getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void ac12_noSePuedeReintentarUnaEntregaDeOtroWebhookNiDeOtroTenant() throws Exception {
        Setup a = setup();
        Setup other = setup();
        receiver.reply(Reply.status(400));
        Topics.deliver(listener::onMessage, aprobada(a.tenant(), UUID.randomUUID().toString()));
        runWorker(a.tenant());
        String deliveryId = deliveries(a.tenant()).get(0).get("id").toString();
        // Otro tenant (con su propio webhook) no la ve ni la reintenta.
        assertThat(mvc.perform(post("/v1/webhooks/" + a.webhookId() + "/deliveries/" + deliveryId + "/retry")
            .with(token(other.tenant(), "admin"))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/v1/webhooks/" + other.webhookId() + "/deliveries/" + deliveryId + "/retry")
            .with(token(other.tenant(), "admin"))).andReturn().getResponse().getStatus()).isEqualTo(404);
        // Mismo tenant, webhook equivocado.
        String second = createWebhook(a.tenant(), hookUrl(), "extraccion.aprobada").path("id").asText();
        assertThat(mvc.perform(post("/v1/webhooks/" + second + "/deliveries/" + deliveryId + "/retry")
            .with(token(a.tenant(), "admin"))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(status(a.tenant())).isEqualTo("FALLIDO");
    }

    // ---------------------------------------------------------------- robustez del despachador

    @Test
    void entregaPendienteDeUnWebhookDadoDeBajaSeCancelaSinEnviar() throws Exception {
        Setup s = setup();
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        mvc.perform(delete("/v1/webhooks/" + s.webhookId()).with(token(s.tenant(), "admin"))).andReturn();
        runWorker(s.tenant());
        assertThat(status(s.tenant())).isEqualTo("CANCELADO");
        assertThat(receiver.count()).isZero();
        assertThat(outbox(s.tenant(), "webhook.fallido")).isEmpty();
    }

    @Test
    void elArrendamientoEvitaDobleEnvioYSeLiberaSiElProcesoMuere() throws Exception {
        Setup s = setup();
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        // Otra instancia reclamo la entrega y murio antes de enviar.
        var claimed = inTenant(s.tenant(), () -> new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()))
            .execute(st -> repository.claimDue(UUID.fromString(s.tenant()), clock.instant(), Duration.ofMinutes(5), 10)));
        assertThat(claimed).hasSize(1);
        assertThat(runWorker(s.tenant())).isZero();
        clock.advance(Duration.ofMinutes(4));
        assertThat(runWorker(s.tenant())).isZero();
        clock.advance(Duration.ofMinutes(2));
        assertThat(runWorker(s.tenant())).isEqualTo(1);
        assertThat(status(s.tenant())).isEqualTo("ENTREGADO");
        assertThat(receiver.count()).isEqualTo(1);
    }

    @Test
    void siLaKekDelTenantFueDestruidaNoSeEnviaNadaYLaEntregaPasaAFallidoConSecretUnavailable() throws Exception {
        Setup s = setup();
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        ((InMemoryKeyService) keys).disableKek(new TenantId(s.tenant()), "webhooks");
        assertThat(runWorker(s.tenant())).isEqualTo(1);
        assertThat(receiver.count()).isZero();
        assertThat(status(s.tenant())).isEqualTo("FALLIDO");
        assertThat(deliveries(s.tenant()).get(0).get("error_code")).isEqualTo("SECRET_UNAVAILABLE");
        assertThat(deliveries(s.tenant()).get(0).get("attempts")).isEqualTo(1);
        assertThat(outbox(s.tenant(), "webhook.fallido")).hasSize(1);
        assertThat(outbox(s.tenant(), "webhook.fallido").get(0).path("reasonCode").asText())
            .isEqualTo("SECRET_UNAVAILABLE");
        // No reaparece en el ciclo siguiente.
        clock.advance(Duration.ofMinutes(10));
        assertThat(runWorker(s.tenant())).isZero();
    }

    @Test
    void elLoteLimitaCuantasEntregasSeReclamanPorPasada() throws Exception {
        Setup s = setup();
        for (int i = 0; i < 12; i++) {
            Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        }
        assertThat(runWorker(s.tenant())).isEqualTo(10);
        assertThat(runWorker(s.tenant())).isEqualTo(2);
        assertThat(runWorker(s.tenant())).isZero();
        assertThat(receiver.count()).isEqualTo(12);
        Set<String> ids = new java.util.HashSet<>();
        List<String> seen = new ArrayList<>();
        receiver.received().forEach(r -> {
            ids.add(r.header("X-Hub-Event-Id"));
            seen.add(r.header("X-Hub-Event-Id"));
        });
        assertThat(ids).hasSameSizeAs(seen);
    }

    @Test
    void cambiosDePoliticaAplicanAEntregasPendientes() throws Exception {
        Setup s = setup();
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        // El tenant retira el host de la allowlist antes del envio: la entrega se bloquea al despachar.
        allowHosts(s.tenant(), "otro.banco.test");
        runWorker(s.tenant());
        assertThat(receiver.count()).isZero();
        assertThat(status(s.tenant())).isEqualTo("FALLIDO");
        assertThat(deliveries(s.tenant()).get(0).get("error_code")).isEqualTo("HOST_NOT_ALLOWED");
        assertThat(outbox(s.tenant(), "webhook.fallido").get(0).path("reasonCode").asText()).isEqualTo("SSRF_BLOCKED");
    }

    /** Cuerpo recibido de un tipo de evento (el orden de reclamo dentro de un lote no es contractual). */
    private JsonNode bodyOf(String eventType) throws Exception {
        for (TestReceiver.Received r : receiver.received()) {
            JsonNode n = JSON.readTree(r.body());
            if (eventType.equals(n.path("eventType").asText())) {
                return n;
            }
        }
        throw new AssertionError("no se recibio " + eventType);
    }

    private void jdbcSetUrl(String tenant, String url) {
        inTenant(tenant, () -> jdbc.update("update webhook_subscription set url = ?", url));
    }

    @SuppressWarnings("unused")
    private Map<String, Object> first(String tenant) {
        return deliveries(tenant).get(0);
    }
}
