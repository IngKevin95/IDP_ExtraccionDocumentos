package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.kms.EnvelopeCrypto;
import com.idp.storage.ArtifactKind;
import com.idp.storage.EncryptedArtifactStore;
import com.idp.storage.ObjectStore;
import com.idp.tenant.context.TenantKeyResolver;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** Recorte de pagina del campo dudoso servido con enlace firmado de corta vida. */
class ReviewCropIntegrationTest extends AbstractReviewIntegrationTest {

    private static final int W = 1000;
    private static final int H = 2000;

    @Autowired ObjectStore objects;
    @Autowired EnvelopeCrypto crypto;
    @Autowired TenantKeyResolver keys;
    @Autowired com.idp.review.config.ReviewProperties props;

    private UUID documentOf(UUID taskId) {
        return inTenant(tenant, () -> jdbc.queryForObject("select document_id from review_task where id = ?",
                UUID.class, taskId));
    }

    private void storePage(UUID documentId, int page) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setColor(Color.RED);
        g.fillRect(100, 200, 500, 100);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        new EncryptedArtifactStore(objects, crypto, keys).put(tenant, documentId, ArtifactKind.PAGE_PNG, page,
                out.toByteArray());
    }

    private UUID fieldId(UUID taskId, String name) {
        return inTenant(tenant, () -> jdbc.queryForObject(
                "select id from review_field where task_id = ? and field_name = ?", UUID.class, taskId, name));
    }

    private String linkPath(String url) {
        URI u = URI.create(url);
        return u.getRawPath() + "?" + u.getRawQuery();
    }

    @Test
    void elEnlaceApuntaAlRecorteVenceEnPocoYSirveSoloElRecorte() throws Exception {
        reviewer("ana");
        UUID taskId = createTask(field("monto", 1, "[0.1,0.1,0.5,0.05]"));
        storePage(documentOf(taskId), 1);
        UUID fieldId = fieldId(taskId, "monto");

        MvcResult linkResult = getAs("ana", "/v1/review/tasks/" + taskId + "/fields/" + fieldId + "/crop-link");

        assertThat(status(linkResult)).isEqualTo(200);
        JsonNode link = body(linkResult);
        Instant expires = Instant.parse(link.path("expiresAt").asText());
        assertThat(Duration.between(Instant.now(), expires)).isLessThanOrEqualTo(props.cropTtl());
        assertThat(props.cropTtl()).isLessThanOrEqualTo(Duration.ofMinutes(5));
        assertThat(link.path("url").asText()).contains("/crop?exp=").contains("&sig=");

        MvcResult crop = mvc.perform(get(linkPath(link.path("url").asText())).with(token(tenant, "ana"))).andReturn();

        assertThat(status(crop)).isEqualTo(200);
        assertThat(crop.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(crop.getResponse().getHeader("Cache-Control")).contains("no-store");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(crop.getResponse().getContentAsByteArray()));
        // caja 0.5 x 0.05 de 1000x2000 = 500x100 mas 1% de margen por lado
        assertThat(img.getWidth()).isBetween(500, 540);
        assertThat(img.getHeight()).isBetween(100, 180);
        assertThat(img.getWidth()).isLessThan(W);
        assertThat(img.getHeight()).isLessThan(H);
    }

    @Test
    void unaFirmaAlteradaOAjenaSeRechaza() throws Exception {
        reviewer("ana");
        reviewer("beto");
        UUID taskId = createTask(field("monto", 1, "[0.1,0.1,0.5,0.05]"));
        storePage(documentOf(taskId), 1);
        UUID fieldId = fieldId(taskId, "monto");
        String url = body(getAs("ana", "/v1/review/tasks/" + taskId + "/fields/" + fieldId + "/crop-link"))
                .path("url").asText();
        String path = linkPath(url);

        MvcResult tampered = mvc.perform(get(path.replaceAll("sig=.{4}", "sig=0000")).with(token(tenant, "ana")))
                .andReturn();
        MvcResult otherUser = mvc.perform(get(path).with(token(tenant, "beto"))).andReturn();
        MvcResult noSig = mvc.perform(get("/v1/review/tasks/" + taskId + "/fields/" + fieldId + "/crop?exp=1&sig=x")
                .with(token(tenant, "ana"))).andReturn();

        assertThat(status(tampered)).isEqualTo(403);
        assertThat(status(otherUser)).isEqualTo(403);
        assertThat(status(noSig)).isEqualTo(403);
    }

    @Test
    void sinEvidenciaOSinPaginaNoHayRecorte() throws Exception {
        reviewer("ana");
        UUID taskId = createTask(field("monto", null, null), field("ciudad", 3, "[0.1,0.1,0.5,0.05]"),
                field("juzgado", 1, "[0.9,0.9,0.5,0.5]"));
        UUID sinEvidencia = fieldId(taskId, "monto");
        UUID sinPagina = fieldId(taskId, "ciudad");
        UUID cajaInvalida = fieldId(taskId, "juzgado");
        storePage(documentOf(taskId), 1);

        MvcResult noEvidence = getAs("ana", "/v1/review/tasks/" + taskId + "/fields/" + sinEvidencia + "/crop-link");
        MvcResult linkMissingPage = getAs("ana", "/v1/review/tasks/" + taskId + "/fields/" + sinPagina + "/crop-link");
        String url = body(linkMissingPage).path("url").asText();
        MvcResult missingPage = mvc.perform(get(linkPath(url)).with(token(tenant, "ana"))).andReturn();
        MvcResult linkBadBox = getAs("ana", "/v1/review/tasks/" + taskId + "/fields/" + cajaInvalida + "/crop-link");
        MvcResult badBox = mvc.perform(get(linkPath(body(linkBadBox).path("url").asText())).with(token(tenant, "ana")))
                .andReturn();

        assertThat(status(noEvidence)).isEqualTo(409);
        assertThat(body(noEvidence).path("code").asText()).isEqualTo("REVIEW_NO_EVIDENCE");
        assertThat(status(missingPage)).isEqualTo(409);
        assertThat(body(missingPage).path("code").asText()).isEqualTo("REVIEW_PAGE_UNAVAILABLE");
        assertThat(status(badBox)).isEqualTo(409);
    }

    @Test
    void soloElRevisorVeElRecorteYSoloSiLaTareaNoEstaAsignadaAOtro() throws Exception {
        reviewer("ana");
        reviewer("beto");
        admin("sup");
        UUID taskId = createTask(field("monto", 1, "[0.1,0.1,0.5,0.05]"));
        storePage(documentOf(taskId), 1);
        UUID fieldId = fieldId(taskId, "monto");
        String linkUrl = "/v1/review/tasks/" + taskId + "/fields/" + fieldId + "/crop-link";
        post("ana", "/v1/review/tasks/" + taskId + "/claim");

        assertThat(status(getAs("sup", linkUrl))).isEqualTo(403);
        assertThat(status(getAs("beto", linkUrl))).isEqualTo(409);
        assertThat(status(getAs("ana", linkUrl))).isEqualTo(200);
        post("ana", "/v1/review/tasks/" + taskId + "/reject");
        assertThat(status(getAs("ana", linkUrl))).isEqualTo(400);
    }
}
