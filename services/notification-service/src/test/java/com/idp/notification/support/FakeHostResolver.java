package com.idp.notification.support;

import com.idp.notification.net.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolvedor DNS falso: cada nombre tiene una secuencia de respuestas (la ultima se repite), lo que permite simular
 * DNS rebinding (publica primero, privada despues) y registros mixtos. Cuenta las consultas por nombre.
 */
public final class FakeHostResolver implements HostResolver {

    private final Map<String, List<String[]>> sequences = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    /** Siempre las mismas direcciones (literales IP) para el nombre. */
    public FakeHostResolver map(String host, String... ips) {
        sequences.put(host, List.<String[]>of(ips));
        return this;
    }

    /** Respuestas sucesivas: la n-esima consulta devuelve la n-esima lista; la ultima se repite. */
    public FakeHostResolver sequence(String host, String[]... answers) {
        sequences.put(host, List.of(answers));
        return this;
    }

    public int calls(String host) {
        AtomicInteger n = calls.get(host);
        return n == null ? 0 : n.get();
    }

    public void reset() {
        sequences.clear();
        calls.clear();
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        List<String[]> seq = sequences.get(host);
        if (seq == null) {
            throw new UnknownHostException(host);
        }
        int n = calls.computeIfAbsent(host, h -> new AtomicInteger()).getAndIncrement();
        String[] ips = seq.get(Math.min(n, seq.size() - 1));
        InetAddress[] out = new InetAddress[ips.length];
        for (int i = 0; i < ips.length; i++) {
            out[i] = InetAddress.getByAddress(host, InetAddress.getByName(ips[i]).getAddress());
        }
        return out;
    }
}
