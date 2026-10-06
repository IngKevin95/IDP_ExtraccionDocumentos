package com.idp.notification.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;

/**
 * Resolvedor DNS del cliente HTTP saliente (SEC-027). Resuelve el nombre una sola vez por conexion, rechaza el
 * destino si CUALQUIER registro cae en un rango prohibido (un atacante puede mezclar IP publicas y privadas) y
 * devuelve las direcciones ya validadas: el cliente conecta a esas IP y no vuelve a consultar DNS, de modo que
 * un rebinding posterior no tiene efecto (TOCTOU).
 */
public final class SsrfGuard implements DnsResolver {

    private final HostResolver resolver;
    private final AddressPolicy policy;

    public SsrfGuard(HostResolver resolver, AddressPolicy policy) {
        this.resolver = resolver;
        this.policy = policy;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] all = resolver.resolve(host);
        if (all == null || all.length == 0) {
            throw new UnknownHostException(host);
        }
        for (InetAddress a : all) {
            if (policy.isBlocked(a)) {
                throw new SsrfViolationException("BLOCKED_ADDRESS", "El destino resuelve a una direccion prohibida");
            }
        }
        return all.clone();
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }
}
