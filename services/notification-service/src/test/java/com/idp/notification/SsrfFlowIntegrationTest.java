package com.idp.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.notification.support.TestReceiver.Reply;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * SEC-027 de extremo a extremo (evento -> entrega -> transporte real con DNS falso): AC-03, AC-04 y AC-14.
 * La politica de direcciones de pruebas solo exceptua loopback; todo lo demas usa la politica estricta.
 */
@ExtendWith(OutputCaptureExtension.class)
class SsrfFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String PRIVATE_HOST = "api.banco.test";

    @Autowired MeterRegistry meters;

    private String tenantFor(String host) throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, host);
        createWebhook(tenant, "http://" + host + ":" + receiver.port() + "/webhook", "extraccion.aprobada");
        return tenant;
    }

    private String status(String tenant) {
        return (String) deliveries(tenant).get(0).get("status");
    }

    @Test
    void ac03_hostQueResuelveA10_0_5_5SeBloqueaMarcaFallidoYRegistraAlertaDeSeguridad(CapturedOutput output)
            throws Exception {
        dns.map(PRIVATE_HOST, "10.0.5.5");
        String tenant = tenantFor(PRIVATE_HOST);
        listener.onMessage(aprobada(tenant, UUID.randomUUID().toString()));

        assertThat(runWorker(tenant)).isEqualTo(1);

        assertThat(receiver.count()).isZero();
        assertThat(status(tenant)).isEqualTo("FALLIDO");
        // Definitivo: un bloqueo SSRF no se reintenta.
        assertThat(deliveries(tenant).get(0).get("attempts")).isEqualTo(1);
        assertThat(deliveries(tenant).get(0).get("error_code")).isEqualTo("BLOCKED_ADDRESS");
        JsonNode failed = outbox(tenant, "webhook.fallido").get(0);
        assertThat(failed.path("reasonCode").asText()).isEqualTo("SSRF_BLOCKED");
        assertThat(failed.path("attempts").asInt()).isEqualTo(1);
        assertThat(meters.find("webhook.delivery.failure").tag("tenant", tenant).tag("cause", "ssrf_blocked").counter()
            .count()).isEqualTo(1);
        assertThat(meters.find("webhook.ssrf.blocked").tag("tenant", tenant).counter().count()).isEqualTo(1);
        assertThat(output.getAll()).contains("SECURITY_ALERT").contains(tenant).doesNotContain("10.0.5.5");
        assertThat(dns.calls(PRIVATE_HOST)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.1", "172.16.5.5", "172.31.255.255", "192.168.1.1", "169.254.169.254", "169.254.1.1",
        "100.64.0.1", "100.100.100.200", "0.0.0.0", "224.0.0.1", "::", "fc00::1", "fd00:ec2::254", "fe80::1",
        "ff02::1", "2001:db8::1", "64:ff9b::a00:1", "2002:a9fe:a9fe::1"})
    void ac14_ningunRangoPrivadoLinkLocalCgnatOIpv6LlegaAAbrirConexion(String ip) throws Exception {
        dns.map(PRIVATE_HOST, ip);
        String tenant = tenantFor(PRIVATE_HOST);
        listener.onMessage(aprobada(tenant, UUID.randomUUID().toString()));
        runWorker(tenant);
        assertThat(status(tenant)).as(ip).isEqualTo("FALLIDO");
        assertThat(deliveries(tenant).get(0).get("error_code")).as(ip).isEqualTo("BLOCKED_ADDRESS");
        assertThat(receiver.count()).isZero();
    }

    @Test
    void ac14_registrosMixtosConUnaIpPrivadaSeRechazanCompletos() throws Exception {
        dns.map(PRIVATE_HOST, "127.0.0.1", "10.0.0.5");
        String tenant = tenantFor(PRIVATE_HOST);
        listener.onMessage(aprobada(tenant, UUID.randomUUID().toString()));
        runWorker(tenant);
        assertThat(status(tenant)).isEqualTo("FALLIDO");
        assertThat(receiver.count()).isZero();
    }

    @Test
    void ac04_urlAlmacenadaConMetadataCloudAbortaSinResolverNiEmitirSolicitud() throws Exception {
        String tenant = newTenant();
        allowHosts(tenant, HOST, "169.254.169.254");
        createWebhook(tenant, hookUrl(), "extraccion.aprobada");
        // Dato preexistente (el alta ya lo impide): la revalidacion al despachar lo detiene igualmente.
        inTenant(tenant, () -> jdbc.update("update webhook_subscription set url = ?",
            "http://169.254.169.254/latest/meta-data/"));
        listener.onMessage(aprobada(tenant, UUID.randomUUID().toString()));
        runWorker(tenant);
        assertThat(status(tenant)).isEqualTo("FALLIDO");
        assertThat(deliveries(tenant).get(0).get("error_code")).isEqualTo("BLOCKED_ADDRESS");
        assertThat(outbox(tenant, "webhook.fallido").get(0).path("reasonCode").asText()).isEqualTo("SSRF_BLOCKED");
        assertThat(receiver.count()).isZero();
        assertThat(dns.calls("169.254.169.254")).isZero();
    }

    @Test
    void ac14_dnsRebindingLaPrimeraEntregaVaALaIpPublicaYLaSiguienteQueResuelveAPrivadaSeBloquea() throws Exception {
        // 127.0.0.1 hace de "IP publica" permitida por la politica de pruebas; luego el DNS apunta a la red privada.
        dns.sequence(PRIVATE_HOST, new String[] {"127.0.0.1"}, new String[] {"10.0.0.5"});
        String tenant = tenantFor(PRIVATE_HOST);
        listener.onMessage(aprobada(tenant, UUID.randomUUID().toString()));
        runWorker(tenant);
        listener.onMessage(aprobada(tenant, UUID.randomUUID().toString()));
        runWorker(tenant);

        var rows = deliveries(tenant);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("status").toString()).containsExactlyInAnyOrder("ENTREGADO", "FALLIDO");
        assertThat(receiver.count()).isEqualTo(1);
        assertThat(outbox(tenant, "webhook.fallido")).hasSize(1);
        assertThat(outbox(tenant, "webhook.fallido").get(0).path("reasonCode").asText()).isEqualTo("SSRF_BLOCKED");
    }

    @Test
    void ac14_cadaIntentoResuelveElNombreExactamenteUnaVez() throws Exception {
        dns.map(PRIVATE_HOST, "127.0.0.1");
        String tenant = tenantFor(PRIVATE_HOST);
        listener.onMessage(aprobada(tenant, UUID.randomUUID().toString()));
        runWorker(tenant);
        assertThat(status(tenant)).isEqualTo("ENTREGADO");
        assertThat(dns.calls(PRIVATE_HOST)).isEqualTo(1);
        assertThat(receiver.received().get(0).header("Host")).isEqualTo(PRIVATE_HOST + ":" + receiver.port());
    }

    @Test
    void ac14_unRedirectHaciaMetadataCloudNoSeSigueNiSeResuelve() throws Exception {
        dns.map(PRIVATE_HOST, "127.0.0.1");
        String tenant = tenantFor(PRIVATE_HOST);
        receiver.reply(Reply.redirect("http://169.254.169.254/latest/meta-data/"));
        listener.onMessage(aprobada(tenant, UUID.randomUUID().toString()));
        runWorker(tenant);
        assertThat(status(tenant)).isEqualTo("FALLIDO");
        assertThat(deliveries(tenant).get(0).get("last_http_status")).isEqualTo(302);
        assertThat(receiver.count()).isEqualTo(1);
        assertThat(dns.calls("169.254.169.254")).isZero();
    }

    @Test
    void unaFallaDeResolucionDnsNoEsSsrfSinoErrorReintentable() throws Exception {
        String tenant = tenantFor("sin-dns.banco.test");
        listener.onMessage(aprobada(tenant, UUID.randomUUID().toString()));
        runWorker(tenant);
        assertThat(status(tenant)).isEqualTo("PENDIENTE");
        assertThat(deliveries(tenant).get(0).get("error_code")).isEqualTo("UNREACHABLE");
        assertThat(outbox(tenant, "webhook.fallido")).isEmpty();
    }
}
