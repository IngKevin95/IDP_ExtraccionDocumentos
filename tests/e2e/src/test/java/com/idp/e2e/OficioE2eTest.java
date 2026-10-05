package com.idp.e2e;

import static com.idp.e2e.E2eEnvironment.AUDITOR_A;
import static com.idp.e2e.E2eEnvironment.OPERATOR_A;
import static com.idp.e2e.E2eEnvironment.OPERATOR_B;
import static com.idp.e2e.E2eEnvironment.TENANT_A;
import static com.idp.e2e.E2eEnvironment.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.kms.EnvelopeCiphertext;
import com.idp.kms.EnvelopeCrypto;
import com.idp.storage.ArtifactKind;
import com.idp.storage.EncryptedArtifactStore;
import com.idp.tenant.TenantId;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Flujo del oficio de punta a punta sin Docker: document-service, extraction-service y audit-service en una misma
 * JVM, Kafka embebido, H2 por silo y bucket/KMS/renderer/LLM falsos.
 */
class OficioE2eTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static E2eEnvironment env;

    record Resp(int status, JsonNode body) {
    }

    @BeforeAll
    static void start() {
        env = E2eEnvironment.get();
    }

    @AfterEach
    void resetLlm() {
        Overrides.Shared.LLM.mode = DeterministicLlm.Mode.CLEAN;
    }

    @Test
    void given_pdfSinteticoDelTenantA_when_seCargaYProcesa_then_apruebaPublicaEventosYAuditaLaCadenaEnOrden()
            throws Exception {
        Resp created = upload(TENANT_A, OPERATOR_A, pdf(), radicado(), null);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("status").asText()).isEqualTo("EN_EXTRACCION");
        String id = created.body().path("id").asText();

        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> assertThat(status(TENANT_A, OPERATOR_A, id)).isEqualTo("APROBADO"));

        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> assertThat(typesOf(id)).contains("extraccion.aprobada"));
        List<String> types = typesOf(id);
        assertThat(types).contains("documento.recibido", "documento.renderizado", "extraccion.solicitada",
            "extraccion.completada", "extraccion.aprobada");
        // Los eventos de una misma transaccion comparten created_at (desempate por id), asi que solo el orden
        // causal entre transacciones es determinista: solicitada -> completada -> aprobada.
        List<String> expected = List.of("extraccion.solicitada", "extraccion.completada", "extraccion.aprobada");
        assertThat(inOrder(types, expected)).as("orden de eventos en Kafka %s", types).isTrue();

        // extraccion con score alto en el silo de extraccion del tenant A
        assertThat(extractionRow(TENANT_A, id, "status")).isEqualTo("COMPLETED");
        assertThat(Double.parseDouble(extractionRow(TENANT_A, id, "overall_score"))).isGreaterThanOrEqualTo(0.85);

        // audit-service registra la cadena del tenant A en el orden de ingesta y la verificacion pasa
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250)).untilAsserted(
            () -> assertThat(auditTypes(TENANT_A, id)).contains("extraccion.aprobada"));
        List<String> audited = auditTypes(TENANT_A, id);
        assertThat(inOrder(audited, expected)).as("orden en la cadena de auditoria %s", audited).isTrue();
        List<Long> sequences = auditSequences(TENANT_A);
        for (int i = 0; i < sequences.size(); i++) {
            assertThat(sequences.get(i)).isEqualTo(i + 1L);
        }
        Resp verify = get(env.auditPort, "/v1/audit/verify", TENANT_A, AUDITOR_A);
        assertThat(verify.status()).isEqualTo(200);
        assertThat(verify.body().path("isChainIntact").asBoolean()).isTrue();
        assertThat(verify.body().path("totalRecordsVerified").asInt()).isGreaterThanOrEqualTo(5);

        assertEventsMatchSchemas();
    }

    @Test
    void given_montoNumerosDistintoDeLetras_when_seExtrae_then_documentoQuedaEnRevisionConEventoDeRevision()
            throws Exception {
        Overrides.Shared.LLM.mode = DeterministicLlm.Mode.INCONSISTENT_AMOUNT;
        Resp created = upload(TENANT_A, OPERATOR_A, pdf(), radicado(), null);
        assertThat(created.status()).isEqualTo(201);
        String id = created.body().path("id").asText();

        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> assertThat(status(TENANT_A, OPERATOR_A, id)).isEqualTo("EN_REVISION"));

        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> assertThat(typesOf(id)).contains("extraccion.requiere_revision"));
        List<String> types = typesOf(id);
        assertThat(types).contains("extraccion.requiere_revision")
            .doesNotContain("extraccion.completada", "extraccion.aprobada");
        assertThat(extractionRow(TENANT_A, id, "status")).isEqualTo("REQUIRES_REVIEW");
        assertEventsMatchSchemas();
    }

    @Test
    void given_documentoDelTenantA_when_elTenantBLoConsulta_then_noLoVeNoLoListaNoLoDescargaYLosArtefactosNoSeLeen()
            throws Exception {
        Resp created = upload(TENANT_A, OPERATOR_A, pdf(), radicado(), null);
        assertThat(created.status()).isEqualTo(201);
        String id = created.body().path("id").asText();
        UUID docId = UUID.fromString(id);

        assertThat(get(env.documentPort, "/v1/documents/" + id, TENANT_A, OPERATOR_A).status()).isEqualTo(200);
        assertThat(get(env.documentPort, "/v1/documents/" + id, TENANT_B, OPERATOR_B).status()).isEqualTo(404);
        assertThat(get(env.documentPort, "/v1/documents/" + id + "/download", TENANT_B, OPERATOR_B).status())
            .isEqualTo(404);
        Resp listB = get(env.documentPort, "/v1/documents?limit=100", TENANT_B, OPERATOR_B);
        assertThat(listB.status()).isEqualTo(200);
        assertThat(listB.body().path("data")).isEmpty();
        assertThat(listB.body().path("total").asLong()).isZero();
        Resp listA = get(env.documentPort, "/v1/documents?limit=100", TENANT_A, OPERATOR_A);
        assertThat(listA.body().toString()).contains(id);

        // nada del documento bajo el prefijo del tenant B y el cifrado de A no abre con el contexto de B
        assertThat(Overrides.Shared.STORE.keys(TENANT_B)).noneMatch(k -> k.contains(id));
        String originalKey = "documents/" + id + "/original.enc";
        byte[] raw = Overrides.Shared.STORE.raw(TENANT_A, originalKey);
        assertThat(raw).isNotNull();
        EnvelopeCiphertext envelope = EnvelopeCiphertext.fromBytes(raw);
        EnvelopeCrypto crypto = new EnvelopeCrypto(Overrides.Shared.KEYS);
        assertThat(crypto.decrypt(new TenantId(TENANT_A), "documents",
            EncryptedArtifactStore.aad(TENANT_A, docId, ArtifactKind.ORIGINAL, 0), envelope)).isNotEmpty();
        assertThatThrownBy(() -> crypto.decrypt(new TenantId(TENANT_A), "documents",
            EncryptedArtifactStore.aad(TENANT_B, docId, ArtifactKind.ORIGINAL, 0), envelope))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> crypto.decrypt(new TenantId(TENANT_B), "documents",
            EncryptedArtifactStore.aad(TENANT_B, docId, ArtifactKind.ORIGINAL, 0), envelope))
            .isInstanceOf(RuntimeException.class);
        com.idp.tenant.context.TenantKeyResolver dummyResolver = new com.idp.tenant.context.TenantKeyResolver(null, java.time.Duration.ZERO, java.time.Clock.systemUTC()) {
            @Override
            public com.idp.tenant.context.TenantKeyResolver.TenantKeys resolve(String tenantId) {
                return new com.idp.tenant.context.TenantKeyResolver.TenantKeys("documents", "audit");
            }
        };
        assertThatThrownBy(() -> new EncryptedArtifactStore(Overrides.Shared.STORE, crypto, dummyResolver)
            .get(TENANT_B, docId, ArtifactKind.ORIGINAL, 0)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void given_mismoArchivoConLaMismaIdempotencyKey_when_seRecarga_then_noCreaSegundoDocumentoNiSegundoEvento()
            throws Exception {
        byte[] content = pdf();
        String key = "idem-" + UUID.randomUUID();
        Resp first = upload(TENANT_A, OPERATOR_A, content, radicado(), key);
        assertThat(first.status()).isEqualTo(201);
        String id = first.body().path("id").asText();
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> assertThat(status(TENANT_A, OPERATOR_A, id)).isEqualTo("APROBADO"));
        int rendersBefore = Overrides.Shared.RENDERER.calls.get();

        Resp second = upload(TENANT_A, OPERATOR_A, content, radicado(), key);

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().path("id").asText()).isEqualTo(id);
        assertThat(documentCount(TENANT_A, key)).isEqualTo(1);
        assertThat(Overrides.Shared.RENDERER.calls.get()).isEqualTo(rendersBefore);
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(250))
            .untilAsserted(() -> assertThat(typesOf(id)).contains("extraccion.aprobada"));
        List<String> types = typesOf(id);
        assertThat(types).filteredOn("documento.recibido"::equals).hasSize(1);
        assertThat(types).filteredOn("extraccion.solicitada"::equals).hasSize(1);
        assertThat(types).filteredOn("extraccion.aprobada"::equals).hasSize(1);
        assertEventsMatchSchemas();
    }

    // ---- utilidades -------------------------------------------------------------------------------------

    private static void assertEventsMatchSchemas() {
        List<JsonNode> all = env.events();
        assertThat(all).isNotEmpty();
        for (JsonNode e : all) {
            env.validator.validateFlat(e);
        }
    }

    private static List<String> typesOf(String documentId) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : env.events()) {
            if (documentId.equals(e.path("documentId").asText())) {
                out.add(e.path("eventType").asText());
            }
        }
        return out;
    }

    /** {@code expected} aparece como subsecuencia ordenada de {@code actual}. */
    private static boolean inOrder(List<String> actual, List<String> expected) {
        int from = 0;
        for (String type : expected) {
            int at = actual.subList(from, actual.size()).indexOf(type);
            if (at < 0) {
                return false;
            }
            from += at + 1;
        }
        return true;
    }

    private static String status(String tenant, String user, String id) throws Exception {
        Resp r = get(env.documentPort, "/v1/documents/" + id, tenant, user);
        return r.status() == 200 ? r.body().path("status").asText() : "HTTP_" + r.status();
    }

    private static Resp get(int port, String path, String tenant, String user) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", env.bearer(tenant, user)).GET().build();
        return toResp(HTTP.send(req, HttpResponse.BodyHandlers.ofString()));
    }

    private static Resp upload(String tenant, String user, byte[] content, String radicado, String idempotencyKey)
            throws Exception {
        String boundary = "e2e" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "typology", "EC");
        field(body, boundary, "radicado", radicado);
        field(body, boundary, "version", "1");
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"oficio.pdf\"\r\n"
            + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + env.documentPort
                + "/v1/documents"))
            .header("Authorization", env.bearer(tenant, user))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (idempotencyKey != null) {
            req.header("Idempotency-Key", idempotencyKey);
        }
        return toResp(HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString()));
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value
            + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static Resp toResp(HttpResponse<String> r) throws IOException {
        String b = r.body();
        return new Resp(r.statusCode(), b == null || b.isBlank() ? E2eEnvironment.JSON.createObjectNode()
            : E2eEnvironment.JSON.readTree(b));
    }

    private static byte[] pdf() {
        return ("%PDF-1.4 " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
    }

    private static String radicado() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 23; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }
        return sb.toString();
    }

    // ---- consultas JDBC a las bases H2 -------------------------------------------------------------------

    private static String extractionRow(String tenant, String documentId, String column) throws SQLException {
        try (Connection c = DriverManager.getConnection(Overrides.extractionUrl(tenant), "sa", "");
             PreparedStatement ps = c.prepareStatement("select " + column + " from extraction where document_id = ?")) {
            ps.setObject(1, UUID.fromString(documentId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    private static int documentCount(String tenant, String idempotencyKey) throws SQLException {
        try (Connection c = DriverManager.getConnection(E2eEnvironment.documentUrl(tenant), "sa", "");
             PreparedStatement ps = c.prepareStatement("select count(*) from document where idempotency_key = ?")) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static List<String> auditTypes(String tenant, String documentId) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(E2eEnvironment.CONTROL_URL, "sa", "");
             PreparedStatement ps = c.prepareStatement("select event_type from audit_entries "
                 + "where tenant_id = ? and document_id = ? order by sequence_id")) {
            ps.setObject(1, UUID.fromString(tenant));
            ps.setObject(2, UUID.fromString(documentId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static List<Long> auditSequences(String tenant) throws SQLException {
        List<Long> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(E2eEnvironment.CONTROL_URL, "sa", "");
             PreparedStatement ps = c.prepareStatement(
                 "select sequence_id from audit_entries where tenant_id = ? order by sequence_id")) {
            ps.setObject(1, UUID.fromString(tenant));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
        }
        return out;
    }
}
