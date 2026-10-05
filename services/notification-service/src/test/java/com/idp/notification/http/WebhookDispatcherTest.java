package com.idp.notification.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.notification.domain.DeliveryStatus;
import com.idp.notification.domain.FailureReason;
import com.idp.notification.domain.WebhookDelivery;
import com.idp.notification.http.WebhookDispatcher.Attempt;
import com.idp.notification.http.WebhookDispatcher.Kind;
import com.idp.notification.net.AddressPolicy;
import com.idp.notification.net.SsrfViolationException;
import com.idp.notification.net.WebhookUrlPolicy;
import com.idp.notification.support.MutableClock;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Clasificacion de resultados (AC-06), revalidacion de la URL (AC-03/04) y armado de la solicitud (AC-05). */
class WebhookDispatcherTest {

    private static final List<String> ALLOW = List.of("api.banco.com");
    private final MutableClock clock = new MutableClock();
    private final HmacSignatureService hmac = new HmacSignatureService();
    private final WebhookUrlPolicy policy = new WebhookUrlPolicy(AddressPolicy.STRICT, false);
    private final AtomicReference<Map<String, String>> sentHeaders = new AtomicReference<>();
    private final AtomicReference<byte[]> sentBody = new AtomicReference<>();
    private final AtomicReference<URI> sentUri = new AtomicReference<>();

    private final WebhookDelivery delivery = new WebhookDelivery(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "extraccion.aprobada",
        "{\"id\":\"d1\",\"status\":\"APROBADA\"}", DeliveryStatus.PENDIENTE, 0, Instant.now(), null, null, null, null,
        null, Instant.now());

    private WebhookDispatcher dispatcher(WebhookTransport transport) {
        return new WebhookDispatcher(transport, policy, hmac, clock);
    }

    private WebhookTransport replying(int status) {
        return (uri, body, headers) -> {
            sentUri.set(uri);
            sentBody.set(body);
            sentHeaders.set(headers);
            return status;
        };
    }

    @Test
    void ac05_armaTimestampActualFirmaVerificableYIdDeEvento() {
        clock.set(Instant.parse("2026-10-05T12:00:00Z"));
        Attempt a = dispatcher(replying(200)).dispatch("https://api.banco.com/hook", delivery, List.of("s-actual"), ALLOW);
        assertThat(a.kind()).isEqualTo(Kind.SUCCESS);
        Map<String, String> h = sentHeaders.get();
        assertThat(h.get("X-Hub-Timestamp")).isEqualTo(Long.toString(clock.instant().getEpochSecond()));
        assertThat(h.get("X-Hub-Event-Id")).isEqualTo(delivery.id().toString());
        assertThat(sentBody.get()).isEqualTo(delivery.payload().getBytes(StandardCharsets.UTF_8));
        assertThat(hmac.verify(h.get("X-Hub-Signature-256"), List.of("s-actual"),
            Long.parseLong(h.get("X-Hub-Timestamp")), sentBody.get(), clock.instant(), Duration.ofMinutes(5))).isTrue();
        assertThat(sentUri.get().getHost()).isEqualTo("api.banco.com");
    }

