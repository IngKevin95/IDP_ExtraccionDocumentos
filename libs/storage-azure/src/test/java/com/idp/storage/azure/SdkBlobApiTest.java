package com.idp.storage.azure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobImmutabilityPolicyMode;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RetryPolicyType;
import com.idp.storage.ObjectStore.StorageException;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** SdkBlobApi sin red: un HttpClient falso registra las peticiones que el SDK enviaria a Blob. */
class SdkBlobApiTest {

    private static final long MAX = 256L * 1024 * 1024;

    private record Sent(String method, String query, HttpHeaders headers, String body) { }

    private static final class RecordingHttpClient implements HttpClient {
        final List<Sent> sent = new ArrayList<>();

        @Override
        public Mono<HttpResponse> send(HttpRequest request) {
            String body = request.getBodyAsBinaryData() == null ? ""
                : new String(request.getBodyAsBinaryData().toBytes(), StandardCharsets.UTF_8);
            String query = request.getUrl().getQuery() == null ? "" : request.getUrl().getQuery();
            sent.add(new Sent(request.getHttpMethod().name(), query, request.getHeaders(), body));
            boolean legalHold = java.util.Arrays.asList(query.split("&")).contains("comp=legalhold");
            HttpHeaders headers = new HttpHeaders().set("x-ms-version", "2025-01-05")
                .set("x-ms-legal-hold", legalHold ? "true" : "false")
                .set("ETag", "\"0x1\"").set("Last-Modified", "Wed, 01 Jan 2026 00:00:00 GMT")
                .set("x-ms-request-server-encrypted", "true");
            return Mono.just(new StubResponse(request, legalHold ? 200 : 201, headers));
        }

        long count(String queryPart) {
            return sent.stream().filter(s -> java.util.Arrays.asList(s.query().split("&")).contains(queryPart)).count();
        }

        Sent first(String queryPart) {
            return sent.stream().filter(s -> java.util.Arrays.asList(s.query().split("&")).contains(queryPart)).findFirst().orElseThrow();
        }
    }

    private static final class StubResponse extends HttpResponse {
        private final int status;
        private final HttpHeaders headers;

        StubResponse(HttpRequest request, int status, HttpHeaders headers) {
            super(request);
            this.status = status;
            this.headers = headers;
        }

        @Override
        public int getStatusCode() {
            return status;
        }

        @Override
        public String getHeaderValue(String name) {
            return headers.getValue(name);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Flux<ByteBuffer> getBody() {
            return Flux.empty();
        }

        @Override
        public Mono<byte[]> getBodyAsByteArray() {
            return Mono.just(new byte[0]);
        }

        @Override
        public Mono<String> getBodyAsString() {
            return Mono.just("");
        }

        @Override
        public Mono<String> getBodyAsString(Charset charset) {
            return Mono.just("");
        }
    }

    private static SdkBlobApi api(RecordingHttpClient http, BlobImmutabilityPolicyMode mode, long max) {
        BlobServiceClient service = new BlobServiceClientBuilder()
            .endpoint("https://acct.blob.core.windows.net")
            .credential(new StorageSharedKeyCredential("acct", Base64.getEncoder().encodeToString(new byte[32])))
            .httpClient(http)
            .retryOptions(new RequestRetryOptions(RetryPolicyType.FIXED, 1, 5, 10L, 10L, null))
            .buildClient();
        return new SdkBlobApi(service, mode, max);
    }

