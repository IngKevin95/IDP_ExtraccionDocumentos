package com.idp.notification.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SEC-028 / AC-05 / AC-09: vector conocido, dos secretos activos, ventana anti-replay y manipulacion. */
class HmacSignatureServiceTest {

    private static final byte[] BODY = "{\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8);
    private static final long TS = 1_700_000_000L;
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private final HmacSignatureService hmac = new HmacSignatureService();

    @Test
    void ac05_firmaCoincideConVectorExactoCalculadoConOpenssl() {
        // printf '1700000000{"id":"x"}' | openssl dgst -sha256 -hmac secret-key
        assertThat(hmac.sign("secret-key", TS, BODY))
            .isEqualTo("sha256=71e0161cfccc84f925c193ca6e92ade98b3f0c41ca9a4cc0f9d05b1f3cf113ec");
    }

    @Test
    void ac05_laFirmaCubreTimestampYCuerpo() {
        String base = hmac.sign("s", TS, BODY);
        assertThat(hmac.sign("s", TS + 1, BODY)).isNotEqualTo(base);
        assertThat(hmac.sign("s", TS, "{\"id\":\"y\"}".getBytes(StandardCharsets.UTF_8))).isNotEqualTo(base);
        assertThat(hmac.sign("otro", TS, BODY)).isNotEqualTo(base);
    }

    @Test
    void ac05_verificaConSecretoCorrectoYRechazaManipulacion() {
        Instant now = Instant.ofEpochSecond(TS + 10);
        String header = hmac.header(List.of("s1"), TS, BODY);
        assertThat(hmac.verify(header, List.of("s1"), TS, BODY, now, WINDOW)).isTrue();
        assertThat(hmac.verify(header, List.of("s2"), TS, BODY, now, WINDOW)).isFalse();
        assertThat(hmac.verify(header, List.of("s1"), TS, "{\"id\":\"z\"}".getBytes(StandardCharsets.UTF_8), now, WINDOW))
            .isFalse();
        assertThat(hmac.verify(header, List.of("s1"), TS + 1, BODY, now, WINDOW)).isFalse();
        assertThat(hmac.verify(null, List.of("s1"), TS, BODY, now, WINDOW)).isFalse();
        assertThat(hmac.verify("sha256=", List.of("s1"), TS, BODY, now, WINDOW)).isFalse();
    }

    @Test
    void ac17_antiReplayRechazaTimestampsFueraDeLaVentana() {
        String header = hmac.header(List.of("s1"), TS, BODY);
        assertThat(hmac.verify(header, List.of("s1"), TS, BODY, Instant.ofEpochSecond(TS + 300), WINDOW)).isTrue();
        assertThat(hmac.verify(header, List.of("s1"), TS, BODY, Instant.ofEpochSecond(TS + 301), WINDOW)).isFalse();
        assertThat(hmac.verify(header, List.of("s1"), TS, BODY, Instant.ofEpochSecond(TS - 301), WINDOW)).isFalse();
    }

    @Test
    void ac09_conDosSecretosActivosLaCabeceraLlevaDosFirmasYCualquieraValida() {
        String header = hmac.header(List.of("nuevo", "anterior"), TS, BODY);
        assertThat(header.split(",")).hasSize(2);
        assertThat(header).startsWith(hmac.sign("nuevo", TS, BODY) + ",").endsWith(hmac.sign("anterior", TS, BODY));
        Instant now = Instant.ofEpochSecond(TS);
        // Un receptor que aun solo conoce el secreto anterior sigue validando; uno migrado valida con el nuevo.
        assertThat(hmac.verify(header, List.of("anterior"), TS, BODY, now, WINDOW)).isTrue();
        assertThat(hmac.verify(header, List.of("nuevo"), TS, BODY, now, WINDOW)).isTrue();
        assertThat(hmac.verify(header, List.of("revocado"), TS, BODY, now, WINDOW)).isFalse();
    }

    @Test
    void exigeUnoODosSecretos() {
        assertThatThrownBy(() -> hmac.header(List.of(), TS, BODY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hmac.header(List.of("a", "b", "c"), TS, BODY))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
