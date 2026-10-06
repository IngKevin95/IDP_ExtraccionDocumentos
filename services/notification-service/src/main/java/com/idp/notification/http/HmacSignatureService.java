package com.idp.notification.http;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Firma HMAC-SHA256 de los webhooks (SEC-028): {@code HMAC(secret, timestamp + cuerpo)} con el timestamp en
 * epoch-segundos ASCII seguido de los bytes exactos del cuerpo (siempre un objeto JSON, que empieza por '{', por
 * lo que la concatenacion es inequivoca). Durante una rotacion la cabecera lleva una firma por secreto activo,
 * separadas por coma; el receptor acepta si cualquiera coincide y rechaza timestamps fuera de ventana (anti-replay).
 */
public final class HmacSignatureService {

    public static final String HEADER_SIGNATURE = "X-Hub-Signature-256";
    public static final String HEADER_TIMESTAMP = "X-Hub-Timestamp";
    public static final String HEADER_EVENT_ID = "X-Hub-Event-Id";
    public static final String PREFIX = "sha256=";

    private static final String ALGORITHM = "HmacSHA256";

    /** Firma individual con prefijo {@code sha256=} y hex en minusculas. */
    public String sign(String secret, long timestampSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            mac.update(Long.toString(timestampSeconds).getBytes(StandardCharsets.US_ASCII));
            mac.update(body);
            return PREFIX + HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }

    /** Valor de la cabecera: una firma por secreto activo (vigente primero, anterior despues). */
    public String header(List<String> activeSecrets, long timestampSeconds, byte[] body) {
        if (activeSecrets.isEmpty() || activeSecrets.size() > 2) {
            throw new IllegalArgumentException("Se requieren uno o dos secretos activos");
        }
        return String.join(",", activeSecrets.stream().map(s -> sign(s, timestampSeconds, body)).toList());
    }

    /**
     * Verificacion del lado receptor (referencia y pruebas): ventana de tiempo y comparacion en tiempo constante
     * contra cada secreto candidato.
     */
    public boolean verify(String headerValue, List<String> secrets, long timestampSeconds, byte[] body, Instant now,
                          Duration tolerance) {
        if (headerValue == null || Math.abs(now.getEpochSecond() - timestampSeconds) > tolerance.toSeconds()) {
            return false;
        }
        boolean ok = false;
        for (String provided : headerValue.split(",")) {
            byte[] p = provided.strip().getBytes(StandardCharsets.US_ASCII);
            for (String secret : secrets) {
                byte[] expected = sign(secret, timestampSeconds, body).getBytes(StandardCharsets.US_ASCII);
                // Sin cortocircuito: el tiempo no depende de cual firma coincide.
                ok |= MessageDigest.isEqual(expected, p);
            }
        }
        return ok;
    }
}