    @Test
    void elCommitLlevaLaPoliticaDeInmutabilidadConModoYFecha() {
        RecordingHttpClient http = new RecordingHttpClient();
        Instant until = Instant.parse("2030-01-01T00:00:00Z");
        byte[] data = "hola".getBytes(StandardCharsets.UTF_8);
        api(http, BlobImmutabilityPolicyMode.LOCKED, MAX).write("c", "t1/a.txt", new ByteArrayInputStream(data),
            data.length, "text/plain", null, until);

        Sent commit = http.first("comp=blocklist");
        assertEquals("PUT", commit.method());
        assertEquals("locked", commit.headers().getValue("x-ms-immutability-policy-mode").toLowerCase());
        Instant sent = java.time.ZonedDateTime.parse(commit.headers().getValue("x-ms-immutability-policy-until-date"),
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        assertEquals(until, sent);
        assertEquals(1, http.count("comp=block"));
    }

    @Test
    void sinRetencionElCommitNoLlevaPolitica() {
        RecordingHttpClient http = new RecordingHttpClient();
        byte[] data = "hola".getBytes(StandardCharsets.UTF_8);
        api(http, BlobImmutabilityPolicyMode.LOCKED, MAX).write("c", "t1/a.txt", new ByteArrayInputStream(data),
            data.length, null, null, null);
        assertEquals(null, http.first("comp=blocklist").headers().getValue("x-ms-immutability-policy-mode"));
    }

    @Test
    void setLegalHoldInvocaLaOperacionDeLegalHold() {
        RecordingHttpClient http = new RecordingHttpClient();
        api(http, BlobImmutabilityPolicyMode.LOCKED, MAX).setLegalHold("c", "t1/a.txt", true);
        Sent hold = http.first("comp=legalhold");
        assertEquals("PUT", hold.method());
        assertEquals("true", hold.headers().getValue("x-ms-legal-hold"));
    }

    @Test
    void flujoMayorQueLoDeclaradoAbortaSinSubirMasBloques() {
        RecordingHttpClient http = new RecordingHttpClient();
        byte[] data = new byte[SdkBlobApi.BLOCK_SIZE * 3];
        long declarada = SdkBlobApi.BLOCK_SIZE + 1;
        assertThrows(StorageException.class, () -> api(http, BlobImmutabilityPolicyMode.LOCKED, MAX).write("c", "b",
            new ByteArrayInputStream(data), declarada, null, null, null));
        assertEquals(1, http.count("comp=block"));
        assertEquals(0, http.count("comp=blocklist"));
    }

    @Test
    void flujoMayorQueLoDeclaradoEnElPrimerBloqueNoSubeNada() {
        RecordingHttpClient http = new RecordingHttpClient();
        assertThrows(StorageException.class, () -> api(http, BlobImmutabilityPolicyMode.LOCKED, MAX).write("c", "b",
            new ByteArrayInputStream(new byte[100]), 10, null, null, null));
        assertTrue(http.sent.isEmpty());
    }

    @Test
    void longitudMenorQueLaDeclaradaNoConfirmaElBlob() {
        RecordingHttpClient http = new RecordingHttpClient();
        assertThrows(StorageException.class, () -> api(http, BlobImmutabilityPolicyMode.LOCKED, MAX).write("c", "b",
            new ByteArrayInputStream(new byte[10]), 100, null, null, null));
        assertEquals(0, http.count("comp=blocklist"));
    }

    @Test
    void sha256DistintoNoConfirmaElBlob() {
        RecordingHttpClient http = new RecordingHttpClient();
        assertThrows(StorageException.class, () -> api(http, BlobImmutabilityPolicyMode.LOCKED, MAX).write("c", "b",
            new ByteArrayInputStream(new byte[10]), 10, null, new byte[32], null));
        assertEquals(0, http.count("comp=blocklist"));
    }

    @Test
    void objetoQueSuperaElTopeSeRechazaAntesDeSubir() {
        RecordingHttpClient http = new RecordingHttpClient();
        StorageException ex = assertThrows(StorageException.class,
            () -> api(http, BlobImmutabilityPolicyMode.LOCKED, 100).write("c", "b",
                new ByteArrayInputStream(new byte[101]), 101, null, null, null));
        assertTrue(ex.getMessage().contains("tope de 100 bytes"));
        assertTrue(http.sent.isEmpty());
    }

    @Test
    void topeFueraDeRangoSeRechazaAlConstruir() {
        RecordingHttpClient http = new RecordingHttpClient();
        assertThrows(IllegalArgumentException.class, () -> api(http, BlobImmutabilityPolicyMode.LOCKED, 0));
        assertThrows(IllegalArgumentException.class,
            () -> api(http, BlobImmutabilityPolicyMode.LOCKED, 50_001L * SdkBlobApi.BLOCK_SIZE));
    }
}
