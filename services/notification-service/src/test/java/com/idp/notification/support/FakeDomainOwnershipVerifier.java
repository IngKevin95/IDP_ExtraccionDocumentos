package com.idp.notification.support;

import com.idp.notification.net.DomainOwnershipVerifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Verificador falso: un dominio "publica" el TXT solo cuando el test lo declara con {@link #publish}. */
public final class FakeDomainOwnershipVerifier implements DomainOwnershipVerifier {

    private final Map<String, Boolean> published = new ConcurrentHashMap<>();

    public void publish(String domain) {
        published.put(domain, true);
    }

    public void reset() {
        published.clear();
    }

    @Override
    public boolean owns(String domain, String token) {
        // Igual que el verificador DNS real: vale el TXT del dominio o de un ancestro.
        for (String d = domain; d.indexOf('.') >= 0; d = d.substring(d.indexOf('.') + 1)) {
            if (published.getOrDefault(d, false)) {
                return true;
            }
        }
        return false;
    }
}
