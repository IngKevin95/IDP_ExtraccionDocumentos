package com.idp.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A1 (tenant ACTIVE) y A2 (issuer y audiencia por servicio) con los servicios reales levantados. */
class AuthE2eTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static E2eEnvironment env;

    @BeforeAll
    static void start() {
        env = E2eEnvironment.get();
    }

    private static int get(int port, String path, String bearer) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", bearer).GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void a2_tokenConAudienciaOIssuerIncorrectosSeRechazaConUnauthorized() throws Exception {
        String path = "/v1/documents/" + UUID.randomUUID();
        String ok = env.bearer(E2eEnvironment.TENANT_A, E2eEnvironment.OPERATOR_A);
        String wrongAudience = env.bearer(E2eEnvironment.TENANT_A, E2eEnvironment.OPERATOR_A,
            Overrides.Shared.ISSUER, List.of("otro-servicio"));
        String wrongIssuer = env.bearer(E2eEnvironment.TENANT_A, E2eEnvironment.OPERATOR_A,
            "https://atacante.test", List.of("document-service"));

        assertThat(get(env.documentPort, path, ok)).isEqualTo(404);
        assertThat(get(env.documentPort, path, wrongAudience)).isEqualTo(401);
        assertThat(get(env.documentPort, path, wrongIssuer)).isEqualTo(401);
        // la audiencia de audit-service no sirve en document-service
        assertThat(get(env.documentPort, path, env.bearer(E2eEnvironment.TENANT_A, E2eEnvironment.OPERATOR_A,
            Overrides.Shared.ISSUER, List.of("audit-service")))).isEqualTo(401);
        assertThat(get(env.auditPort, "/v1/audit/verify", wrongAudience)).isEqualTo(401);
    }

    @Test
    void a1_tenantEnBajaSoloConservaRolesDeSupervisionYDestruidoNinguno() throws Exception {
        String tenant = UUID.randomUUID().toString();
        try (Connection c = DriverManager.getConnection(E2eEnvironment.CONTROL_URL, "sa", "")) {
            c.createStatement().execute("insert into tenants values ('" + tenant + "', 'PENDING_DELETION')");
            grant(c, tenant, "op-d", com.idp.security.Roles.OPERADOR);
            grant(c, tenant, "aud-d1", com.idp.security.Roles.AUDITOR);
            grant(c, tenant, "aud-d2", com.idp.security.Roles.AUDITOR);
        }
        String docPath = "/v1/documents/" + UUID.randomUUID();

        // PENDING_DELETION: el operador pierde acceso; el auditor (RN-14) lo conserva
        assertThat(get(env.documentPort, docPath, env.bearer(tenant, "op-d"))).isEqualTo(403);
        assertThat(get(env.auditPort, "/v1/audit/verify", env.bearer(tenant, "aud-d1"))).isNotEqualTo(403)
            .isNotEqualTo(401);

        // DELETED: tampoco el auditor
        try (Connection c = DriverManager.getConnection(E2eEnvironment.CONTROL_URL, "sa", "")) {
            c.createStatement().execute("update tenants set status = 'DELETED' where id = '" + tenant + "'");
        }
        assertThat(get(env.auditPort, "/v1/audit/verify", env.bearer(tenant, "aud-d2"))).isEqualTo(403);
    }

    private static void grant(Connection c, String tenant, String user, String role) throws SQLException {
        c.createStatement().execute("insert into role_assignment (id, tenant_id, user_id, role) values ('"
            + UUID.randomUUID() + "', '" + tenant + "', '" + user + "', '" + role + "')");
    }
}
