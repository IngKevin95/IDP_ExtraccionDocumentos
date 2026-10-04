package com.idp.renderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.renderer.config.RendererProperties;
import com.idp.renderer.core.RenderException;
import com.idp.renderer.security.AntivirusScanner.ScanResult;
import com.idp.renderer.security.ClamAvScanner;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifica el protocolo INSTREAM contra un clamd simulado (el real se prueba en ClamAvContainerTest). */
class ClamAvScannerTest {

    @TempDir
    Path dir;

    private ClamAvScanner scanner(int port) {
        return new ClamAvScanner(new RendererProperties(
                new RendererProperties.Clamav("127.0.0.1", port, Duration.ofSeconds(2), Duration.ofSeconds(5)),
                new RendererProperties.Limits(1, 1, 1, 1, Duration.ofSeconds(1)),
                new RendererProperties.Libreoffice("soffice", Duration.ofSeconds(1)),
                new RendererProperties.Raster(72)));
    }

    /** clamd simulado: lee zINSTREAM + chunks, responde segun contenido (o respuesta fija) y cierra. */
    private static Thread fakeClamd(ServerSocket server, AtomicReference<byte[]> received, String reply) {
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                byte[] cmd = in.readNBytes("zINSTREAM\0".length());
                assertThat(new String(cmd, StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                int len;
                while ((len = in.readInt()) > 0) {
                    data.write(in.readNBytes(len));
                }
                received.set(data.toByteArray());
                String r = reply != null ? reply
                        : new String(data.toByteArray(), StandardCharsets.ISO_8859_1).contains("EICAR")
                                ? "stream: Eicar-Test-Signature FOUND" : "stream: OK";
                OutputStream out = s.getOutputStream();
                out.write((r + "\0").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        t.start();
        return t;
    }

    @Test
    void ac04_instream_archivoLimpio_devuelveOk() throws Exception {
        Path f = Files.write(dir.resolve("a.bin"), new byte[200_000]);
        AtomicReference<byte[]> received = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = fakeClamd(server, received, null);
            ScanResult r = scanner(server.getLocalPort()).scan(f);
            t.join(5000);
            assertThat(r.clean()).isTrue();
            assertThat(received.get()).hasSize(200_000);
        }
    }

    @Test
    void ac04_instream_eicar_devuelveInfectadoConFirma() throws Exception {
        Path f = Files.writeString(dir.resolve("e.com"), TestDocs.EICAR);
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = fakeClamd(server, new AtomicReference<>(), null);
            ScanResult r = scanner(server.getLocalPort()).scan(f);
            t.join(5000);
            assertThat(r.clean()).isFalse();
            assertThat(r.signature()).isEqualTo("Eicar-Test-Signature");
        }
    }

    @Test
    void ac04_respuestaDeErrorDeClamd_fallaCerrada() throws Exception {
        Path f = Files.write(dir.resolve("a.bin"), new byte[10]);
        try (ServerSocket server = new ServerSocket(0)) {
            Thread t = fakeClamd(server, new AtomicReference<>(), "INSTREAM size limit exceeded. ERROR");
            assertThatThrownBy(() -> scanner(server.getLocalPort()).scan(f))
                    .isInstanceOfSatisfying(RenderException.class,
                            e -> assertThat(e.code()).isEqualTo("ERR_SCANNER_UNAVAILABLE"));
            t.join(5000);
        }
    }

    @Test
    void ac04_clamdCaido_fallaCerradaSinPermitirElDocumento() throws Exception {
        Path f = Files.write(dir.resolve("a.bin"), new byte[10]);
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertThatThrownBy(() -> scanner(closedPort).scan(f))
                .isInstanceOfSatisfying(RenderException.class, e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.code()).isEqualTo("ERR_SCANNER_UNAVAILABLE");
                });
    }
}
