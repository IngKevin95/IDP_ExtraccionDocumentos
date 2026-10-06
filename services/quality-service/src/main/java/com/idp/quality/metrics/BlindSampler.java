package com.idp.quality.metrics;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Muestreo ciego configurable de oficios auto-aprobados. La decision es HMAC-SHA256(semilla, tenant|documento)
 * mapeado a [0,1): determinista (reintentos coherentes) pero impredecible sin la semilla, de modo que nadie puede
 * saber de antemano que oficio se revisara a ciegas (SEC-051).
 *
 * <p>Fuera de dev-mode la semilla es obligatoria y de al menos {@value #MIN_SEED_BYTES} bytes cuando algun muestreo
 * esta activo. En dev-mode con semilla vacia se genera una clave aleatoria por arranque (nunca una clave fija).
 * Rotar la semilla cambia la seleccion: oficios antes elegidos pueden no serlo y viceversa.
 */
public final class BlindSampler {

    /** Longitud minima de la semilla (UTF-8) fuera de dev-mode. */
    public static final int MIN_SEED_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] seed;
    private final double defaultRate;
    private final Map<String, Double> rateByTypology;

    public BlindSampler(String seed, double defaultRate, Map<String, Double> rateByTypology, boolean devMode) {
        if (defaultRate < 0 || defaultRate > 1) {
            throw new IllegalArgumentException("La tasa de muestreo debe estar entre 0 y 1");
        }
        rateByTypology.values().forEach(r -> {
            if (r < 0 || r > 1) {
                throw new IllegalArgumentException("La tasa de muestreo debe estar entre 0 y 1");
            }
        });
        boolean sampling = defaultRate > 0 || rateByTypology.values().stream().anyMatch(r -> r > 0);
        byte[] bytes = seed == null ? new byte[0] : seed.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            if (sampling && !devMode) {
                throw new IllegalStateException(
                    "quality.blind-sampling.seed es obligatoria salvo idp.security.dev-mode=true");
            }
            bytes = new byte[MIN_SEED_BYTES];
            RANDOM.nextBytes(bytes);
        } else if (sampling && !devMode && bytes.length < MIN_SEED_BYTES) {
            throw new IllegalStateException(
                "quality.blind-sampling.seed debe tener al menos " + MIN_SEED_BYTES + " bytes");
        }
        this.seed = bytes;
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
            mac.init(new SecretKeySpec(seed, "HmacSHA256"));
            byte[] h = mac.doFinal((tenantId + "|" + documentId).getBytes(StandardCharsets.UTF_8));
            long v = ByteBuffer.wrap(h).getLong() >>> 11; // 53 bits
            return v / (double) (1L << 53);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }
}
