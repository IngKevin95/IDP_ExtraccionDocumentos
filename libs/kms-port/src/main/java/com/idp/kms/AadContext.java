package com.idp.kms;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Serializacion canonica e inequivoca (ordenada, con prefijo de longitud) del contexto AAD. */
public final class AadContext {

    private AadContext() {
    }

    public static byte[] canonical(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            return new byte[0];
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(context).entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue();
            sb.append(e.getKey().length()).append(':').append(e.getKey())
                .append(v.length()).append(':').append(v).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
