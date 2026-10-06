package com.idp.notification.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.notification.net.AddressPolicy;
import com.idp.notification.net.SsrfGuard;
import com.idp.notification.net.SsrfViolationException;
import com.idp.notification.support.FakeHostResolver;
import com.idp.notification.support.TestReceiver;
import com.idp.notification.support.TestReceiver.Reply;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Transporte real (Apache HttpClient) contra un receptor loopback, con DNS falso y una politica que solo exceptua
 * loopback: demuestra que la IP resuelta es la que se usa, que cada conexion revalida y que no hay redirects.
 */
class ApacheWebhookTransportTest {

    private static final byte[] BODY = "{\"id\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
    private final FakeHostResolver dns = new FakeHostResolver();
    private final AddressPolicy loopbackOnly = a -> !a.isLoopbackAddress() && AddressPolicy.STRICT.isBlocked(a);
    private TestReceiver receiver;
    private ApacheWebhookTransport transport;

    @BeforeEach
    void start() {
        receiver = new TestReceiver();
        transport = new ApacheWebhookTransport(new SsrfGuard(dns, loopbackOnly), Duration.ofSeconds(2),
            Duration.ofMillis(600), Duration.ofSeconds(3));
    }

    @AfterEach
    void stop() throws Exception {
        transport.close();
        receiver.close();
    }

    private URI url(String host, String path) {
        return URI.create("http://" + host + ":" + receiver.port() + path);
    }

    @Test
    void ac05_enviaCuerpoYCabecerasTalCualYConectaALaIpResuelta() throws Exception {
        dns.map("hook.banco.test", "127.0.0.1");
        int status = transport.post(url("hook.banco.test", "/idp"), BODY,
            Map.of("X-Hub-Timestamp", "1700000000", "X-Hub-Signature-256", "sha256=abc", "Content-Type", "application/json"));
        assertThat(status).isEqualTo(200);
        assertThat(receiver.received()).hasSize(1);
        TestReceiver.Received r = receiver.received().get(0);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/idp");
        assertThat(r.bodyText()).isEqualTo("{\"id\":\"abc\"}");
        assertThat(r.header("X-Hub-Timestamp")).isEqualTo("1700000000");
        assertThat(r.header("X-Hub-Signature-256")).isEqualTo("sha256=abc");
        // El nombre solo se resolvio una vez y el Host enviado es el original (virtual host del receptor).
        assertThat(dns.calls("hook.banco.test")).isEqualTo(1);
        assertThat(r.header("Host")).isEqualTo("hook.banco.test:" + receiver.port());
        assertThat(r.header("User-Agent")).startsWith("IDP-Webhook");
    }

    @Test
    void ac14_cadaEnvioVuelveAResolverYValidar() throws Exception {
        dns.map("hook.banco.test", "127.0.0.1");
        transport.post(url("hook.banco.test", "/a"), BODY, Map.of());
        transport.post(url("hook.banco.test", "/b"), BODY, Map.of());
        assertThat(dns.calls("hook.banco.test")).isEqualTo(2);
        assertThat(receiver.count()).isEqualTo(2);
    }

    @Test
    void ac14_dnsRebindingLaSegundaConexionAIpPrivadaNuncaLlegaAlReceptor() throws Exception {
        dns.sequence("rebind.banco.test", new String[] {"127.0.0.1"}, new String[] {"10.0.0.5"},
            new String[] {"169.254.169.254"});
        assertThat(transport.post(url("rebind.banco.test", "/ok"), BODY, Map.of())).isEqualTo(200);
        assertThat(receiver.count()).isEqualTo(1);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> transport.post(url("rebind.banco.test", "/rebind"), BODY, Map.of()))
                .isInstanceOf(SsrfViolationException.class);
        }
        assertThat(receiver.count()).isEqualTo(1);
    }

    @Test
    void ac03_nombreQueResuelveARangoPrivadoNoAbreConexion() {
        for (String ip : new String[] {"10.0.5.5", "172.16.0.1", "192.168.1.1", "100.64.0.1", "169.254.169.254", "fd00::1",
            "fe80::1"}) {
            dns.map("priv.banco.test", ip);
            assertThatThrownBy(() -> transport.post(url("priv.banco.test", "/x"), BODY, Map.of()))
                .as(ip).isInstanceOf(SsrfViolationException.class);
        }
        assertThat(receiver.count()).isZero();
    }

    @Test
    void ac14_registrosMixtosLoopbackPermitidoMasPrivadaSeRechazanSinConectar() {
        dns.map("mixto.banco.test", "127.0.0.1", "10.0.0.5");
        assertThatThrownBy(() -> transport.post(url("mixto.banco.test", "/x"), BODY, Map.of()))
            .isInstanceOf(SsrfViolationException.class);
        assertThat(receiver.count()).isZero();
    }

    @Test
    void ac05_redirectsNoSeSiguenYSeDevuelveElCodigo3xx() throws Exception {
        dns.map("hook.banco.test", "127.0.0.1");
        receiver.reply(Reply.redirect("/otra-ruta"));
        assertThat(transport.post(url("hook.banco.test", "/origen"), BODY, Map.of("X-Hub-Signature-256", "sha256=x")))
            .isEqualTo(302);
        assertThat(receiver.received()).extracting(TestReceiver.Received::path).containsExactly("/origen");
    }

    @Test
    void ac14_redirectAMetadataCloudNoSeSigue() throws Exception {
        dns.map("hook.banco.test", "127.0.0.1");
        receiver.reply(Reply.redirect("http://169.254.169.254/latest/meta-data/"));
        assertThat(transport.post(url("hook.banco.test", "/origen"), BODY, Map.of())).isEqualTo(302);
        assertThat(receiver.count()).isEqualTo(1);
        // Un redirect 307/308 (que conserva metodo y cuerpo) tampoco se sigue.
        receiver.reply(new Reply(307, Map.of("Location", "http://10.0.0.5/x"), 0));
        assertThat(transport.post(url("hook.banco.test", "/origen2"), BODY, Map.of())).isEqualTo(307);
        assertThat(receiver.received()).extracting(TestReceiver.Received::path).containsExactly("/origen", "/origen2");
    }

    @Test
    void timeoutDeLecturaSeReportaComoInterrupcionDeIo() {
        dns.map("hook.banco.test", "127.0.0.1");
        receiver.reply(Reply.delayed(200, 2500));
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> transport.post(url("hook.banco.test", "/lento"), BODY, Map.of()))
            .isInstanceOf(InterruptedIOException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void plazoTotalCortaRespuestasQueNuncaTerminan() throws Exception {
        dns.map("hook.banco.test", "127.0.0.1");
        try (ApacheWebhookTransport slowRead = new ApacheWebhookTransport(new SsrfGuard(dns, loopbackOnly),
            Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofMillis(400))) {
            receiver.reply(Reply.delayed(200, 5000));
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> slowRead.post(url("hook.banco.test", "/tarpit"), BODY, Map.of()))
                .isInstanceOf(IOException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(3));
        }
    }

    @Test
    void conexionRechazadaEsErrorDeRedNoDeSsrf() throws Exception {
        dns.map("hook.banco.test", "127.0.0.1");
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        assertThatThrownBy(() -> transport.post(URI.create("http://hook.banco.test:" + closedPort + "/x"), BODY, Map.of()))
            .isInstanceOf(IOException.class).isNotInstanceOf(InterruptedIOException.class);
    }

    @Test
    void hostDesconocidoFallaComoErrorDeRed() {
        assertThatThrownBy(() -> transport.post(url("desconocido.banco.test", "/x"), BODY, Map.of()))
            .isInstanceOf(IOException.class);
    }
}
