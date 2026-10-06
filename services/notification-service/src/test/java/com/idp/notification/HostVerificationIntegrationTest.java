package com.idp.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.notification.support.FakeDomainOwnershipVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SEC-027 (hallazgo 1) con host-verification=dns: un host nuevo solo se activa con prueba de propiedad del dominio;
 * los comodines sobre sufijos publicos se rechazan en cualquier modo.
 */
@TestPropertySource(properties = "idp.notification.host-verification=dns")
class HostVerificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired FakeDomainOwnershipVerifier verifier;

    @BeforeEach
    void resetVerifier() {
        verifier.reset();
    }

    private MvcResult putPolicy(String tenant, String... hosts) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        var arr = body.putArray("allowedHosts");
        for (String h : hosts) {
            arr.add(h);
        }
        body.put("maxAttempts", 5);
        body.put("initialBackoffSeconds", 30);
        body.put("backoffMultiplier", 2.0);
        body.put("maxBackoffSeconds", 3600);
        return mvc.perform(put("/v1/webhooks/policy").with(token(tenant, admin(tenant)))
            .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
    }

    @Test
    void hostNuevoSinTxtSeRechazaConElDesafioYLuegoSeActivaCuandoElDominioLoPublica() throws Exception {
        String tenant = newTenant();
        MvcResult first = putPolicy(tenant, "*.banco.com");
        assertThat(first.getResponse().getStatus()).isEqualTo(409);
        JsonNode err = json(first);
        assertThat(err.path("code").asText()).isEqualTo("WEBHOOK_HOST_UNVERIFIED");
        assertThat(err.path("message").asText()).contains("_idp-verify.banco.com").contains("idp-verify=");
        String message = err.path("message").asText();

        // El desafio es estable entre intentos (mismo token) y la politica no cambio.
        MvcResult second = putPolicy(tenant, "*.banco.com");
        assertThat(json(second).path("message").asText()).isEqualTo(message);
        assertThat(json(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .get("/v1/webhooks/policy").with(token(tenant, "admin"))).andReturn()).path("allowedHosts")).isEmpty();

        verifier.publish("banco.com");
        MvcResult ok = putPolicy(tenant, "*.banco.com", "api.banco.com");
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(ok).path("allowedHosts")).extracting(JsonNode::asText)
            .containsExactly("*.banco.com", "api.banco.com");
    }

    @Test
    void laVerificacionEsPorTenantNoSeHeredaDeOtro() throws Exception {
        String a = newTenant();
        String b = newTenant();
        verifier.publish("banco.com");
        assertThat(putPolicy(a, "api.banco.com").getResponse().getStatus()).isEqualTo(200);
        // El verificador falso dice que banco.com publica TXT para cualquiera; lo relevante: B tiene su propio desafio.
        verifier.reset();
        assertThat(putPolicy(b, "api.banco.com").getResponse().getStatus()).isEqualTo(409);
        // A conserva su host aunque el TXT ya no este: no se reverifica lo ya activo.
        assertThat(putPolicy(a, "api.banco.com").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void comodinesSobreSufijosPublicosSeRechazanAunqueElDominioSeaVerificable() throws Exception {
        String tenant = newTenant();
        verifier.publish("com");
        for (String bad : new String[] {"*.com", "*.co.uk", "*.com.ar", "*.github.io", "com", "localhost"}) {
            MvcResult r = putPolicy(tenant, bad);
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(json(r).path("code").asText()).as(bad).isEqualTo("WEBHOOK_HOST_PUBLIC_SUFFIX");
        }
    }
}
