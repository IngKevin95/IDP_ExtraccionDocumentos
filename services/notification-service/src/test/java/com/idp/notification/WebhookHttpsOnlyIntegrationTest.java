package com.idp.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** AC-10 con la configuracion de produccion: solo HTTPS (en el resto de pruebas se admite http por el receptor local). */
@TestPropertySource(properties = "idp.notification.allow-insecure-http=false")
class WebhookHttpsOnlyIntegrationTest extends AbstractIntegrationTest {

    private MvcResult create(String tenant, String url) throws Exception {
        return mvc.perform(post("/v1/webhooks").with(token(tenant, "admin")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"url\":\"" + url + "\",\"events\":[\"extraccion.aprobada\"]}")).andReturn();
    }

    @Test
    void ac10_httpPlanoSeRechazaYHttpsSeAcepta() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST);
        MvcResult plain = create(tenant, "http://" + HOST + "/webhook");
        assertThat(plain.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(plain).path("code").asText()).isEqualTo("WEBHOOK_URL_INSECURE_SCHEME");
        MvcResult secure = create(tenant, "https://" + HOST + "/webhook");
        assertThat(secure.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void ac10_httpHaciaMetadataCloudSeRechazaPorElCidrAntesQueEsquema() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, "169.254.169.254");
        MvcResult r = create(tenant, "http://169.254.169.254/latest/meta-data/");
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(r).path("code").asText()).isEqualTo("WEBHOOK_URL_BLOCKED_ADDRESS");
    }
}
