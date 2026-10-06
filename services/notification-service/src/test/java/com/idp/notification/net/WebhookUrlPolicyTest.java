package com.idp.notification.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** AC-04, AC-10 y SEC-027: HTTPS obligatorio, allowlist por tenant, sin literales IP ni nombres internos. */
class WebhookUrlPolicyTest {

    private static final List<String> ALLOW = List.of("api.banco.com", "*.partner.co");
    private final WebhookUrlPolicy policy = new WebhookUrlPolicy(AddressPolicy.STRICT, false);

    private String codeOf(String url, List<String> allow) {
        try {
            policy.validate(url, allow);
            return "OK";
        } catch (SsrfViolationException e) {
            return e.code();
        }
    }

    @Test
    void ac10_aceptaHttpsConHostEnLaAllowlist() {
        assertThat(policy.validate("https://api.banco.com/hooks/idp", ALLOW).getHost()).isEqualTo("api.banco.com");
        assertThat(codeOf("https://API.BANCO.COM:443/x", ALLOW)).isEqualTo("OK");
        assertThat(codeOf("https://api.banco.com./x", ALLOW)).isEqualTo("OK");
    }

    @Test
    void ac10_sinAllowlistNoSeAdmiteNingunDestino() {
        assertThat(codeOf("https://api.banco.com/x", List.of())).isEqualTo("HOST_NOT_ALLOWED");
        assertThat(codeOf("https://api.banco.com/x", null)).isEqualTo("HOST_NOT_ALLOWED");
    }

    @Test
    void ac10_hostFueraDeLaAllowlistSeRechaza() {
        assertThat(codeOf("https://otro.com/x", ALLOW)).isEqualTo("HOST_NOT_ALLOWED");
        assertThat(codeOf("https://api.banco.com.evil.com/x", ALLOW)).isEqualTo("HOST_NOT_ALLOWED");
        assertThat(codeOf("https://evilapi.banco.com/x", List.of("api.banco.com"))).isEqualTo("HOST_NOT_ALLOWED");
    }

    @Test
    void ac10_comodinCubreSubdominiosPeroNoElApexNiSufijosParecidos() {
        assertThat(codeOf("https://a.partner.co/x", ALLOW)).isEqualTo("OK");
        assertThat(codeOf("https://a.b.partner.co/x", ALLOW)).isEqualTo("OK");
        assertThat(codeOf("https://partner.co/x", ALLOW)).isEqualTo("HOST_NOT_ALLOWED");
        assertThat(codeOf("https://evilpartner.co/x", ALLOW)).isEqualTo("HOST_NOT_ALLOWED");
    }

    @Test
    void ac10_httpPlanoSeRechazaSalvoDesarrollo() {
        assertThat(codeOf("http://api.banco.com/x", ALLOW)).isEqualTo("INSECURE_SCHEME");
        assertThat(new WebhookUrlPolicy(AddressPolicy.STRICT, true, new PortPolicy(List.of(80), false))
            .validate("http://api.banco.com/x", ALLOW)).isNotNull();
        // Incluso en desarrollo solo se admite http y https.
        assertThat(codeOf("ftp://api.banco.com/x", ALLOW)).isEqualTo("INSECURE_SCHEME");
    }

