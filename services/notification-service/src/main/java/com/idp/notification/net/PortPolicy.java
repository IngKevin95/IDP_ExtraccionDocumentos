package com.idp.notification.net;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Puertos de destino permitidos: solo 443 por defecto, mas una lista explicita de plataforma. En dev-mode se
 * admite ademas cualquier puerto sin privilegios (>= 1024) para los receptores de prueba. Impide usar el webhook
 * como sonda de servicios internos (6379, 5432, 9200...) aunque el host pase la allowlist.
 */
public final class PortPolicy {

    private final Set<Integer> allowed;
    private final boolean devMode;

    public PortPolicy(Collection<Integer> platformPorts, boolean devMode) {
        Set<Integer> ports = new TreeSet<>();
        ports.add(443);
        for (Integer p : platformPorts) {
            if (p == null || p < 1 || p > 65535) {
                throw new IllegalArgumentException("Puerto fuera de rango: " + p);
            }
            ports.add(p);
        }
        this.allowed = Set.copyOf(ports);
        this.devMode = devMode;
    }

    public static PortPolicy httpsOnly() {
        return new PortPolicy(Set.of(), false);
    }

    public boolean permits(int port) {
        return allowed.contains(port) || (devMode && port >= 1024);
    }
}
