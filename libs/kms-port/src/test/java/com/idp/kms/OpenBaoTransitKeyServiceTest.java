package com.idp.kms;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenBaoTransitKeyServiceTest {

    private static final String BASE = "http://openbao:8200/v1/transit";

    private MockRestServiceServer server;
    private OpenBaoTransitKeyService kms;
    private final TenantId tenant = new TenantId("t1");
    private final Map<String, String> aad = Map.of("doc", "d-1");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        kms = new OpenBaoTransitKeyService(builder, "http://openbao:8200", () -> "tok", "transit", true, null);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void wrapDekUsaEncryptConKekDelTenantYContextoAad() {
        server.expect(requestTo(BASE + "/encrypt/t-t1-datos"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Vault-Token", "tok"))
            .andExpect(jsonPath("$.plaintext").value(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})))
            .andExpect(jsonPath("$.context").exists())
            .andRespond(withSuccess("{\"data\":{\"ciphertext\":\"vault:v1:abc\"}}", MediaType.APPLICATION_JSON));

        byte[] wrapped = kms.wrapDek(tenant, new byte[] {1, 2, 3}, "datos", aad).getData();

        assertEquals("vault:v1:abc", new String(wrapped, StandardCharsets.UTF_8));
        server.verify();
    }

    @Test
    void unwrapDekDevuelveLaDekEnClaro() {
        server.expect(requestTo(BASE + "/decrypt/t-t1-datos"))
            .andExpect(jsonPath("$.ciphertext").value("vault:v1:abc"))
            .andRespond(withSuccess("{\"data\":{\"plaintext\":\"" + Base64.getEncoder().encodeToString(new byte[] {9, 8})
                + "\"}}", MediaType.APPLICATION_JSON));

        byte[] dek = kms.unwrapDek(tenant, "vault:v1:abc".getBytes(StandardCharsets.UTF_8), "datos", aad).getData();

        assertArrayEquals(new byte[] {9, 8}, dek);
    }

    @Test
    void signYVerifyUsanLaLlaveDeFirmaIndependiente() {
        server.expect(requestTo(BASE + "/sign/t-t1-firma"))
            .andExpect(jsonPath("$.input").value(b64("doc")))
            .andRespond(withSuccess("{\"data\":{\"signature\":\"vault:v1:sig\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/verify/t-t1-firma"))
            .andExpect(jsonPath("$.signature").value("vault:v1:sig"))
            .andRespond(withSuccess("{\"data\":{\"valid\":true}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/verify/t-t1-firma"))
            .andRespond(withSuccess("{\"data\":{\"valid\":false}}", MediaType.APPLICATION_JSON));

        byte[] sig = kms.sign(tenant, "doc".getBytes(StandardCharsets.UTF_8), "firma").getData();

        assertTrue(kms.verify(tenant, "doc".getBytes(StandardCharsets.UTF_8), sig, "firma"));
        assertFalse(kms.verify(tenant, "otro".getBytes(StandardCharsets.UTF_8), sig, "firma"));
        server.verify();
    }

    @Test
    void ac04_disableKekDestruyeLaLlaveYBloqueaUsosPosteriores() {
        server.expect(requestTo(BASE + "/keys/t-t1-datos/config"))
            .andExpect(content().json("{\"deletion_allowed\":true}"))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(BASE + "/keys/t-t1-datos"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NO_CONTENT));

        kms.disableKek(tenant, "datos");

        assertThrows(KeyService.KeyDisabledException.class,
            () -> kms.wrapDek(tenant, new byte[] {1}, "datos", aad));
        server.verify();
    }

    @Test
    void ac06_fallaDelProveedorSeReportaComoNoDisponible() {
        server.expect(requestTo(BASE + "/encrypt/t-t1-datos")).andRespond(withServerError());
        assertThrows(KeyServiceUnavailableException.class,
            () -> kms.wrapDek(tenant, new byte[] {1}, "datos", aad));
    }

    @Test
    void llaveInexistenteSeReportaComoNoEncontrada() {
        server.expect(requestTo(BASE + "/decrypt/t-t1-datos")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThrows(KeyService.KeyNotFoundException.class,
            () -> kms.unwrapDek(tenant, "vault:v1:x".getBytes(StandardCharsets.UTF_8), "datos", aad));
    }

    @Test
    void respuestaSinDatosSeTrataComoProveedorNoDisponible() {
        server.expect(requestTo(BASE + "/encrypt/t-t1-datos"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThrows(KeyServiceUnavailableException.class,
            () -> kms.wrapDek(tenant, new byte[] {1}, "datos", aad));
    }

    @Test
    void identificadoresConCaracteresDePathSeRechazanSinLlamarAlKms() {
        assertThrows(IllegalArgumentException.class,
            () -> kms.wrapDek(tenant, new byte[] {1}, "../x", aad));
    }

    @Test
    void exigeHttpsSiNoEsDevMode() {
        RestClient.Builder builder = RestClient.builder();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new OpenBaoTransitKeyService(builder, "http://openbao:8200", () -> "tok", "transit", false, null));
        assertEquals("HTTPS es obligatorio para OpenBao salvo en dev-mode", ex.getMessage());
    }
}
