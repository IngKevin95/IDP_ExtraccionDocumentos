package com.idp.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.document.infra.HttpRendererClient;
import com.idp.document.infra.RendererClient.RejectedException;
import com.idp.document.infra.RendererClient.RenderResult;
import com.idp.document.infra.RendererClient.UnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T-06: adaptador HTTP del renderer contra un servidor falso (contrato contracts/openapi/renderer.yaml). */
class HttpRendererClientTest {

    private HttpServer server;
    private volatile int status;
    private volatile byte[] response;
    private volatile String contentType;
    private volatile String receivedContentType;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/render", ex -> {
            receivedContentType = ex.getRequestHeaders().getFirst("Content-Type");
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(status, response.length);
            ex.getResponseBody().write(response);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpRendererClient client() {
        return new HttpRendererClient("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2),
                Duration.ofSeconds(5), null);
    }

    private static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                z.putNextEntry(new ZipEntry(namesAndContents[i]));
                z.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void ac03_parseaPaginasOrdenadasYCapaDeTexto() throws IOException {
        status = 200;
        contentType = "application/zip";
        response = zip("page_2.png", "dos", "page_1.png", "uno", "text_layer.json", "{\"pages\":[]}");

        RenderResult r = client().render("a.pdf", "application/pdf", new byte[] {1});

        assertThat(receivedContentType).startsWith("multipart/form-data");
        assertThat(r.pages()).extracting(p -> p.number()).containsExactly(1, 2);
        assertThat(new String(r.pages().get(0).png(), StandardCharsets.UTF_8)).isEqualTo("uno");
        assertThat(new String(r.textLayer(), StandardCharsets.UTF_8)).isEqualTo("{\"pages\":[]}");
    }

    @Test
    void ac04_respuesta4xxSeInterpretaComoRechazoConElCodigoDelRenderer() {
        status = 422;
        contentType = "application/json";
        response = "{\"code\":\"ERR_MALWARE_DETECTED\",\"message\":\"x\",\"incidentId\":\"i\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> client().render("a.pdf", "application/pdf", new byte[] {1}))
                .isInstanceOfSatisfying(RejectedException.class, e -> {
                    assertThat(e.code()).isEqualTo("ERR_MALWARE_DETECTED");
                    assertThat(e.status()).isEqualTo(422);
                });
    }

    @Test
    void ac04_respuesta5xxYZipSinPaginasSonTransitorios() throws IOException {
        status = 500;
        contentType = "application/json";
        response = "{}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> client().render("a.pdf", "application/pdf", new byte[] {1}))
                .isInstanceOf(UnavailableException.class);

        status = 200;
        contentType = "application/zip";
        response = zip("text_layer.json", "{}");
        assertThatThrownBy(() -> client().render("a.pdf", "application/pdf", new byte[] {1}))
                .isInstanceOf(UnavailableException.class);
    }

    @Test
    void ac04_servidorInaccesibleEsTransitorio() {
        server.stop(0);
        assertThatThrownBy(() -> client().render("a.pdf", "application/pdf", new byte[] {1}))
                .isInstanceOf(UnavailableException.class);
    }
}
