package com.idp.notification.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.notification.support.FakeHostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SEC-027: DNS pinning, DNS rebinding con resolver falso, registros mixtos y literales IP. */
class SsrfGuardTest {

    private final FakeHostResolver dns = new FakeHostResolver();
    private final SsrfGuard guard = new SsrfGuard(dns, AddressPolicy.STRICT);

    @Test
    void ac03_hostQueResuelveAIpPrivadaEsBloqueado() {
        dns.map("api.banco.com", "10.0.5.5");
        assertThatThrownBy(() -> guard.resolve("api.banco.com"))
            .isInstanceOfSatisfying(SsrfViolationException.class, e -> assertThat(e.code()).isEqualTo("BLOCKED_ADDRESS"));
    }

    @Test
    void ac04_hostQueResuelveAMetadataCloudEsBloqueado() {
        dns.map("evil.example.com", "169.254.169.254");
        assertThatThrownBy(() -> guard.resolve("evil.example.com")).isInstanceOf(SsrfViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "100.64.1.1", "172.16.0.9", "192.168.1.1", "::1", "fd00:ec2::254", "fe80::1",
        "64:ff9b::a00:1"})
    void bloqueaCualquierRangoPrivadoResueltoPorDns(String ip) {
        dns.map("h.example.com", ip);
        assertThatThrownBy(() -> guard.resolve("h.example.com")).isInstanceOf(SsrfViolationException.class);
    }

    @Test
    void ipPublicaSeDevuelveFijadaYSinConsultasAdicionales() throws Exception {
        dns.map("api.banco.com", "93.184.216.34");
        InetAddress[] pinned = guard.resolve("api.banco.com");
        assertThat(pinned).hasSize(1);
        assertThat(pinned[0].getHostAddress()).isEqualTo("93.184.216.34");
        assertThat(dns.calls("api.banco.com")).isEqualTo(1);
    }

    @Test
    void registrosMixtosPublicaYPrivadaSeRechazanCompletos() {
        dns.map("mixto.example.com", "93.184.216.34", "10.0.0.5");
        assertThatThrownBy(() -> guard.resolve("mixto.example.com")).isInstanceOf(SsrfViolationException.class);
        dns.map("mixto2.example.com", "10.0.0.5", "93.184.216.34");
        assertThatThrownBy(() -> guard.resolve("mixto2.example.com")).isInstanceOf(SsrfViolationException.class);
    }

    @Test
    void dnsRebindingLaSegundaResolucionPrivadaEsBloqueada() throws Exception {
        dns.sequence("rebind.example.com", new String[] {"93.184.216.34"}, new String[] {"10.0.0.5"},
            new String[] {"169.254.169.254"});
        assertThat(guard.resolve("rebind.example.com")[0].getHostAddress()).isEqualTo("93.184.216.34");
        // Cada conexion vuelve a validar: el cambio de respuesta DNS (TTL 0) no permite llegar a la red privada.
        assertThatThrownBy(() -> guard.resolve("rebind.example.com")).isInstanceOf(SsrfViolationException.class);
        assertThatThrownBy(() -> guard.resolve("rebind.example.com")).isInstanceOf(SsrfViolationException.class);
        assertThat(dns.calls("rebind.example.com")).isEqualTo(3);
    }

    @Test
    void dnsRebindingInversoPrivadaPrimeroTambienSeBloquea() {
        dns.sequence("rebind2.example.com", new String[] {"10.0.0.5"}, new String[] {"93.184.216.34"});
        assertThatThrownBy(() -> guard.resolve("rebind2.example.com")).isInstanceOf(SsrfViolationException.class);
    }

    @Test
    void hostSinRegistrosOInexistenteNoEsSsrfSinoFalloDeResolucion() {
        assertThatThrownBy(() -> guard.resolve("no-existe.example.com")).isInstanceOf(UnknownHostException.class);
        FakeHostResolver empty = new FakeHostResolver();
        SsrfGuard g = new SsrfGuard(host -> new InetAddress[0], AddressPolicy.STRICT);
        assertThatThrownBy(() -> g.resolve("vacio.example.com")).isInstanceOf(UnknownHostException.class);
        assertThat(empty.calls("x")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "169.254.169.254", "10.1.2.3", "192.168.0.10", "100.64.0.1", "::1",
        "fd00:ec2::254", "2130706433", "127.1", "0.0.0.0"})
    void literalesIpYFormasNumericasNoLlegaranAConectar(String literal) {
        SsrfGuard system = new SsrfGuard(HostResolver.system(), AddressPolicy.STRICT);
        // El resolvedor del sistema interpreta el literal (sin DNS) y la politica lo bloquea; si no lo interpreta,
        // falla la resolucion. En ningun caso devuelve una direccion utilizable.
        assertThatThrownBy(() -> system.resolve(literal))
            .isInstanceOfAny(SsrfViolationException.class, UnknownHostException.class);
    }

    @Test
    void resolucionCanonicaNoConsultaDns() {
        assertThat(guard.resolveCanonicalHostname("api.banco.com")).isEqualTo("api.banco.com");
        assertThat(dns.calls("api.banco.com")).isZero();
    }
}
