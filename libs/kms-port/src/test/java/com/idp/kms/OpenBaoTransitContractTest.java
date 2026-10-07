package com.idp.kms;

import com.idp.kms.contract.KeyServiceContract;
import com.idp.tenant.TenantId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** KeyServiceContract contra un OpenBao real en modo dev con el motor transit montado. */
@Testcontainers(disabledWithoutDocker = true)
class OpenBaoTransitContractTest extends KeyServiceContract {

    private static final String TOKEN = "root";

    @Container
    static final GenericContainer<?> OPENBAO = new GenericContainer<>("openbao/openbao:2.4.1")
        .withEnv("BAO_DEV_ROOT_TOKEN_ID", TOKEN)
        .withEnv("BAO_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
        .withCommand("server", "-dev")
        .withExposedPorts(8200)
        .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200));

    private static String address;
    private static OpenBaoTransitKeyService kms;

    @BeforeAll
    static void montarTransit() throws Exception {
        address = "http://" + OPENBAO.getHost() + ":" + OPENBAO.getMappedPort(8200);
        llamar("POST", "/v1/sys/mounts/transit", "{\"type\":\"transit\"}");
        kms = new OpenBaoTransitKeyService(RestClient.builder(), address, () -> TOKEN, "transit", true, null);
    }

    private static void llamar(String metodo, String ruta, String cuerpo) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(address + ruta))
            .header("X-Vault-Token", TOKEN).header("Content-Type", "application/json")
            .method(metodo, HttpRequest.BodyPublishers.ofString(cuerpo)).build();
        HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException(metodo + " " + ruta + " -> " + res.statusCode() + " " + res.body());
        }
    }

    @Override
    protected KeyService getKms() {
        return kms;
    }

    @Override
    protected TenantId getTenantA() {
        return new TenantId("t1");
    }

    @Override
    protected TenantId getTenantB() {
        return new TenantId("t2");
    }

    @Override
    protected void createKeyIfNeeded(TenantId tenant, String keyId) {
        String nombre = "t-" + tenant.value() + "-" + keyId;
        String tipo = keyId.equals(SIGNING_KEY) ? "ed25519" : "aes256-gcm96";
        try {
            llamar("POST", "/v1/transit/keys/" + nombre, "{\"type\":\"" + tipo + "\",\"derived\":"
                + !tipo.equals("ed25519") + "}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
