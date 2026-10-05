package com.idp.notification.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Politica de direcciones destino (SEC-027). Deniega todo lo que no sea unicast publico: privadas (RFC1918),
 * loopback, link-local (incluye metadata cloud 169.254.169.254), CGNAT 100.64/10, IPv6 ULA y link-local,
 * multicast, reservadas, documentacion y los prefijos de transicion IPv6 (NAT64, 6to4, Teredo) que embeben IPv4.
 */
@FunctionalInterface
public interface AddressPolicy {

    boolean isBlocked(InetAddress address);

    /** Politica estricta de produccion. */
    AddressPolicy STRICT = Strict::blocked;

    /** Implementacion de la politica estricta. */
    final class Strict {

        private static final List<Cidr> V4 = List.of(
            Cidr.of("0.0.0.0", 8),
            Cidr.of("10.0.0.0", 8),
            Cidr.of("100.64.0.0", 10),
            Cidr.of("127.0.0.0", 8),
            Cidr.of("169.254.0.0", 16),
            Cidr.of("172.16.0.0", 12),
            Cidr.of("192.0.0.0", 24),
            Cidr.of("192.0.2.0", 24),
            Cidr.of("192.88.99.0", 24),
            Cidr.of("192.168.0.0", 16),
            Cidr.of("198.18.0.0", 15),
            Cidr.of("198.51.100.0", 24),
            Cidr.of("203.0.113.0", 24),
            Cidr.of("224.0.0.0", 4),
            Cidr.of("240.0.0.0", 4));

        private static final List<Cidr> V6 = List.of(
            Cidr.of("::", 128),
            Cidr.of("::1", 128),
            Cidr.of("fc00::", 7),
            Cidr.of("fe80::", 10),
            Cidr.of("fec0::", 10),
            Cidr.of("ff00::", 8),
            Cidr.of("2001::", 32),
            Cidr.of("2001:db8::", 32),
            Cidr.of("100::", 64));

        private Strict() {
        }

        static boolean blocked(InetAddress address) {
            if (address instanceof Inet4Address) {
                return inV4(address.getAddress());
            }
            if (address instanceof Inet6Address) {
                return blockedV6(address.getAddress());
            }
            return true;
        }

        private static boolean inV4(byte[] ip) {
            for (Cidr c : V4) {
                if (c.contains(ip)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean blockedV6(byte[] ip) {
            for (Cidr c : V6) {
                if (c.contains(ip)) {
                    return true;
                }
            }
            // IPv4-compatible (::a.b.c.d) y mapeada (::ffff:a.b.c.d): no hay destino legitimo, se bloquean.
            if (zeros(ip, 0, 10) && ((ip[10] == 0 && ip[11] == 0) || (ip[10] == (byte) 0xff && ip[11] == (byte) 0xff))) {
                return true;
            }
            // NAT64 64:ff9b::/96: se juzga la IPv4 de los ultimos 4 bytes; 64:ff9b:1::/48 (uso local) se bloquea.
            if (ip[0] == 0x00 && ip[1] == 0x64 && ip[2] == (byte) 0xff && ip[3] == (byte) 0x9b) {
                if (zeros(ip, 4, 12)) {
                    return inV4(new byte[] {ip[12], ip[13], ip[14], ip[15]});
                }
                return true;
            }
            // 6to4 2002::/16: IPv4 en los bytes 2..5.
            if (ip[0] == 0x20 && ip[1] == 0x02) {
                return inV4(new byte[] {ip[2], ip[3], ip[4], ip[5]});
            }
            return false;
        }

        private static boolean zeros(byte[] ip, int from, int to) {
            for (int i = from; i < to; i++) {
                if (ip[i] != 0) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Prefijo CIDR sobre bytes de direccion. */
    record Cidr(byte[] network, int prefix) {

        static Cidr of(String address, int prefix) {
            try {
                return new Cidr(InetAddress.getByName(address).getAddress(), prefix);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException(e);
            }
        }

        boolean contains(byte[] ip) {
            if (ip.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (ip[i] != network[i]) {
                    return false;
                }
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (ip[full] & mask) == (network[full] & mask);
        }
    }
}
