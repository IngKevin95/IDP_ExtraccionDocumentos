package com.idp.notification.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SEC-027 / AC-03 / AC-04: rangos prohibidos (RFC1918, loopback, link-local, CGNAT, IPv6) y limites de cada rango. */
class AddressPolicyTest {

    private static boolean blocked(String literal) throws Exception {
        return AddressPolicy.STRICT.isBlocked(InetAddress.getByName(literal));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // RFC1918
        "10.0.0.0", "10.0.5.5", "10.255.255.255", "172.16.0.1", "172.20.1.1", "172.31.255.255", "192.168.0.1",
        "192.168.255.255",
        // loopback
        "127.0.0.1", "127.255.255.254", "127.1.2.3",
        // link-local y metadata cloud (AWS, GCP, Azure)
        "169.254.169.254", "169.254.0.1", "169.254.255.255",
        // CGNAT 100.64/10 (incluye metadata de Alibaba 100.100.100.200)
        "100.64.0.0", "100.64.0.1", "100.100.100.200", "100.127.255.255",
        // "esta red", multicast, reservadas, broadcast, documentacion, benchmarking, IETF
        "0.0.0.0", "0.1.2.3", "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255", "192.0.0.8",
        "192.0.2.1", "198.51.100.7", "203.0.113.9", "198.18.0.1", "198.19.255.255", "192.88.99.1",
        // IPv6: loopback, no especificada, ULA (incluye metadata AWS fd00:ec2::254), link-local, site-local, multicast
        "::1", "::", "fc00::1", "fd12:3456:789a::1", "fd00:ec2::254", "fe80::1", "febf::1", "fec0::1", "ff02::1",
        // IPv6: documentacion, Teredo, discard, IPv4-compatible
        "2001:db8::1", "2001:0:4136:e378:8000:63bf:3fff:fdd2", "100::1", "::a00:1", "::7f00:1",
        // IPv4 mapeada en IPv6 (Java la normaliza a IPv4)
        "::ffff:10.0.0.1", "::ffff:127.0.0.1", "::ffff:169.254.169.254",
        // NAT64 y 6to4 con IPv4 privada embebida
        "64:ff9b::a00:1", "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "2002:0a00:0001::1", "2002:7f00:1::1",
        "2002:a9fe:a9fe::1", "64:ff9b:1::1"})
    void bloqueaRangosProhibidos(String literal) throws Exception {
        assertThat(blocked(literal)).as(literal).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "8.8.8.8", "1.1.1.1", "93.184.216.34", "11.0.0.1", "172.15.255.255", "172.32.0.1", "192.167.255.255",
        "192.169.0.1", "100.63.255.255", "100.128.0.1", "169.253.255.255", "169.255.0.1", "198.17.255.255",
        "198.20.0.1", "2606:4700:4700::1111", "2001:4860:4860::8888", "2a00:1450:4001::1", "64:ff9b::808:808",
        "2002:0808:0808::1", "::ffff:8.8.8.8"})
    void permiteUnicastPublico(String literal) throws Exception {
        assertThat(blocked(literal)).as(literal).isFalse();
    }

    @Test
    void cidrRespetaLosLimitesDeCadaPrefijo() {
        AddressPolicy.Cidr cgnat = AddressPolicy.Cidr.of("100.64.0.0", 10);
        assertThat(cgnat.contains(new byte[] {100, 64, 0, 0})).isTrue();
        assertThat(cgnat.contains(new byte[] {100, 127, (byte) 255, (byte) 255})).isTrue();
        assertThat(cgnat.contains(new byte[] {100, 63, (byte) 255, (byte) 255})).isFalse();
        assertThat(cgnat.contains(new byte[] {100, (byte) 128, 0, 0})).isFalse();
        AddressPolicy.Cidr rfc1918 = AddressPolicy.Cidr.of("172.16.0.0", 12);
        assertThat(rfc1918.contains(new byte[] {(byte) 172, 31, 1, 1})).isTrue();
        assertThat(rfc1918.contains(new byte[] {(byte) 172, 32, 0, 1})).isFalse();
        // familia distinta: un prefijo IPv4 nunca coincide con una direccion IPv6.
        assertThat(rfc1918.contains(new byte[16])).isFalse();
    }
}