    @Test
    void ac09_conRotacionLaCabeceraLlevaLasFirmasDeAmbosSecretos() {
        dispatcher(replying(204)).dispatch("https://api.banco.com/hook", delivery, List.of("nuevo", "anterior"), ALLOW);
        String header = sentHeaders.get().get("X-Hub-Signature-256");
        long ts = Long.parseLong(sentHeaders.get().get("X-Hub-Timestamp"));
        assertThat(header.split(",")).hasSize(2);
        assertThat(hmac.verify(header, List.of("anterior"), ts, sentBody.get(), clock.instant(), Duration.ofMinutes(5))).isTrue();
        assertThat(hmac.verify(header, List.of("nuevo"), ts, sentBody.get(), clock.instant(), Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void ac17_cadaIntentoLlevaTimestampNuevoPeroElMismoIdYCuerpo() {
        WebhookDispatcher d = dispatcher(replying(200));
        d.dispatch("https://api.banco.com/hook", delivery, List.of("s"), ALLOW);
        String ts1 = sentHeaders.get().get("X-Hub-Timestamp");
        String id1 = sentHeaders.get().get("X-Hub-Event-Id");
        byte[] body1 = sentBody.get();
        clock.advance(Duration.ofSeconds(90));
        d.dispatch("https://api.banco.com/hook", delivery, List.of("s"), ALLOW);
        assertThat(sentHeaders.get().get("X-Hub-Timestamp")).isNotEqualTo(ts1);
        assertThat(sentHeaders.get().get("X-Hub-Event-Id")).isEqualTo(id1);
        assertThat(sentBody.get()).isEqualTo(body1);
    }

    @ParameterizedTest
    @CsvSource({"200,SUCCESS", "201,SUCCESS", "204,SUCCESS", "299,SUCCESS", "500,RETRYABLE", "502,RETRYABLE",
        "503,RETRYABLE", "504,RETRYABLE", "429,RETRYABLE", "408,RETRYABLE", "400,PERMANENT", "401,PERMANENT",
        "403,PERMANENT", "404,PERMANENT", "410,PERMANENT", "301,PERMANENT", "302,PERMANENT", "307,PERMANENT",
        "308,PERMANENT"})
    void ac06_clasificaPorCodigoHttp(int status, Kind kind) {
        Attempt a = dispatcher(replying(status)).dispatch("https://api.banco.com/hook", delivery, List.of("s"), ALLOW);
        assertThat(a.kind()).isEqualTo(kind);
        assertThat(a.httpStatus()).isEqualTo(status);
        if (kind != Kind.SUCCESS) {
            assertThat(a.reason()).isEqualTo(FailureReason.HTTP_ERROR_THRESHOLD_EXCEEDED);
        }
    }

    @Test
    void ac06_erroresDeRedSonReintentablesConMotivoPropio() {
        Attempt timeout = dispatcher((u, b, h) -> {
            throw new SocketTimeoutException("read timed out");
        }).dispatch("https://api.banco.com/hook", delivery, List.of("s"), ALLOW);
        assertThat(timeout.kind()).isEqualTo(Kind.RETRYABLE);
        assertThat(timeout.reason()).isEqualTo(FailureReason.CONNECTION_TIMEOUT);
        Attempt refused = dispatcher((u, b, h) -> {
            throw new ConnectException("refused");
        }).dispatch("https://api.banco.com/hook", delivery, List.of("s"), ALLOW);
        assertThat(refused.kind()).isEqualTo(Kind.RETRYABLE);
        assertThat(refused.reason()).isEqualTo(FailureReason.ENDPOINT_UNREACHABLE);
        Attempt tls = dispatcher((u, b, h) -> {
            throw new javax.net.ssl.SSLHandshakeException("bad cert");
        }).dispatch("https://api.banco.com/hook", delivery, List.of("s"), ALLOW);
        assertThat(tls.reason()).isEqualTo(FailureReason.ENDPOINT_UNREACHABLE);
    }

    @Test
    void ac03_violacionSsrfDelTransporteEsDefinitivaYNoSeReintenta() {
        Attempt a = dispatcher((u, b, h) -> {
            throw new SsrfViolationException("BLOCKED_ADDRESS", "x");
        }).dispatch("https://api.banco.com/hook", delivery, List.of("s"), ALLOW);
        assertThat(a.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(a.reason()).isEqualTo(FailureReason.SSRF_BLOCKED);
        assertThat(a.ssrf()).isTrue();
        // Tambien cuando el cliente HTTP la envuelve en una IOException.
        Attempt wrapped = dispatcher((u, b, h) -> {
            throw new IOException("wrapper", new SsrfViolationException("BLOCKED_ADDRESS", "x"));
        }).dispatch("https://api.banco.com/hook", delivery, List.of("s"), ALLOW);
        assertThat(wrapped.reason()).isEqualTo(FailureReason.SSRF_BLOCKED);
    }

    @Test
    void ac04_urlConMetadataCloudAbortaSinEmitirSolicitud() {
        Attempt a = dispatcher((u, b, h) -> {
            throw new AssertionError("no debe enviarse");
        }).dispatch("http://169.254.169.254/latest/meta-data/", delivery, List.of("s"), List.of("169.254.169.254"));
        assertThat(a.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(a.reason()).isEqualTo(FailureReason.SSRF_BLOCKED);
        assertThat(a.code()).isEqualTo("BLOCKED_ADDRESS");
    }

    @Test
    void ac10_hostFueraDeLaAllowlistVigenteSeBloqueaAlDespachar() {
        Attempt a = dispatcher((u, b, h) -> {
            throw new AssertionError("no debe enviarse");
        }).dispatch("https://api.banco.com/hook", delivery, List.of("s"), List.of("otro.banco.com"));
        assertThat(a.reason()).isEqualTo(FailureReason.SSRF_BLOCKED);
        assertThat(a.code()).isEqualTo("HOST_NOT_ALLOWED");
    }
}
