package com.idp.quality.metrics;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Muestreo ciego configurable de oficios auto-aprobados. La decision es HMAC-SHA256(semilla, tenant|documento)
 * mapeado a [0,1): determinista (reintentos coherentes) pero impredecible sin la semilla, de modo que nadie puede
 * saber de antemano que oficio se revisara a ciegas.
 */
public final class BlindSampler {

    private final byte[] seed;
    private final double defaultRate;
    private final Map<String, Double> rateByTypology;

    public BlindSampler(String seed, double defaultRate, Map<String, Double> rateByTypology) {
        if (defaultRate < 0 || defaultRate > 1) {
            throw new IllegalArgumentException("La tasa de muestreo debe estar entre 0 y 1");
        }
        rateByTypology.values().forEach(r -> {
            if (r < 0 || r > 1) {
                throw new IllegalArgumentException("La tasa de muestreo debe estar entre 0 y 1");
            }
        });
        this.seed = seed.getBytes(StandardCharsets.UTF_8);
        this.defaultRate = defaultRate;
        this.rateByTypology = Map.copyOf(rateByTypology);
    }

    public double rateFor(String tipologia) {
        return rateByTypology.getOrDefault(tipologia, defaultRate);
    }

    public boolean shouldSample(UUID tenantId, UUID documentId, String tipologia) {
        double rate = rateFor(tipologia);
        if (rate <= 0) {
            return false;
        }
        if (rate >= 1) {
            return true;
        }
        return unit(tenantId, documentId) < rate;
    }

    private double unit(UUID tenantId, UUID documentId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(seed.length == 0 ? new byte[] {0} : seed, "HmacSHA256"));
            byte[] h = mac.doFinal((tenantId + "|" + documentId).getBytes(StandardCharsets.UTF_8));
            long v = ByteBuffer.wrap(h).getLong() >>> 11; // 53 bits
            return v / (double) (1L << 53);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }
}
