package com.idp.notification;

import com.idp.testsupport.Topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.notification.domain.WebhookSubscription;
import com.idp.notification.support.TestReceiver.Reply;
import com.idp.notification.store.WebhookRepository;
import com.idp.security.Roles;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Hallazgos 3 (tope de reintentos manuales), 4, 5 y 6 del informe de auditoria de seguridad. */
@ExtendWith(OutputCaptureExtension.class)
class HardeningIntegrationTest extends AbstractIntegrationTest {

    @Autowired WebhookRepository repository;
    @Autowired MeterRegistry meters;

    private record Setup(String tenant, UUID webhookId) {
    }

    private Setup setup(String path) throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        JsonNode created = createWebhook(tenant, "http://" + HOST + ":" + receiver.port() + path, "extraccion.aprobada");
        return new Setup(tenant, UUID.fromString(created.path("id").asText()));
    }

    private MvcResult createRaw(String tenant, String url) throws Exception {
        return mvc.perform(post("/v1/webhooks").with(token(tenant, admin(tenant))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + url + "\",\"events\":[\"extraccion.aprobada\"]}")).andReturn();
    }

    private String status(String tenant) {
        return (String) deliveries(tenant).get(0).get("status");
    }

    // ---------------------------------------------------------------- hallazgo 6

    @Test
    void urlConQueryStringOCredencialesEmbebidasSeRechazaAlRegistrar() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        String base = "http://" + HOST + ":" + receiver.port();
        for (String bad : new String[] {base + "/h?token=abc", base + "/h?", "http://user:pw@" + HOST + ":"
            + receiver.port() + "/h", "http://user@" + HOST + ":" + receiver.port() + "/h"}) {
            MvcResult r = createRaw(tenant, bad);
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(json(r).path("code").asText()).as(bad).isEqualTo("WEBHOOK_URL_INVALID_URL");
        }
        assertThat(createRaw(tenant, base + "/h").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void elOperadorVeElHostPeroNoLaRutaYElAdminVeLaUrlCompleta() throws Exception {
        Setup s = setup("/hooks/tenant-secreto-123");
        roles.grant(s.tenant(), "operador", Roles.OPERADOR);
        String full = "http://" + HOST + ":" + receiver.port() + "/hooks/tenant-secreto-123";

        JsonNode admin = json(mvc.perform(get("/v1/webhooks").with(token(s.tenant(), "admin"))).andReturn());
        assertThat(admin.get(0).path("url").asText()).isEqualTo(full);

        MvcResult op = mvc.perform(get("/v1/webhooks").with(token(s.tenant(), "operador"))).andReturn();
        String url = json(op).get(0).path("url").asText();
        assertThat(url).isEqualTo("http://" + HOST + ":" + receiver.port() + "/***");
        assertThat(op.getResponse().getContentAsString()).doesNotContain("tenant-secreto-123");
    }

    // ---------------------------------------------------------------- hallazgo 3: tope de reintentos manuales

    @Test
    void elReintentoManualTieneTopePorEntrega() throws Exception {
        Setup s = setup("/webhook");
        receiver.otherwise(r -> Reply.status(400));
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));
        runWorker(s.tenant());
        assertThat(status(s.tenant())).isEqualTo("FALLIDO");
        String deliveryId = deliveries(s.tenant()).get(0).get("id").toString();
        String retryUrl = "/v1/webhooks/" + s.webhookId() + "/deliveries/" + deliveryId + "/retry";

        // maxManualRetries = 3 por defecto.
        for (int i = 1; i <= 3; i++) {
            assertThat(mvc.perform(post(retryUrl).with(token(s.tenant(), "admin"))).andReturn().getResponse()
                .getStatus()).as("reintento " + i).isEqualTo(202);
            runWorker(s.tenant());
            assertThat(status(s.tenant())).isEqualTo("FALLIDO");
        }
        MvcResult fourth = mvc.perform(post(retryUrl).with(token(s.tenant(), "admin"))).andReturn();
        assertThat(fourth.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(fourth).path("code").asText()).isEqualTo("WEBHOOK_RETRY_LIMIT_REACHED");
        assertThat(status(s.tenant())).isEqualTo("FALLIDO");
        assertThat(receiver.count()).isEqualTo(4);
        assertThat(inTenant(s.tenant(), () -> jdbc.queryForObject(
            "select manual_retries from webhook_delivery where id = ?", Integer.class, UUID.fromString(deliveryId))))
            .isEqualTo(3);
    }

    // ---------------------------------------------------------------- hallazgo 5

    @Test
    void siElSecretoNoSeDescifraLaEntregaPasaAFallidoConSecretUnavailableYAlertaSinSecretos(CapturedOutput output)
            throws Exception {
        Setup s = setup("/webhook");
        inTenant(s.tenant(), () -> jdbc.update("update webhook_subscription set secret_current = ? where id = ?",
            "CORRUPTO-NO-ES-UN-SOBRE", s.webhookId()));
        Topics.deliver(listener::onMessage, aprobada(s.tenant(), UUID.randomUUID().toString()));

        assertThat(runWorker(s.tenant())).isEqualTo(1);

        assertThat(receiver.count()).isZero();
        assertThat(status(s.tenant())).isEqualTo("FALLIDO");
        assertThat(deliveries(s.tenant()).get(0).get("attempts")).isEqualTo(1);
        assertThat(deliveries(s.tenant()).get(0).get("error_code")).isEqualTo("SECRET_UNAVAILABLE");
        assertThat(meters.find("webhook.secret.unavailable").tag("tenant", s.tenant()).counter().count()).isEqualTo(1);
        assertThat(meters.find("webhook.delivery.failure").tag("tenant", s.tenant())
            .tag("cause", "secret_unavailable").counter().count()).isEqualTo(1);
        assertThat(output.getAll()).contains("SECURITY_ALERT").contains("secreto no disponible")
            .doesNotContain("CORRUPTO-NO-ES-UN-SOBRE");
        // No reaparece en el siguiente ciclo.
        clock.advance(Duration.ofHours(1));
        assertThat(runWorker(s.tenant())).isZero();
    }

    // ---------------------------------------------------------------- hallazgo 4

    @Test
    void unaEscrituraDeRotacionConEstadoObsoletoNoPisaElSecretoNuevo() throws Exception {
        Setup s = setup("/webhook");
        String tenant = s.tenant();
        UUID id = s.webhookId();
        assertThat(mvc.perform(post("/v1/webhooks/" + id + "/secret/rotate").with(token(tenant, "admin"))).andReturn()
            .getResponse().getStatus()).isEqualTo(200);
        // Instantanea que tendria un endRotation que leyo antes de otra rotacion.
        WebhookSubscription stale = inTenant(tenant, () -> repository.findSubscription(UUID.fromString(tenant), id)
            .orElseThrow());
        assertThat(stale.secretPrevious()).isNotNull();

        // Interleaving: otro endRotation y una nueva rotacion completan antes que el endRotation obsoleto.
        assertThat(mvc.perform(delete("/v1/webhooks/" + id + "/secret/previous").with(token(tenant, "admin")))
            .andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(mvc.perform(post("/v1/webhooks/" + id + "/secret/rotate").with(token(tenant, "admin"))).andReturn()
            .getResponse().getStatus()).isEqualTo(200);
        WebhookSubscription latest = inTenant(tenant, () -> repository.findSubscription(UUID.fromString(tenant), id)
            .orElseThrow());
        assertThat(latest.secretCurrent()).isNotEqualTo(stale.secretCurrent());

        boolean applied = inTenant(tenant, () -> repository.saveRotationIfUnchanged(UUID.fromString(tenant), id,
            stale.secretCurrent(), null, null, stale.rotatedAt(), stale.secretCurrent(), stale.rotatedAt()));

        assertThat(applied).isFalse();
        WebhookSubscription after = inTenant(tenant, () -> repository.findSubscription(UUID.fromString(tenant), id)
            .orElseThrow());
        assertThat(after.secretCurrent()).isEqualTo(latest.secretCurrent());
        assertThat(after.secretPrevious()).isEqualTo(latest.secretPrevious());
    }

    @Test
    void rotacionYFinDeRotacionConcurrentesNuncaRestauranElSecretoViejo() throws Exception {
        Setup s = setup("/webhook");
        String tenant = s.tenant();
        UUID id = s.webhookId();
        for (int round = 0; round < 5; round++) {
            mvc.perform(post("/v1/webhooks/" + id + "/secret/rotate").with(token(tenant, "admin"))).andReturn();
            String current = inTenant(tenant, () -> repository.findSubscription(UUID.fromString(tenant), id)
                .orElseThrow().secretCurrent());
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var end = pool.submit(() -> mvc.perform(delete("/v1/webhooks/" + id + "/secret/previous")
                    .with(token(tenant, "admin"))).andReturn().getResponse().getStatus());
                var rot = pool.submit(() -> mvc.perform(post("/v1/webhooks/" + id + "/secret/rotate")
                    .with(token(tenant, "admin"))).andReturn().getResponse().getStatus());
                assertThat(end.get()).isIn(204, 409);
                assertThat(rot.get()).isIn(200, 409);
            } finally {
                pool.shutdownNow();
            }
            // El secreto vigente final nunca es uno anterior al que habia al iniciar la ronda salvo que se rotara.
            WebhookSubscription after = inTenant(tenant, () -> repository.findSubscription(UUID.fromString(tenant), id)
                .orElseThrow());
            assertThat(after.secretCurrent()).isNotNull();
            if (after.secretPrevious() != null) {
                assertThat(after.secretPrevious()).isEqualTo(current);
            }
            // Cierra cualquier rotacion abierta para la siguiente ronda.
            mvc.perform(delete("/v1/webhooks/" + id + "/secret/previous").with(token(tenant, "admin"))).andReturn();
        }
    }
}