    @Test
    void ac04_metadataCloudEnUrlHttpSeBloqueaAntesDeCualquierOtraRegla() {
        assertThat(codeOf("http://169.254.169.254/latest/meta-data/", ALLOW)).isEqualTo("BLOCKED_ADDRESS");
        assertThat(codeOf("http://169.254.169.254/latest/meta-data/", List.of("169.254.169.254")))
            .isEqualTo("BLOCKED_ADDRESS");
        assertThatThrownBy(() -> new WebhookUrlPolicy(AddressPolicy.STRICT, true)
            .validate("http://169.254.169.254/latest/meta-data/", List.of("169.254.169.254")))
            .isInstanceOf(SsrfViolationException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        https://10.0.5.5/hook | BLOCKED_ADDRESS
        https://172.16.0.1/hook | BLOCKED_ADDRESS
        https://192.168.1.1/hook | BLOCKED_ADDRESS
        https://127.0.0.1/hook | BLOCKED_ADDRESS
        https://100.64.0.1/hook | BLOCKED_ADDRESS
        https://0.0.0.0/hook | BLOCKED_ADDRESS
        https://[::1]/hook | BLOCKED_ADDRESS
        https://[fd00:ec2::254]/hook | BLOCKED_ADDRESS
        https://[fe80::1]/hook | BLOCKED_ADDRESS
        https://[::ffff:10.0.0.1]/hook | BLOCKED_ADDRESS
        https://[64:ff9b::a00:1]/hook | BLOCKED_ADDRESS
        https://2130706433/hook | BLOCKED_ADDRESS
        https://0x7f.1/hook | INVALID_URL
        https://0177.0.0.1/hook | BLOCKED_ADDRESS
        https://127.1/hook | INVALID_URL
        https://localhost/hook | BLOCKED_HOSTNAME
        https://LOCALHOST./hook | BLOCKED_HOSTNAME
        https://api.localhost/hook | BLOCKED_HOSTNAME
        https://intranet.local/hook | BLOCKED_HOSTNAME
        https://db.internal/hook | BLOCKED_HOSTNAME
        https://metadata.google.internal/hook | BLOCKED_HOSTNAME
        https://metadata/hook | BLOCKED_HOSTNAME
        https://kubernetes/hook | BLOCKED_HOSTNAME
        https://svc.default.svc/hook | BLOCKED_HOSTNAME
        https://x.svc.cluster.local/hook | BLOCKED_HOSTNAME
        """)
    void bloqueaLiteralesIpFormasNumericasYNombresInternosAunEnLaAllowlist(String url, String code) {
        List<String> everything = List.of("10.0.5.5", "172.16.0.1", "192.168.1.1", "127.0.0.1", "100.64.0.1", "0.0.0.0",
            "localhost", "api.localhost", "intranet.local", "db.internal", "metadata.google.internal", "metadata",
            "kubernetes", "*.svc", "*.cluster.local", "*.local", "*.internal", "[::1]", "2130706433");
        assertThat(codeOf(url, everything)).isEqualTo(code);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        https://user:pass@api.banco.com/x | INVALID_URL
        https://api.banco.com/x#frag | INVALID_URL
        file:///etc/passwd | INVALID_URL
        javascript:alert(1) | INVALID_URL
        /relativa/sin/host | INVALID_URL
        https:///sin-host | INVALID_URL
        https://api banco.com/x | INVALID_URL
        """)
    void rechazaUrlsMalFormadasOConCredencialesEmbebidas(String url, String code) {
        assertThat(codeOf(url, ALLOW)).isEqualTo(code);
    }

    @Test
    void rechazaUrlNulaVaciaOExcesivamenteLarga() {
        assertThat(codeOf(null, ALLOW)).isEqualTo("INVALID_URL");
        assertThat(codeOf("  ", ALLOW)).isEqualTo("INVALID_URL");
        assertThat(codeOf("https://api.banco.com/" + "a".repeat(2100), ALLOW)).isEqualTo("INVALID_URL");
        assertThatThrownBy(() -> policy.validate("https://[::1]/x", ALLOW)).isInstanceOf(SsrfViolationException.class);
    }

    // ---- hallazgo 2: puertos ----

    @Test
    void soloElPuerto443PorDefectoYRechazaServiciosInternosComoRedis() {
        assertThat(codeOf("https://api.banco.com:6379/", ALLOW)).isEqualTo("PORT_NOT_ALLOWED");
        assertThat(codeOf("https://api.banco.com:8443/x", ALLOW)).isEqualTo("PORT_NOT_ALLOWED");
        assertThat(codeOf("https://api.banco.com:5432/x", ALLOW)).isEqualTo("PORT_NOT_ALLOWED");
        assertThat(codeOf("https://api.banco.com:443/x", ALLOW)).isEqualTo("OK");
        assertThat(codeOf("https://api.banco.com/x", ALLOW)).isEqualTo("OK");
    }

    @Test
    void otrosPuertosSoloPorListaExplicitaDePlataforma() {
        WebhookUrlPolicy platform = new WebhookUrlPolicy(AddressPolicy.STRICT, false,
            new PortPolicy(List.of(8443), false));
        assertThat(platform.validate("https://api.banco.com:8443/x", ALLOW)).isNotNull();
        assertThatThrownBy(() -> platform.validate("https://api.banco.com:6379/x", ALLOW))
            .isInstanceOfSatisfying(SsrfViolationException.class, e -> assertThat(e.code()).isEqualTo("PORT_NOT_ALLOWED"));
    }

    @Test
    void enDevModeSeAdmitenPuertosSinPrivilegioParaLosReceptoresDePrueba() {
        WebhookUrlPolicy dev = new WebhookUrlPolicy(AddressPolicy.STRICT, false, new PortPolicy(List.of(), true));
        assertThat(dev.validate("https://api.banco.com:54321/x", ALLOW)).isNotNull();
        assertThatThrownBy(() -> dev.validate("https://api.banco.com:80/x", ALLOW))
            .isInstanceOfSatisfying(SsrfViolationException.class, e -> assertThat(e.code()).isEqualTo("PORT_NOT_ALLOWED"));
    }

    @Test
    void puertoFueraDeRangoEnLaListaDePlataformaEsInvalido() {
        assertThatThrownBy(() -> new PortPolicy(List.of(70000), false)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- hallazgo 6: query y credenciales ----

    @Test
    void rechazaQueryStringYCredencialesEmbebidas() {
        assertThat(codeOf("https://api.banco.com/hook?token=abc", ALLOW)).isEqualTo("INVALID_URL");
        assertThat(codeOf("https://api.banco.com/hook?", ALLOW)).isEqualTo("INVALID_URL");
        assertThat(codeOf("https://user:pass@api.banco.com/hook", ALLOW)).isEqualTo("INVALID_URL");
        assertThat(codeOf("https://user@api.banco.com/hook", ALLOW)).isEqualTo("INVALID_URL");
        assertThat(codeOf("https://api.banco.com/hook", ALLOW)).isEqualTo("OK");
    }

    // ---- hallazgo 1: comodines sobre sufijos publicos ya guardados no cubren nada ----

    @Test
    void unComodinSobreSufijoPublicoNuncaCoincide() {
        assertThat(codeOf("https://api.banco.com/x", List.of("*.com"))).isEqualTo("HOST_NOT_ALLOWED");
        assertThat(codeOf("https://banco.co.uk/x", List.of("*.co.uk"))).isEqualTo("HOST_NOT_ALLOWED");
        assertThat(codeOf("https://api.banco.com/x", List.of("*.banco.com"))).isEqualTo("OK");
    }
}
