package com.idp.notification.http;

import com.idp.notification.domain.FailureReason;
import com.idp.notification.domain.WebhookDelivery;
import com.idp.notification.net.SsrfViolationException;
import com.idp.notification.net.WebhookUrlPolicy;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Un intento de entrega: revalida la URL (allowlist vigente y anti-SSRF), firma el cuerpo con los secretos activos
 * y lo envia por el transporte seguro. Clasifica el resultado: exito, error reintentable (5xx, 408, 429, timeouts,
 * red) o definitivo (SSRF, 3xx porque no se siguen redirects, 4xx del receptor).
 */
public final class WebhookDispatcher {

    /** Resultado de un intento. */
    public record Attempt(Kind kind, Integer httpStatus, String code, FailureReason reason, long latencyMs) {
        public boolean ssrf() {
            return reason == FailureReason.SSRF_BLOCKED;
        }
    }

    /** Clasificacion. */
    public enum Kind { SUCCESS, RETRYABLE, PERMANENT }

    private final WebhookTransport transport;
    private final WebhookUrlPolicy urlPolicy;
    private final HmacSignatureService hmac;
    private final Clock clock;

    public WebhookDispatcher(WebhookTransport transport, WebhookUrlPolicy urlPolicy, HmacSignatureService hmac,
                             Clock clock) {
        this.transport = transport;
        this.urlPolicy = urlPolicy;
        this.hmac = hmac;
        this.clock = clock;
    }

    /**
     * @param activeSecrets uno (vigente) o dos (vigente y anterior durante una rotacion) secretos en claro
     */
    public Attempt dispatch(String url, WebhookDelivery delivery, List<String> activeSecrets,
                            List<String> allowedHosts) {
        URI uri;
        try {
            uri = urlPolicy.validate(url, allowedHosts);
        } catch (SsrfViolationException e) {
            return new Attempt(Kind.PERMANENT, null, e.code(), FailureReason.SSRF_BLOCKED, 0);
        }
        byte[] body = delivery.payload().getBytes(StandardCharsets.UTF_8);
        long timestamp = clock.instant().getEpochSecond();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put(HmacSignatureService.HEADER_TIMESTAMP, Long.toString(timestamp));
        headers.put(HmacSignatureService.HEADER_EVENT_ID, delivery.id().toString());
        headers.put(HmacSignatureService.HEADER_SIGNATURE, hmac.header(activeSecrets, timestamp, body));
        long start = System.nanoTime();
        try {
            int status = transport.post(uri, body, headers);
            long latency = (System.nanoTime() - start) / 1_000_000;
            return classify(status, latency);
        } catch (IOException e) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            SsrfViolationException ssrf = findSsrf(e);
            if (ssrf != null) {
                return new Attempt(Kind.PERMANENT, null, ssrf.code(), FailureReason.SSRF_BLOCKED, latency);
            }
            if (e instanceof InterruptedIOException) {
                return new Attempt(Kind.RETRYABLE, null, "TIMEOUT", FailureReason.CONNECTION_TIMEOUT, latency);
            }
            return new Attempt(Kind.RETRYABLE, null, "UNREACHABLE", FailureReason.ENDPOINT_UNREACHABLE, latency);
        } catch (SsrfViolationException e) {
            return new Attempt(Kind.PERMANENT, null, e.code(), FailureReason.SSRF_BLOCKED,
                (System.nanoTime() - start) / 1_000_000);
        }
    }

    private static Attempt classify(int status, long latency) {
        if (status >= 200 && status < 300) {
            return new Attempt(Kind.SUCCESS, status, null, null, latency);
        }
        if (status >= 500 || status == 408 || status == 429) {
            return new Attempt(Kind.RETRYABLE, status, "HTTP_" + status, FailureReason.HTTP_ERROR_THRESHOLD_EXCEEDED,
                latency);
        }
        // 3xx (redirects no seguidos) y 4xx: el receptor lo rechaza; reintentar no cambia el resultado.
        return new Attempt(Kind.PERMANENT, status, "HTTP_" + status, FailureReason.HTTP_ERROR_THRESHOLD_EXCEEDED,
            latency);
    }

    private static SsrfViolationException findSsrf(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof SsrfViolationException s) {
                return s;
            }
        }
        return null;
    }
}
