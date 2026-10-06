package com.idp.e2e;

import static com.idp.e2e.E2eEnvironment.ADMIN_D;
import static com.idp.e2e.E2eEnvironment.REVISOR_D;
import static com.idp.e2e.E2eEnvironment.STEWARD_D;
import static com.idp.e2e.E2eEnvironment.TENANT_D;
import static com.idp.e2e.E2eEnvironment.UPLOADER_D;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Lazo del muestreo ciego con Kafka embebido y los servicios reales: extraccion.aprobada (AUTO_STP) -> quality-service
 * selecciona y publica calidad.muestra_ciega_solicitada por outbox -> review-service crea la tarea ciega (sin exponer la
 * salida del modelo) -> el revisor independiente transcribe -> revision.completada con blindSample=true -> quality-service
 * mide acuerdo/desacuerdo y el error silente cambia el reporte.
 */
class BlindSampleLoopE2eTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static E2eEnvironment env;

    @BeforeAll
    static void start() {
        env = E2eEnvironment.get();
    }

    @Test
    void given_oficioAutoAprobadoMuestreado_when_revisorIndependienteTranscribe_then_errorSilenteCambiaElReporte()
            throws Exception {
        // --- documento 1: el revisor ciego coincide con el modelo (acuerdo) ---------------------------------
        UUID agree = UUID.randomUUID();
        seedApprovedDocument(agree, "operador-d", "Calle 1", "1000", "Bogota");
        approve(agree);
        UUID agreeSample = awaitSample(agree);
        completeBlind(agreeSample, "Calle 1", "1000", "Bogota");

        JsonNode agreeCompleted = awaitRevision(agree);
        assertThat(agreeCompleted.path("blindSample").asBoolean()).isTrue();
        assertThat(agreeCompleted.has("correctedFields")).isFalse();
        awaitReport(1, 0);

        // --- documento 2: el revisor ciego transcribe un monto distinto (desacuerdo = error silente) -----------
        UUID disagree = UUID.randomUUID();
        seedApprovedDocument(disagree, "operador-d", "Calle 2", "5000", "Cali");
        approve(disagree);
        UUID disagreeSample = awaitSample(disagree);
        completeBlind(disagreeSample, "Calle 2", "7000", "Cali");

        JsonNode completed = awaitRevision(disagree);
        assertThat(completed.path("criticalCorrection").asBoolean()).isFalse();
        assertThat(completed.has("secondReviewerId")).isFalse();
        assertThat(completed.path("correctedFields")).hasSize(1);
        assertThat(completed.path("correctedFields").get(0).path("field").asText()).isEqualTo("monto");
        assertThat(completed.path("correctedFields").get(0).path("correctionType").asText()).isEqualTo("VALOR");
        // Sin valores en Kafka: ni lo extraido ni lo transcrito.
        assertThat(completed.toString()).doesNotContain("7000").doesNotContain("5000").doesNotContain("Cali");
        awaitReport(2, 1);

        // Todos los eventos del lazo cumplen su esquema (incluye el nuevo calidad.muestra_ciega_solicitada).
        for (JsonNode e : env.events()) {
            env.validator.validateFlat(e);
        }
        // El documento no se reabrio: ningun evento extraccion.requiere_revision para estos documentos.
        for (JsonNode e : env.events()) {
            if (agree.toString().equals(e.path("documentId").asText())
                    || disagree.toString().equals(e.path("documentId").asText())) {
                assertThat(e.path("eventType").asText()).isNotEqualTo("extraccion.requiere_revision");
            }
        }
    }

    // ---- pasos ------------------------------------------------------------------------------------------

    /** Extraccion del documento en el silo (lo que escribio extraction-service) y quien lo cargo. */
    private static void seedApprovedDocument(UUID documentId, String uploader, String direccion, String monto,
                                             String ciudad) throws SQLException {
        try (Connection c = DriverManager.getConnection(E2eEnvironment.reviewUrl(TENANT_D), "sa", "")) {
            UUID extraction = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement("insert into document (id, uploaded_by, status, approved_by, "
                + "classification) values (?, ?, 'APROBADO', 'AUTO_STP', 'CONFIDENCIAL')")) {
                ps.setObject(1, documentId);
                ps.setString(2, uploader);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("insert into extraction (id, document_id, tenant_id, "
                + "status) values (?, ?, ?, 'COMPLETED')")) {
                ps.setObject(1, extraction);
                ps.setObject(2, documentId);
                ps.setObject(3, UUID.fromString(TENANT_D));
                ps.executeUpdate();
            }
            String[][] fields = {{"direccion", direccion}, {"monto", monto}, {"ciudad", ciudad}};
            for (String[] f : fields) {
                try (PreparedStatement ps = c.prepareStatement("insert into field_value (id, extraction_id, "
                    + "field_name, value_text, confidence_score, evidence_page, bounding_box) "
                    + "values (?, ?, ?, ?, 0.9912, 1, '[0.1,0.1,0.4,0.05]')")) {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, extraction);
                    ps.setString(3, f[0]);
                    ps.setString(4, f[1]);
                    ps.executeUpdate();
                }
            }
        }
    }

    /** Publica la aprobacion AUTO_STP como lo haria document-service (el muestreo de tipologia ZZ es del 100 %). */
    private static void approve(UUID documentId) {
        ObjectNode n = E2eEnvironment.JSON.createObjectNode();
        n.put("eventId", UUID.randomUUID().toString());
        n.put("eventType", "extraccion.aprobada");
        n.put("schemaVersion", 1);
        n.put("occurredAt", Instant.now().toString());
        n.put("tenantId", TENANT_D);
        n.put("correlationId", UUID.randomUUID().toString());
        n.put("documentId", documentId.toString());
        n.put("approvedBy", "AUTO_STP");
        n.put("typology", "ZZ");
        env.validator.validateFlat(n);
        env.publish(documentId.toString(), n.toString());
    }

    /** quality-service publica la solicitud; review-service crea la tarea ciega con ese sampleId. */
    private static UUID awaitSample(UUID documentId) {
        JsonNode[] found = new JsonNode[1];
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            found[0] = null;
            for (JsonNode e : env.events()) {
                if ("calidad.muestra_ciega_solicitada".equals(e.path("eventType").asText())
                    && documentId.toString().equals(e.path("documentId").asText())) {
                    found[0] = e;
                }
            }
            assertThat(found[0]).isNotNull();
        });
        JsonNode e = found[0];
        env.validator.validateFlat(e);
        assertThat(e.path("tenantId").asText()).isEqualTo(TENANT_D);
        assertThat(e.path("typology").asText()).isEqualTo("ZZ");
        UUID sampleId = UUID.fromString(e.path("sampleId").asText());
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            Resp r = review("GET", "/v1/review/tasks/" + sampleId, REVISOR_D, null);
            assertThat(r.status()).isEqualTo(200);
            // Ceguera: el REVISOR no recibe el flag; solo el rol de gestion lo ve (hardening F5).
            assertThat(r.body().path("blindSample").asBoolean()).isFalse();
            assertThat(review("GET", "/v1/review/tasks/" + sampleId, ADMIN_D, null).body().path("blindSample")
                .asBoolean()).isTrue();
            assertThat(r.body().path("status").asText()).isEqualTo("PENDING");
            assertThat(r.body().path("documentId").asText()).isEqualTo(documentId.toString());
        });
        return sampleId;
    }

    private static void completeBlind(UUID taskId, String direccion, String monto, String ciudad) throws Exception {
        String base = "/v1/review/tasks/" + taskId;
        // El cargador del documento tiene rol REVISOR pero no es independiente.
        assertThat(review("POST", base + "/claim", UPLOADER_D, null).status()).isEqualTo(403);

        Resp fields = review("GET", base + "/fields", REVISOR_D, null);
        assertThat(fields.status()).isEqualTo(200);
        assertThat(fields.body()).hasSize(3);
        for (JsonNode f : fields.body()) {
            assertThat(f.path("confidence").isNull()).isTrue();
        }
        assertThat(fields.raw()).doesNotContain("0.9912");

        Resp saved = review("POST", base + "/corrections", REVISOR_D, "[{\"fieldName\":\"direccion\","
            + "\"correctedValue\":\"" + direccion + "\"},{\"fieldName\":\"monto\",\"correctedValue\":\"" + monto
            + "\"},{\"fieldName\":\"ciudad\",\"correctedValue\":\"" + ciudad + "\"}]");
        assertThat(saved.status()).isEqualTo(200);
        for (JsonNode c : saved.body()) {
            assertThat(c.path("originalValue").isNull()).isTrue();
        }
        Resp done = review("POST", base + "/approve", REVISOR_D, null);
        assertThat(done.status()).isEqualTo(200);
        assertThat(done.body().path("status").asText()).isEqualTo("APPROVED");
    }

    private static JsonNode awaitRevision(UUID documentId) {
        JsonNode[] found = new JsonNode[1];
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            found[0] = null;
            for (JsonNode e : env.events()) {
                if ("revision.completada".equals(e.path("eventType").asText())
                    && documentId.toString().equals(e.path("documentId").asText())) {
                    found[0] = e;
                }
            }
            assertThat(found[0]).isNotNull();
        });
        env.validator.validateFlat(found[0]);
        assertThat(found[0].path("reviewerId").asText()).isEqualTo(REVISOR_D);
        return found[0];
    }

    /** quality-service ingiere la revision ciega: el reporte de error silente refleja muestras y desacuerdos. */
    private static void awaitReport(int blindSamples, int silentErrors) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            Resp r = quality("/v1/quality/reports/silent-error?start_date=" + today.minusDays(1) + "&end_date="
                + today.plusDays(1));
            assertThat(r.status()).isEqualTo(200);
            int samples = 0;
            int errors = 0;
            for (JsonNode d : r.body().path("data")) {
                samples += d.path("blind_samples_total").asInt();
                errors += d.path("silent_errors_found").asInt();
            }
            assertThat(samples).isEqualTo(blindSamples);
            assertThat(errors).isEqualTo(silentErrors);
        });
    }

    // ---- HTTP -------------------------------------------------------------------------------------------

    record Resp(int status, String raw, JsonNode body) {
    }

    private static Resp review(String method, String path, String user, String json) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + env.reviewPort + path))
            .header("Authorization", env.bearer(TENANT_D, user, Overrides.Shared.ISSUER, List.of("review-service")));
        if ("GET".equals(method)) {
            req.GET();
        } else if (json != null) {
            req.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        } else {
            req.POST(HttpRequest.BodyPublishers.noBody());
        }
        return resp(HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString()));
    }

    private static Resp quality(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + env.qualityPort + path))
            .header("Authorization", env.bearer(TENANT_D, STEWARD_D, Overrides.Shared.ISSUER,
                List.of("quality-service"))).GET().build();
        return resp(HTTP.send(req, HttpResponse.BodyHandlers.ofString()));
    }

    private static Resp resp(HttpResponse<String> r) throws Exception {
        String raw = r.body() == null ? "" : r.body();
        return new Resp(r.statusCode(), raw, raw.isBlank() ? E2eEnvironment.JSON.nullNode()
            : E2eEnvironment.JSON.readTree(raw));
    }
}
