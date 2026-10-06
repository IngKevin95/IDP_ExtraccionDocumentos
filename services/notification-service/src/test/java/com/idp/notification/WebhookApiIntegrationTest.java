package com.idp.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.notification.service.WebhookSecrets;
import com.idp.security.Roles;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** API de gestion (contracts/openapi/notification-service.yaml): AC-01, AC-07, AC-09, AC-10 y AC-11. */
class WebhookApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired WebhookSecrets secrets;

    private MvcResult call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        return mvc.perform(req).andReturn();
    }

    @Test
    void ac01_altaDevuelve201ConSecretoGeneradoYSuscripcionActiva() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        MvcResult r = call(post("/v1/webhooks").with(token(tenant, admin(tenant))).contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + hookUrl() + "\",\"events\":[\"extraccion.aprobada\",\"documento.rechazado\"]}"));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        JsonNode body = json(r);
        assertThat(body.path("id").asText()).isNotBlank();
        assertThat(body.path("url").asText()).isEqualTo(hookUrl());
        assertThat(body.path("events")).extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("extraccion.aprobada", "documento.rechazado");
        // 256 bits en base64url sin relleno = 43 caracteres.
        assertThat(body.path("secret").asText()).matches("[A-Za-z0-9_-]{43}");
        assertThat(body.path("createdAt").asText()).isNotBlank();
        assertThat(inTenant(tenant, () -> jdbc.queryForObject("select active from webhook_subscription where id = ?",
            Boolean.class, UUID.fromString(body.path("id").asText())))).isTrue();
    }

    @Test
    void ac01_dosAltasGeneranSecretosDistintos() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        String s1 = createWebhook(tenant, hookUrl(), "extraccion.aprobada").path("secret").asText();
        String s2 = createWebhook(tenant, hookUrl(), "extraccion.aprobada").path("secret").asText();
        assertThat(s1).isNotEqualTo(s2);
    }

    @Test
    void ac11_secretoSeMuestraUnaSolaVezYSeGuardaCifradoEnSobre() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        JsonNode created = createWebhook(tenant, hookUrl(), "extraccion.aprobada");
        String secret = created.path("secret").asText();
        UUID id = UUID.fromString(created.path("id").asText());

        String stored = inTenant(tenant, () -> jdbc.queryForObject(
            "select secret_current from webhook_subscription where id = ?", String.class, id));
        assertThat(stored).isNotEqualTo(secret).doesNotContain(secret);
        // Descifra solo con el tenant y el webhook correctos (el AAD liga el sobre).
        assertThat(inTenant(tenant, () -> secrets.open(UUID.fromString(tenant), id, stored))).isEqualTo(secret);
        assertThatThrownBy(() -> secrets.open(UUID.fromString(tenant), UUID.randomUUID(), stored))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> secrets.open(UUID.randomUUID(), id, stored)).isInstanceOf(RuntimeException.class);

        // Ninguna lectura posterior expone el secreto.
        MvcResult list = call(get("/v1/webhooks").with(token(tenant, "admin")));
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(list.getResponse().getContentAsString()).doesNotContain(secret).doesNotContain("secret");
    }

    @Test
    void ac10_hostFueraDeLaAllowlistOSinPoliticaSeRechazaConCodigoEstable() throws Exception {
        String tenant = newTenant();
        String body = "{\"url\":\"" + hookUrl() + "\",\"events\":[\"extraccion.aprobada\"]}";
        MvcResult sinPolitica = call(post("/v1/webhooks").with(token(tenant, admin(tenant)))
            .contentType(MediaType.APPLICATION_JSON).content(body));
        assertThat(sinPolitica.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(sinPolitica).path("code").asText()).isEqualTo("WEBHOOK_URL_HOST_NOT_ALLOWED");

        allowHosts(tenant, "otro.banco.test");
        MvcResult fuera = call(post("/v1/webhooks").with(token(tenant, "admin")).contentType(MediaType.APPLICATION_JSON)
            .content(body));
        assertThat(fuera.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(fuera).path("code").asText()).isEqualTo("WEBHOOK_URL_HOST_NOT_ALLOWED");
        assertThat(inTenant(tenant, () -> jdbc.queryForObject("select count(*) from webhook_subscription",
            Integer.class))).isZero();
    }

    @Test
    void ac04_altaConUrlDeMetadataCloudOLiteralPrivadoSeRechazaAunEnAllowlist() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, "169.254.169.254", "10.0.5.5");
        for (String url : new String[] {"http://169.254.169.254/latest/meta-data/", "https://10.0.5.5/hook",
            "https://localhost/hook", "https://[fd00:ec2::254]/hook"}) {
            MvcResult r = call(post("/v1/webhooks").with(token(tenant, "admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + url + "\",\"events\":[\"extraccion.aprobada\"]}"));
            assertThat(r.getResponse().getStatus()).as(url).isEqualTo(400);
            assertThat(json(r).path("code").asText()).as(url).startsWith("WEBHOOK_URL_BLOCKED_");
        }
    }

    @Test
    void ac01_eventosInvalidosOCuerpoMalformadoDan400() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        for (String body : new String[] {"{\"url\":\"" + hookUrl() + "\",\"events\":[]}",
            "{\"url\":\"" + hookUrl() + "\",\"events\":[\"no.existe\"]}", "{\"url\":\"" + hookUrl() + "\"}",
            "{\"events\":[\"extraccion.aprobada\"]}", "no es json"}) {
            MvcResult r = call(post("/v1/webhooks").with(token(tenant, "admin")).contentType(MediaType.APPLICATION_JSON)
                .content(body));
            assertThat(r.getResponse().getStatus()).as(body).isEqualTo(400);
        }
    }

    @Test
    void ac01_soloElAdminDelTenantGestionaYElOperadorSoloLee() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        roles.grant(tenant, "operador", Roles.OPERADOR);
        String body = "{\"url\":\"" + hookUrl() + "\",\"events\":[\"extraccion.aprobada\"]}";
        assertThat(call(post("/v1/webhooks").contentType(MediaType.APPLICATION_JSON).content(body)).getResponse()
            .getStatus()).isEqualTo(401);
        assertThat(call(post("/v1/webhooks").with(token(tenant, "operador")).contentType(MediaType.APPLICATION_JSON)
            .content(body)).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(post("/v1/webhooks").with(token(tenant, "nadie")).contentType(MediaType.APPLICATION_JSON)
            .content(body)).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(get("/v1/webhooks").with(token(tenant, "operador"))).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(get("/v1/webhooks").with(token(tenant, "nadie"))).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(delete("/v1/webhooks/" + UUID.randomUUID()).with(token(tenant, "operador"))).getResponse()
            .getStatus()).isEqualTo(403);
        assertThat(call(get("/v1/webhooks/policy").with(token(tenant, "operador"))).getResponse().getStatus())
            .isEqualTo(403);
    }

    @Test
    void ac01_tokenSinTenantOConTenantMalformadoEsDenegado() throws Exception {
        String tenant = newTenant();
        roles.grant(tenant, "admin", Roles.TENANT_ADMIN);
        var noTenant = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
            .jwt(j -> j.subject("admin"));
        var badTenant = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
            .jwt(j -> j.subject("admin").claim("tenant_id", tenant.toUpperCase() + "x"));
        assertThat(call(get("/v1/webhooks").with(noTenant)).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(get("/v1/webhooks").with(badTenant)).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void ac07_unTenantNoVeNiBorraNiRotaNiConsultaWebhooksDeOtro() throws Exception {
        String a = newTenant();
        String b = newTenant();
        allowHosts(a, HOST);
        allowHosts(b, HOST);
        String idA = createWebhook(a, hookUrl(), "extraccion.aprobada").path("id").asText();
        createWebhook(b, hookUrl(), "documento.rechazado");

        JsonNode listB = json(call(get("/v1/webhooks").with(token(b, "admin"))));
        assertThat(listB).hasSize(1);
        assertThat(listB.get(0).path("id").asText()).isNotEqualTo(idA);
        assertThat(call(delete("/v1/webhooks/" + idA).with(token(b, "admin"))).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(post("/v1/webhooks/" + idA + "/secret/rotate").with(token(b, "admin"))).getResponse().getStatus())
            .isEqualTo(404);
        assertThat(call(get("/v1/webhooks/" + idA + "/deliveries").with(token(b, "admin"))).getResponse().getStatus())
            .isEqualTo(404);
        assertThat(json(call(get("/v1/webhooks").with(token(a, "admin"))))).hasSize(1);
    }

    @Test
    void ac07_laPoliticaEsPorTenant() throws Exception {
        String a = newTenant();
        String b = newTenant();
        allowHosts(a, "solo-a.banco.test");
        JsonNode pa = json(call(get("/v1/webhooks/policy").with(token(a, "admin"))));
        JsonNode pb = json(call(get("/v1/webhooks/policy").with(token(b, admin(b)))));
        assertThat(pa.path("allowedHosts")).extracting(JsonNode::asText).containsExactly("solo-a.banco.test");
        assertThat(pb.path("allowedHosts")).isEmpty();
    }

    @Test
    void ac01_bajaDesactivaYLaSegundaBajaDa404() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        String id = createWebhook(tenant, hookUrl(), "extraccion.aprobada").path("id").asText();
        assertThat(call(delete("/v1/webhooks/" + id).with(token(tenant, "admin"))).getResponse().getStatus())
            .isEqualTo(204);
        assertThat(json(call(get("/v1/webhooks").with(token(tenant, "admin"))))).isEmpty();
        assertThat(call(delete("/v1/webhooks/" + id).with(token(tenant, "admin"))).getResponse().getStatus())
            .isEqualTo(404);
        assertThat(call(delete("/v1/webhooks/no-es-uuid").with(token(tenant, "admin"))).getResponse().getStatus())
            .isEqualTo(400);
    }

    @Test
    void ac01_limiteDeSuscripcionesActivasPorTenant() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        for (int i = 0; i < 20; i++) {
            createWebhook(tenant, hookUrl(), "extraccion.aprobada");
        }
        MvcResult r = call(post("/v1/webhooks").with(token(tenant, "admin")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + hookUrl() + "\",\"events\":[\"extraccion.aprobada\"]}"));
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(r).path("code").asText()).isEqualTo("WEBHOOK_LIMIT_REACHED");
    }

    @Test
    void ac09_rotacionGeneraSecretoNuevoConservaElAnteriorYSeCierraAMano() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        JsonNode created = createWebhook(tenant, hookUrl(), "extraccion.aprobada");
        String id = created.path("id").asText();
        String original = created.path("secret").asText();

        MvcResult rot = call(post("/v1/webhooks/" + id + "/secret/rotate").with(token(tenant, "admin")));
        assertThat(rot.getResponse().getStatus()).isEqualTo(200);
        assertThat(rot.getResponse().getHeader("Cache-Control")).contains("no-store");
        String fresh = json(rot).path("secret").asText();
        assertThat(fresh).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(original);
        assertThat(json(rot).path("previousSecretExpiresAt").asText()).isNotBlank();

        // Ambos secretos quedan cifrados y descifrables: vigente = nuevo, anterior = original.
        UUID wid = UUID.fromString(id);
        var row = inTenant(tenant, () -> jdbc.queryForMap(
            "select secret_current, secret_previous from webhook_subscription where id = ?", wid));
        assertThat(secrets.open(UUID.fromString(tenant), wid, (String) row.get("secret_current"))).isEqualTo(fresh);
        assertThat(secrets.open(UUID.fromString(tenant), wid, (String) row.get("secret_previous"))).isEqualTo(original);

        JsonNode item = json(call(get("/v1/webhooks").with(token(tenant, "admin")))).get(0);
        assertThat(item.path("rotating").asBoolean()).isTrue();

        assertThat(call(post("/v1/webhooks/" + id + "/secret/rotate").with(token(tenant, "admin"))).getResponse()
            .getStatus()).isEqualTo(409);

        assertThat(call(delete("/v1/webhooks/" + id + "/secret/previous").with(token(tenant, "admin"))).getResponse()
            .getStatus()).isEqualTo(204);
        assertThat(inTenant(tenant, () -> jdbc.queryForObject(
            "select secret_previous from webhook_subscription where id = ?", String.class, wid))).isNull();
        assertThat(json(call(get("/v1/webhooks").with(token(tenant, "admin")))).get(0).path("rotating").asBoolean())
            .isFalse();
        assertThat(call(delete("/v1/webhooks/" + id + "/secret/previous").with(token(tenant, "admin"))).getResponse()
            .getStatus()).isEqualTo(409);
        // Terminada la rotacion se puede iniciar otra.
        assertThat(call(post("/v1/webhooks/" + id + "/secret/rotate").with(token(tenant, "admin"))).getResponse()
            .getStatus()).isEqualTo(200);
    }

    @Test
    void ac09_elSecretoAnteriorExpiraSoloTrasLaVentanaDeSolapamiento() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        String id = createWebhook(tenant, hookUrl(), "extraccion.aprobada").path("id").asText();
        call(post("/v1/webhooks/" + id + "/secret/rotate").with(token(tenant, "admin")));
        clock.advance(Duration.ofDays(6));
        assertThat(json(call(get("/v1/webhooks").with(token(tenant, "admin")))).get(0).path("rotating").asBoolean())
            .isTrue();
        clock.advance(Duration.ofDays(2));
        assertThat(json(call(get("/v1/webhooks").with(token(tenant, "admin")))).get(0).path("rotating").asBoolean())
            .isFalse();
        // Vencido el solapamiento una nueva rotacion ya no choca con la anterior.
        assertThat(call(post("/v1/webhooks/" + id + "/secret/rotate").with(token(tenant, "admin"))).getResponse()
            .getStatus()).isEqualTo(200);
    }

    @Test
    void ac16_politicaPorDefectoYActualizacionConValidaciones() throws Exception {
        String tenant = newTenant();
        JsonNode def = json(call(get("/v1/webhooks/policy").with(token(tenant, admin(tenant)))));
        assertThat(def.path("allowedHosts")).isEmpty();
        assertThat(def.path("maxAttempts").asInt()).isEqualTo(5);
        assertThat(def.path("initialBackoffSeconds").asLong()).isEqualTo(30);
        assertThat(def.path("backoffMultiplier").asDouble()).isEqualTo(2.0);
        assertThat(def.path("maxBackoffSeconds").asLong()).isEqualTo(3600);

        allowHosts(tenant, 3, 30, 3.0, 600, "A.Banco.Test", "*.partner.test", "a.banco.test");
        JsonNode upd = json(call(get("/v1/webhooks/policy").with(token(tenant, "admin"))));
        assertThat(upd.path("allowedHosts")).extracting(JsonNode::asText)
            .containsExactly("a.banco.test", "*.partner.test");
        assertThat(upd.path("maxAttempts").asInt()).isEqualTo(3);
        assertThat(upd.path("backoffMultiplier").asDouble()).isEqualTo(3.0);

        for (String bad : new String[] {
            "{\"allowedHosts\":[],\"maxAttempts\":0,\"initialBackoffSeconds\":10,\"backoffMultiplier\":2,\"maxBackoffSeconds\":60}",
            "{\"allowedHosts\":[],\"maxAttempts\":11,\"initialBackoffSeconds\":10,\"backoffMultiplier\":2,\"maxBackoffSeconds\":60}",
            "{\"allowedHosts\":[],\"maxAttempts\":3,\"initialBackoffSeconds\":0,\"backoffMultiplier\":2,\"maxBackoffSeconds\":60}",
            "{\"allowedHosts\":[],\"maxAttempts\":3,\"initialBackoffSeconds\":10,\"backoffMultiplier\":0.5,\"maxBackoffSeconds\":60}",
            "{\"allowedHosts\":[],\"maxAttempts\":3,\"initialBackoffSeconds\":10,\"backoffMultiplier\":2,\"maxBackoffSeconds\":5}",
            "{\"allowedHosts\":[],\"maxAttempts\":3,\"initialBackoffSeconds\":10,\"backoffMultiplier\":2,\"maxBackoffSeconds\":90000}",
            "{\"allowedHosts\":[\"https://x.com\"],\"maxAttempts\":3,\"initialBackoffSeconds\":10,\"backoffMultiplier\":2,\"maxBackoffSeconds\":60}",
            "{\"allowedHosts\":[\"x.com/path\"],\"maxAttempts\":3,\"initialBackoffSeconds\":10,\"backoffMultiplier\":2,\"maxBackoffSeconds\":60}"}) {
            MvcResult r = call(put("/v1/webhooks/policy").with(token(tenant, "admin"))
                .contentType(MediaType.APPLICATION_JSON).content(bad));
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(json(r).path("code").asText()).as(bad).isEqualTo("WEBHOOK_POLICY_INVALID");
        }
    }
}
