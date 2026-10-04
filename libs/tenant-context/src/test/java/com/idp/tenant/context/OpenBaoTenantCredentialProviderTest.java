package com.idp.tenant.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenBaoTenantCredentialProviderTest {

    private MockRestServiceServer server;
    private OpenBaoTenantCredentialProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new OpenBaoTenantCredentialProvider(builder, "http://openbao:8200", () -> "tok",
            "database/creds/tenant-{tenant}", "jdbc:postgresql://db:5432/idp_{tenant}");
    }

    @Test
    void resuelveCredencialesDinamicasDelTenant() {
        server.expect(requestTo("http://openbao:8200/v1/database/creds/tenant-t1"))
            .andExpect(header("X-Vault-Token", "tok"))
            .andRespond(withSuccess("{\"data\":{\"username\":\"v-t1-abc\",\"password\":\"pw\"}}",
                MediaType.APPLICATION_JSON));

        TenantConnection c = provider.resolve("t1");

        assertEquals("jdbc:postgresql://db:5432/idp_t1", c.jdbcUrl());
        assertEquals("v-t1-abc", c.username());
        assertEquals("pw", c.password());
        server.verify();
    }

    @Test
    void ac08_tenantInexistenteOSuspendidoSeReportaComoNoDisponible() {
        server.expect(requestTo("http://openbao:8200/v1/database/creds/tenant-t9"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThrows(TenantNotAvailableException.class, () -> provider.resolve("t9"));
    }

    @Test
    void errorDeServidorSeReportaComoNoDisponible() {
        server.expect(requestTo("http://openbao:8200/v1/database/creds/tenant-t1"))
            .andRespond(withServerError());
        assertThrows(TenantNotAvailableException.class, () -> provider.resolve("t1"));
    }

    @Test
    void respuestaSinCredencialesSeRechaza() {
        server.expect(requestTo("http://openbao:8200/v1/database/creds/tenant-t1"))
            .andRespond(withSuccess("{\"data\":{}}", MediaType.APPLICATION_JSON));
        assertThrows(TenantNotAvailableException.class, () -> provider.resolve("t1"));
    }

    @Test
    void tenantIdConCaracteresPeligrososNoLlegaAOpenBao() {
        assertThrows(TenantNotAvailableException.class, () -> provider.resolve("../secret"));
        assertThrows(TenantNotAvailableException.class, () -> provider.resolve(null));
        server.verify();
    }
}
